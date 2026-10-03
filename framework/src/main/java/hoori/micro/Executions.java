package hoori.micro;

import hoori.concurrent.Budget;
import hoori.concurrent.Cancellation;
import hoori.concurrent.TaskContext;
import hoori.concurrent.TaskDiagnostics;
import hoori.concurrent.TaskScope;
import hoori.concurrent.Tasks;
import hoori.concurrent.http.RequestScopes;
import hoori.http.Request;
import hoori.http.RequestBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** Service-owned, bounded root slots. The SDK owns scheduling, context and actual scope drain. */
final class Executions implements AutoCloseable {
    private static final TaskContext.Key<Invocation> INVOCATION = TaskContext.inheritedKey();
    private static final TaskContext.Key<Request> OWNER_REQUEST = TaskContext.localKey();
    private final ServiceConfig config;
    private final RequestScopes requests;
    private final Slot[] active;
    private final long[] outcomes = new long[Failures.Kind.values().length];
    private boolean stopping;
    private volatile boolean cancelling;
    private long sequence, completed, drainNanos;

    Executions(ServiceConfig config) {
        this.config = config;
        active = new Slot[config.incomingCalls + config.incomingPendingCalls];
        requests = new RequestScopes(active.length, Duration.ofMillis(config.workTimeoutMillis));
    }

    <T> T request(Request request, RequestBudget budget, Admission incoming, Tasks.Function<TaskScope, T> body)
            throws Exception {
        Slot slot = enter(new Invocation(request.id(), Invocation.Origin.REQUEST, budget.deadlineNanos()));
        try {
            return requests.call(
                    request,
                    op -> {
                        slot.configured = true;
                        op.with(INVOCATION, slot.invocation)
                                .with(OWNER_REQUEST, request)
                                .withBudget(Budget.until(budget.deadlineNanos()));
                    },
                    scope -> {
                        attach(slot, scope);
                        try {
                            slot.permit = incoming.acquire(budget, false);
                            slot.permit.check(incoming);
                            T value = body.apply(scope);
                            slot.permit.check(incoming);

                            return value;
                        } finally {
                            slot.bodyEnded = System.nanoTime();
                        }
                    });
        } catch (IllegalStateException failure) {
            // Our slots prevent SDK capacity overflow. Before configure, close is the only
            // possible SDK start race; application IllegalStateExceptions are never rewritten.
            if (!slot.configured && cancelling) {
                CallRejectedException stopped = new CallRejectedException(true);
                stopped.initCause(failure);
                record(stopped);
                throw stopped;
            }

            record(failure);
            throw failure;
        } catch (Exception | Error failure) {
            record(failure);
            throw failure;
        } finally {
            release(slot);
        }
    }

    <T> T service(Tasks.Function<TaskScope, T> body) throws Exception {
        RequestBudget budget = RequestBudget.afterMillis(config.workTimeoutMillis);
        Budget parent = Budget.current();

        if (parent.isFinite()) budget = budget.limitedTo(RequestBudget.until(parent.deadlineNanos()));

        RequestBudget rootBudget = budget;
        Invocation invocation;
        synchronized (this) {
            invocation = new Invocation(
                    config.instanceId + "-" + ++sequence, Invocation.Origin.SERVICE, budget.deadlineNanos());
        }
        Slot slot = enter(invocation);
        try {
            return TaskScope.named("service-work")
                    .cancellable()
                    .withBudget(Budget.until(rootBudget.deadlineNanos()))
                    .with(INVOCATION, invocation)
                    .call(scope -> {
                        attach(slot, scope);
                        try {
                            return body.apply(scope);
                        } finally {
                            slot.bodyEnded = System.nanoTime();
                        }
                    });
        } catch (Exception | Error failure) {
            record(failure);
            throw failure;
        } finally {
            release(slot);
        }
    }

    Invocation current() {
        if (!TaskContext.contains(INVOCATION) || Cancellation.current() == null)
            throw new IllegalStateException("A Micro request or runTask boundary is required");

        Invocation invocation = TaskContext.get(INVOCATION);
        synchronized (this) {
            for (Slot slot : active) if (slot != null && slot.invocation == invocation) return invocation;
        }
        throw new IllegalStateException("Execution does not belong to this running service");
    }

    Request ownerRequest() {
        if (current().origin() != Invocation.Origin.REQUEST || !TaskContext.contains(OWNER_REQUEST))
            throw new IllegalStateException("Only the HTTP request owner has its raw request");

        return TaskContext.get(OWNER_REQUEST);
    }

    synchronized boolean requestOwned(Invocation invocation) {
        if (invocation == null || invocation.origin() != Invocation.Origin.REQUEST) return false;

        for (Slot slot : active) if (slot != null && slot.invocation == invocation) return true;

        return false;
    }

    private synchronized Slot enter(Invocation invocation) throws CallRejectedException {
        if (!stopping)
            for (int i = 0; i < active.length; i++)
                if (active[i] == null) {
                    Slot slot = new Slot(invocation, i);
                    active[i] = slot;

                    return slot;
                }

        CallRejectedException failure = new CallRejectedException(stopping);
        record(failure);
        throw failure;
    }

    private void attach(Slot slot, TaskScope scope) {
        Cancellation token = scope.cancellation();
        synchronized (this) {
            slot.scope = scope;
            slot.token = token;
        }

        if (cancelling) token.cancel(new CallRejectedException(true));

        Cancellation.checkpoint();
    }

    private void release(Slot slot) {
        // Keep the business permit through children, resources, timer and binding restoration.
        if (slot.permit != null) slot.permit.close();

        synchronized (this) {
            active[slot.index] = null;
            slot.scope = null;
            slot.token = null;
            slot.permit = null;
            completed++;

            if (slot.bodyEnded != 0) drainNanos += Math.max(0, System.nanoTime() - slot.bodyEnded);
        }
    }

    private void record(Throwable failure) {
        Failures.Kind kind = Failures.classify(failure).kind();
        synchronized (this) {
            outcomes[kind.ordinal()]++;
        }
    }

    synchronized void stop() {
        stopping = true;
    }

    synchronized int activeCount() {
        int count = 0;
        for (Slot slot : active) if (slot != null) count++;

        return count;
    }

    boolean currentOwner() {
        if (!TaskContext.contains(INVOCATION)) return false;

        Invocation invocation = TaskContext.get(INVOCATION);
        synchronized (this) {
            for (Slot slot : active) if (slot != null && slot.invocation == invocation) return true;
        }

        return false;
    }

    void awaitUntil(long deadline) {
        boolean interrupted = Thread.interrupted();
        while (activeCount() != 0 && System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(1_000_000);
            interrupted |= Thread.interrupted();
        }

        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void close() {
        stop();
        cancelling = true;
        Cancellation[] tokens = new Cancellation[active.length];
        synchronized (this) {
            for (int i = 0; i < active.length; i++) if (active[i] != null) tokens[i] = active[i].token;
        }
        for (Cancellation token : tokens) if (token != null) token.cancel(new CallRejectedException(true));
        requests.close();
        // Slots include roots between registration, token publication and final SDK restoration.
        boolean interrupted = Thread.interrupted();
        while (activeCount() != 0) {
            LockSupport.parkNanos(1_000_000);
            interrupted |= Thread.interrupted();
        }

        if (interrupted) Thread.currentThread().interrupt();
    }

    List<TaskDiagnostics.Snapshot> diagnostics() {
        TaskScope[] scopes = new TaskScope[Math.min(8, active.length)];
        int count = 0;
        synchronized (this) {
            for (Slot slot : active)
                if (slot != null && slot.scope != null && count < scopes.length) scopes[count++] = slot.scope;
        }
        ArrayList<TaskDiagnostics.Snapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < count; i++) snapshots.add(TaskDiagnostics.snapshot(scopes[i], 32, 4));

        return List.copyOf(snapshots);
    }

    synchronized String metrics() {
        StringBuilder output = new StringBuilder();
        output.append("hoori_micro_operations_active ").append(activeCount()).append('\n');
        int overdue = 0;
        for (Slot slot : active) if (slot != null && System.nanoTime() - slot.invocation.deadlineNanos >= 0) overdue++;
        output.append("hoori_micro_operations_overdue ").append(overdue).append('\n');
        output.append("hoori_micro_operations_completed_total ")
                .append(completed)
                .append('\n');
        output.append("hoori_micro_operation_drain_nanos_total ")
                .append(drainNanos)
                .append('\n');
        for (Failures.Kind kind : Failures.Kind.values())
            output.append("hoori_micro_operation_failures_total{reason=\"")
                    .append(kind.label)
                    .append("\"} ")
                    .append(outcomes[kind.ordinal()])
                    .append('\n');

        return output.toString();
    }

    private static final class Slot {
        final Invocation invocation;
        final int index;
        TaskScope scope;
        Cancellation token;
        Admission.Permit permit;
        long bodyEnded;
        boolean configured;

        Slot(Invocation invocation, int index) {
            this.invocation = invocation;
            this.index = index;
        }
    }
}
