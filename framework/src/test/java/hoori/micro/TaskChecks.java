package hoori.micro;

import hoori.concurrent.Cancellation;
import hoori.concurrent.ScopeStateException;
import hoori.concurrent.TaskCancelledException;
import hoori.concurrent.TaskScope;
import hoori.concurrent.TaskSpec;
import hoori.concurrent.Tasks;
import hoori.http.RequestBudget;
import hoori.rest.RequestException;
import java.time.Duration;
import java.util.Map;

/** Native Micro interactions only; generic scheduler/SDK matrices live upstream. */
public final class TaskChecks {
    public static void main(String[] args) throws Exception {
        Admission admission = new Admission(1, 1);
        try (Admission.Permit held = admission.acquire(RequestBudget.afterMillis(5000), false)) {
            try {
                Tasks.parallel(
                                Tasks.task(() -> {
                                    try (Admission.Permit permit =
                                            admission.acquire(RequestBudget.afterMillis(4000), false)) {
                                        throw new AssertionError("Cancelled waiter admitted");
                                    }
                                }),
                                Tasks.task(() -> {
                                    while (admission.stats().pending == 0) Thread.sleep(1);
                                    throw new RequestException(422, "Expected business failure");
                                }))
                        .failFast()
                        .within(Duration.ofSeconds(5))
                        .map((first, second) -> null);
                throw new AssertionError("Missing failure");
            } catch (Exception expected) {
                require(Failures.classify(expected).status() == 422, "Sibling cancellation hid business failure");
            }
            require(admission.stats().active == 1 && admission.stats().pending == 0, "Cancelled admission leaked");
        }
        for (int i = 0; i < 32; i++) {
            final boolean freeFirst = (i & 1) == 0;
            try {
                TaskScope.named("cancel-race").within(Duration.ofSeconds(5)).run(scope -> {
                    Admission.Permit held = admission.acquire(RequestBudget.afterMillis(5000), false);
                    scope.fork(() -> {
                        try (var permit = admission.acquire(RequestBudget.afterMillis(5000), false)) {
                            Cancellation.checkpoint();
                        }

                        return null;
                    });
                    while (admission.stats().pending == 0) Thread.sleep(1);

                    if (freeFirst) held.close();

                    scope.cancellation().cancel();
                    held.close();
                });
            } catch (Exception expected) {
                require(Failures.classify(expected).kind() == Failures.Kind.CANCELLED, "Cancel race classification");
            }
            require(admission.stats().active == 0 && admission.stats().pending == 0, "Cancel/release retained permit");
        }
        TaskScope.named("registration-recovery").within(Duration.ofSeconds(5)).run(scope -> {
            for (int i = 0; i < 256; i++) {
                try (var permit = admission.acquire(RequestBudget.afterMillis(5000), false)) {
                    permit.check(admission);
                }
            }
        });
        TaskScope.named("admission-registration-capacity").cancellable().run(scope -> {
            var registrations = new Cancellation.Registration[Cancellation.MAX_REGISTRATIONS];
            try {
                for (int i = 0; i < registrations.length; i++)
                    registrations[i] = scope.cancellation().onCancel(() -> {});
                // A free permit needs no callback slot; a queued call still obeys the SDK limit.
                try (var permit = admission.acquire(RequestBudget.afterMillis(5000), false)) {
                    try {
                        admission.acquire(RequestBudget.afterMillis(5000), false);
                        throw new AssertionError("Waiter exceeded cancellation registration capacity");
                    } catch (ScopeStateException expected) {
                        require(
                                admission.stats().pending == 0 && admission.stats().active == 1,
                                "Failed waiter registration leaked its slot");
                    }
                }
            } finally {
                for (var registration : registrations) if (registration != null) registration.close();
            }
        });
        try {
            TaskScope.named("cancel-before-register").cancellable().run(scope -> {
                scope.cancellation().cancel();
                admission.acquire(RequestBudget.afterMillis(5000), false);
                throw new AssertionError("Pre-cancelled work admitted");
            });
        } catch (TaskCancelledException expected) {
            require(admission.stats().active == 0 && admission.stats().pending == 0, "Pre-cancelled work leaked");
        }
        try (Microservice app = Microservice.create("checks", 1, key -> null)) {
            Context context = app.context();
            // Specs may be constructed outside a request; calls cannot run without a live service boundary.
            RemoteClient client = new RemoteClient(app, "recipes", 1);
            TaskSpec<Object> remote = Tasks.task(() -> HttpFixture.call(client, "/recipes", Map.of("id", 1)));
            try {
                remote.run();
                throw new AssertionError("Missing context accepted");
            } catch (IllegalStateException expected) {
            }
            TaskSpec<String> reusable = Tasks.task(() -> context.invocation().requestId());
            String first = app.runTask(reusable);
            String second = app.runTask(reusable);
            require(!first.equals(second), "Deferred spec retained a former invocation");
            app.runTask(() -> {
                try {
                    context.ownerRequest();
                    throw new AssertionError("Service work exposed an HTTP request");
                } catch (IllegalStateException expected) {
                }
                String parent = context.invocation().requestId();

                return Tasks.parallel(
                                Tasks.task(() -> context.invocation().requestId()),
                                Tasks.task(() -> context.invocation().requestId()))
                        .map((a, b) -> {
                            require(a.equals(parent) && b.equals(parent), "Missing inherited metadata");
                            require(context.invocation().origin() == Invocation.Origin.SERVICE, "Wrong origin");

                            return null;
                        });
            });
            require(
                    app.taskDiagnostics().isEmpty() && app.broker().executions.activeCount() == 0,
                    "Completed roots retained");
            try {
                context.invocation();
                throw new AssertionError("Binding survived completion");
            } catch (IllegalStateException expected) {
            }
        }
        System.out.println("Task checks passed: admission cancellation, failure priority, context and root recovery");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
