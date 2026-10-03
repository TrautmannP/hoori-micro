package hoori.micro;

import hoori.concurrent.TaskRejectedException;
import hoori.concurrent.TaskScope;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Native-only integration checks of Micro slots with the pinned SDK/VM capacities. */
public final class CapacityChecks {
    private static volatile boolean released;
    private static final AtomicInteger ARRIVED = new AtomicInteger(), TIMERS = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        try {
            roots();
            timers();
            vm();
        } catch (Exception | Error failure) {
            System.err.println("capacity_failure=" + failure.getClass().getName() + " " + failure.getMessage());
            throw failure;
        }
    }

    private static Microservice app(int roots) {
        Map<String, String> values = Map.of(
                "HOORI_SERVER_CONNECTIONS",
                "128",
                "HOORI_INCOMING_CALLS",
                Integer.toString(roots),
                "HOORI_WORK_TIMEOUT_MS",
                "600000",
                "HOORI_INSTANCE_ID",
                "capacity");

        return Microservice.create(Service.named("capacity"), values::get);
    }

    private static void roots() throws Exception {
        released = false;
        ARRIVED.set(0);
        AtomicInteger bodies = new AtomicInteger(), closed = new AtomicInteger();
        Throwable[] failures = new Throwable[2];
        Thread[] owners = new Thread[2];
        try (Microservice app = app(2)) {
            for (int i = 0; i < owners.length; i++) {
                int index = i;
                owners[i] = new Thread(() -> {
                    try {
                        app.broker().executions.service(scope -> {
                            scope.own(() -> closed.incrementAndGet());
                            bodies.incrementAndGet();
                            hold();

                            return null;
                        });
                    } catch (Throwable failure) {
                        failures[index] = failure;
                    }
                });
                owners[i].start();
            }
            try {
                arrived(2);
                rejected(app, bodies);

                if (bodies.get() != 2
                        || closed.get() != 0
                        || app.broker().executions.activeCount() != 2)
                    throw new AssertionError("Root rejection disturbed admitted work");
            } finally {
                released = true;
                join(owners, failures);
            }

            if (closed.get() != 2) throw new AssertionError("Owned root resources not closed");

            recovered(app);
        }
        System.out.println("capacity_roots=2 rejected_body=0 drained_resources=2 recovery=true");
    }

    private static void timers() throws Exception {
        released = false;
        ARRIVED.set(0);
        TIMERS.set(0);
        AtomicInteger rejectedBody = new AtomicInteger();
        Throwable[] failures = new Throwable[64];
        Thread[] owners = new Thread[64];
        try (Microservice app = app(128)) {
            for (int i = 0; i < owners.length; i++) {
                int index = i;
                owners[i] = new Thread(() -> {
                    try {
                        app.runTask(() -> {
                            TIMERS.incrementAndGet();
                            try {
                                fillTimers(16);

                                return null;
                            } finally {
                                TIMERS.decrementAndGet();
                            }
                        });
                    } catch (Throwable failure) {
                        if (Failures.classify(failure).kind() != Failures.Kind.CAPACITY) failures[index] = failure;

                        ARRIVED.incrementAndGet();
                    }
                });
                owners[i].start();
            }
            try {
                arrived(64);

                if (TIMERS.get() != 1024) throw new AssertionError("Did not reach the actual SDK timer bound");

                int admitted = app.broker().executions.activeCount();
                rejected(app, rejectedBody);

                if (rejectedBody.get() != 0 || app.broker().executions.activeCount() != admitted)
                    throw new AssertionError("Timer rejection entered work or leaked a Micro slot");
            } finally {
                released = true;
                join(owners, failures);
            }

            if (TIMERS.get() != 0) throw new AssertionError("Timer owners retained work");

            recovered(app);
        }
        System.out.println("capacity_timers=1024 rejected_body=0 drained_timers=0 recovery=true");
    }

    private static void fillTimers(int remaining) throws Exception {
        if (remaining == 0) {
            hold();

            return;
        }

        try {
            TaskScope.named("timer-fill")
                    .within(Duration.ofSeconds(30L * (remaining + 1)))
                    .run(scope -> {
                        TIMERS.incrementAndGet();
                        try {
                            fillTimers(remaining - 1);
                        } finally {
                            TIMERS.decrementAndGet();
                        }
                    });
        } catch (TaskRejectedException full) {
            hold(); // Retain already admitted outer timers while the coordinator probes a new Micro root.
        }
    }

    private static void vm() throws Exception {
        released = false;
        AtomicInteger rejectedBody = new AtomicInteger();
        int admitted = 0;
        try (Microservice app = app(2)) {
            try (TaskScope fillers = TaskScope.open("vm-fill", 2048)) {
                try {
                    try {
                        while (true) {
                            fillers.fork(() -> {
                                while (!released) LockSupport.parkNanos(1_000_000);

                                return null;
                            });
                            admitted++;
                        }
                    } catch (TaskRejectedException full) {
                        if (admitted < 1000 || admitted > 1024) throw new AssertionError("Unexpected VM capacity");
                    }
                    rejected(app, rejectedBody); // Its new deadline waiter cannot start at VM capacity.

                    if (rejectedBody.get() != 0 || app.broker().executions.activeCount() != 0)
                        throw new AssertionError("VM rejection entered work or leaked a Micro slot");
                } finally {
                    released = true;
                }
            }
            recovered(app);
        }
        System.out.println("capacity_vm_children=" + admitted + " rejected_body=0 recovery=true");
    }

    private static void rejected(Microservice app, AtomicInteger bodies) throws Exception {
        try {
            app.runTask(() -> bodies.incrementAndGet());
            throw new AssertionError("Capacity unexpectedly admitted a Micro root");
        } catch (Exception failure) {
            if (Failures.classify(failure).kind() != Failures.Kind.CAPACITY) throw failure;
        }
    }

    private static void recovered(Microservice app) throws Exception {
        if (app.broker().executions.activeCount() != 0
                || !app.taskDiagnostics().isEmpty()
                || app.runTask(() -> 42) != 42) throw new AssertionError("Micro recovery failed");
    }

    private static void hold() {
        ARRIVED.incrementAndGet();
        while (!released) LockSupport.parkNanos(1_000_000);
    }

    private static void arrived(int count) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (ARRIVED.get() != count) {
            if (System.nanoTime() >= end) throw new AssertionError("Capacity owners did not reach their gates");

            Thread.sleep(1);
        }
    }

    private static void join(Thread[] owners, Throwable[] failures) throws Exception {
        for (int i = 0; i < owners.length; i++) {
            owners[i].join(10_000);

            if (owners[i].isAlive() || failures[i] != null)
                throw new AssertionError("Capacity owner did not drain", failures[i]);
        }
    }
}
