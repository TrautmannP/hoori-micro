package dev.hoori.micro.data;

import dev.hoori.micro.contracts.dto.Overview;
import dev.hoori.micro.contracts.dto.Recipe;
import dev.hoori.micro.data.client.*;
import dev.hoori.micro.data.config.DatabaseConfiguration;
import dev.hoori.micro.data.controller.*;
import dev.hoori.micro.data.dto.Draft;
import dev.hoori.micro.data.repository.DraftRepository;
import dev.hoori.micro.data.service.*;
import hoori.concurrent.Budget;
import hoori.concurrent.Cancellation;
import hoori.concurrent.OperationFailedException;
import hoori.concurrent.ScopeExtension;
import hoori.concurrent.TaskScope;
import hoori.http.Response;
import hoori.jdbc.JdbcTransactions;
import hoori.jdbi.JdbiTransactions;
import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.mvc.*;
import hoori.transaction.Transactions;
import hoori.validation.ValidationLimits;
import jakarta.validation.Valid;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Handle;

/** Real Micro boundaries and DB contents; fault injection is test-only. No driver or manager implementation here. */
public final class DataChecks {
    private static volatile boolean released, childWaiting, childFinished, queryCancelled;
    private static volatile Cancellation query;
    private static volatile String lastStatus = "NONE";
    private static final Overview PREPARED = new Overview(new Recipe(1, "Kartoffelsuppe"), List.of("Kartoffeln"));

    private static final class CountingFactory implements JdbcTransactions.Factory, AutoCloseable {
        private final JdbcTransactions.Factory delegate = DatabaseConfiguration.factory();
        private final List<WeakReference<Connection>> closed = new ArrayList<>();
        private int opened, active, released, discarded;

        public Connection open(Budget budget) throws SQLException {
            Connection connection = delegate.open(budget);
            synchronized (this) {
                opened++;
                active++;
            }

            return connection;
        }

        public void release(Connection connection) throws SQLException {
            if (!connection.getAutoCommit()) throw new AssertionError("Transaction not physically completed");

            delegate.release(connection);

            if (!connection.isClosed()) throw new AssertionError("Connection still open");

            synchronized (this) {
                active--;
                released++;
                closed.add(new WeakReference<>(connection));
            }
        }

        public void discard(Connection connection) throws SQLException {
            delegate.discard(connection);

            if (!connection.isClosed()) throw new AssertionError("Unsafe connection was not discarded");

            synchronized (this) {
                active--;
                discarded++;
                closed.add(new WeakReference<>(connection));
            }
        }

        synchronized Map<String, Object> snapshot() {
            int retained = 0;
            for (var reference : closed) if (reference.get() != null) retained++;

            return Map.of(
                    "opened",
                    opened,
                    "active",
                    active,
                    "released",
                    released,
                    "discarded",
                    discarded,
                    "retained",
                    retained,
                    "status",
                    lastStatus,
                    "childWaiting",
                    childWaiting,
                    "childFinished",
                    childFinished,
                    "queryCancelled",
                    queryCancelled);
        }

        public synchronized void close() {
            if (active != 0) throw new AssertionError("Service resources closed before transaction drain");

            System.out.println("data_factory_closed active=0");
        }
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        CountingFactory factory = new CountingFactory();
        var manager = new JdbiTransactions(factory, DatabaseConfiguration.IO_TIMEOUT_MILLIS);
        DraftRepository store = new DraftRepository(manager);
        try (Microservice app = Microservice.create("drafts")) {
            var recipes = new RecipeClientHttp(app);
            var pantry = new PantryClientHttp(app);
            var errors = MvcErrors.builder().classify(app::classifyMvc).build();
            var writer = new DraftWriterScoped(new LocalDraftWriter(store), manager);
            app.controller(
                    new DraftControllerMvc(
                            new DraftController(new DraftService(recipes, pantry, writer)),
                            app.jsonLimits(),
                            ValidationLimits.DEFAULT,
                            errors),
                    "drafts:write");
            app.controller(
                    new DataChecks_CasesMvc(
                            new Cases(app, manager, store), app.jsonLimits(), ValidationLimits.DEFAULT, errors),
                    new String[] {null});
            app.own(factory);
            app.routes().get("/discovery", request -> {
                recipes.get(1);
                pantry.items(1);

                return Response.text(200, "discovered");
            });
            app.routes()
                    .get(
                            "/probe",
                            request -> Responses.json(200, factory.snapshot(), JsonTree.CODEC, app.jsonLimits()));
            app.routes().post("/gate/{mode}", request -> {
                released = request.pathParam("mode").equals("open");
                childWaiting = childFinished = false;

                return Response.text(200, "configured");
            });
            app.routes().post("/cancel", request -> {
                Cancellation current = query;

                if (current == null) throw new AssertionError("No query to cancel");

                current.cancel();

                return Response.text(200, "cancel requested");
            });
            app.routes().post("/gc", request -> {
                System.gc();

                return Response.text(200, "collected");
            });
            app.run();
        }
    }

    @RestController
    public static final class Cases {
        private final Microservice app;
        private final JdbiTransactions manager;
        private final DraftRepository store;

        public Cases(Microservice app, JdbiTransactions manager, DraftRepository store) {
            this.app = app;
            this.manager = manager;
            this.store = store;
        }

        @PostMapping("/case/{mode}")
        public Response run(@PathVariable("mode") String mode, @RequestBody @Valid Draft input) throws Exception {
            var operation = TaskScope.named("data.check").with(Transactions.required(manager));

            if (mode.equals("deadline")) operation.within(Duration.ofMillis(250));

            try {
                int pid = operation.call(scope -> {
                    store.save(input.id(), PREPARED);
                    switch (mode) {
                        case "body" -> throw new RequestException(409, "Controlled local conflict");
                        case "nested" -> {
                            Handle outer = manager.jdbi().withHandle(handle -> handle);
                            try {
                                TaskScope.named("data.inner")
                                        .with(Transactions.required(manager))
                                        .run(inner -> {
                                            if (manager.jdbi().withHandle(handle -> handle) != outer)
                                                throw new AssertionError("REQUIRED changed the Handle");

                                            throw new IOException("inner failure");
                                        });
                            } catch (OperationFailedException expected) {
                                if (expected.status() != ScopeExtension.Status.ROLLBACK_ONLY) throw expected;
                            }
                        }
                        case "child" ->
                            scope.fork(() -> {
                                try {
                                    throw new IOException("required child failed");
                                } finally {
                                    childWaiting = true;
                                    while (!released) Thread.sleep(1);
                                    childFinished = true;
                                }
                            });
                        case "foreign" -> {
                            Handle retained = manager.jdbi().withHandle(handle -> handle);
                            scope.fork(() -> {
                                try {
                                    manager.jdbi().useHandle(handle -> handle.execute("select 1"));
                                    throw new AssertionError("Inherited Handle lookup accepted");
                                } catch (IllegalStateException expected) {
                                }
                                try {
                                    retained.execute(
                                            "insert into meal_drafts values (?, 1, 'wrong child', 0)",
                                            input.id() + 1000);
                                    throw new AssertionError("Retained Handle accepted foreign child");
                                } catch (IllegalStateException expected) {
                                }

                                return null;
                            });
                        }
                        case "independent" -> {
                            scope.fork(() -> TaskScope.named("data.child")
                                    .with(Transactions.required(manager))
                                    .call(child -> {
                                        store.save(input.id() + 1000, PREPARED);

                                        return null;
                                    }));
                            scope.join();
                            throw new IOException("Parent fails after independent child commit");
                        }
                        case "deadline" -> {
                            while (true) {
                                Cancellation.checkpoint();
                                Thread.sleep(1);
                            }
                        }
                        case "query" -> {
                            query = Cancellation.current();
                            manager.jdbi()
                                    .useHandle(
                                            handle -> handle.createQuery("select 1 from pg_advisory_xact_lock(724199)")
                                                    .mapTo(Integer.class)
                                                    .one());
                            throw new AssertionError("Blocked query completed without cancellation");
                        }
                        case "committed" ->
                            Transactions.current(manager).synchronize(new Transactions.Synchronization() {
                                public void afterCommit() throws Exception {
                                    throw new IOException("notification failed");
                                }
                            });
                        case "overlap" -> {
                            while (!released) {
                                Cancellation.checkpoint();
                                Thread.sleep(1);
                            }
                        }
                        case "ok", "encode" -> {}
                        default -> throw new RequestException(400, "Unknown test mode");
                    }

                    return manager.jdbi()
                            .withHandle(handle -> handle.createQuery("select pg_backend_pid()")
                                    .mapTo(Integer.class)
                                    .one());
                });
                lastStatus = "COMMITTED";

                if (mode.equals("encode"))
                    return Responses.json(200, Map.of("unsupported", new Object()), JsonTree.CODEC, app.jsonLimits());

                return Responses.json(200, Map.of("pid", pid), JsonTree.CODEC, app.jsonLimits());
            } catch (OperationFailedException failure) {
                lastStatus = failure.status().name();
                queryCancelled = sqlCancelled(failure, 0);
                System.out.println("data_status=" + lastStatus + " query_cancelled=" + queryCancelled);
                throw failure;
            } finally {
                if (mode.equals("query")) query = null;
            }
        }
    }

    private static boolean sqlCancelled(Throwable failure, int depth) {
        if (failure == null || depth == 16) return false;

        if (failure instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;

        if (sqlCancelled(failure.getCause(), depth + 1)) return true;

        for (Throwable suppressed : failure.getSuppressed()) if (sqlCancelled(suppressed, depth + 1)) return true;

        return false;
    }
}
