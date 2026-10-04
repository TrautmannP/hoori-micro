package hoori.micro;

import hoori.http.RequestBudget;
import hoori.runtime.RuntimeMetrics;

/** Native attribution probes. HTTP throughput and latency are measured separately by benchmark.py. */
public final class FrameworkCosts {
    private static final int CALLS = 10_000;

    @FunctionalInterface
    private interface Step {
        int run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        try (Microservice app =
                Microservice.create("costs", 1, key -> key.equals("HOORI_WORK_TIMEOUT_MS") ? "600000" : null)) {
            app.runTask(() -> {
                Context context = app.context();
                measure("context", () -> context.effectiveBudget().isExpired() ? 0 : 1);
                Admission admission = new Admission(1, 1);
                RequestBudget budget = RequestBudget.afterMillis(600000);
                measure("admission", () -> {
                    try (var permit = admission.acquire(budget, false)) {
                        return 1;
                    }
                });

                if (admission.stats().active != 0 || admission.stats().pending != 0)
                    throw new AssertionError("Admission retained work");

                measure(
                        "client-request",
                        () -> new ClientRequest("/bench").target().length());

                return null;
            });

            if (app.broker().executions.activeCount() != 0) throw new AssertionError("Root retained work");
        }
    }

    private static void measure(String name, Step step) throws Exception {
        for (int round = 0; round < 6; round++) {
            RuntimeMetrics.Snapshot before = RuntimeMetrics.snapshot();
            long started = System.nanoTime();
            long checksum = 0;
            for (int i = 0; i < CALLS; i++) checksum += step.run();
            long elapsed = System.nanoTime() - started;
            RuntimeMetrics.Snapshot after = RuntimeMetrics.snapshot();

            if (checksum != (name.equals("client-request") ? 6L : 1L) * CALLS)
                throw new AssertionError("Incorrect measured result");

            if (round != 0)
                System.out.println("{\"probe\":\"" + name + "\",\"round\":" + round + ",\"calls\":" + CALLS
                        + ",\"elapsed_ns\":" + elapsed + ",\"allocated_bytes\":"
                        + (after.allocatedBytes - before.allocatedBytes) + ",\"object_allocations\":"
                        + (after.objectAllocations - before.objectAllocations) + ",\"array_allocations\":"
                        + (after.arrayAllocations - before.arrayAllocations) + "}");
        }
    }
}
