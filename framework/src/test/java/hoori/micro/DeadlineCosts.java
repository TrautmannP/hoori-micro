package hoori.micro;

import hoori.concurrent.Budget;
import hoori.concurrent.TaskScope;
import hoori.runtime.RuntimeMetrics;

/** SDK-only reproducer: isolated cancellable roots with and without a finite deadline. */
public final class DeadlineCosts {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("timed") || args[0].equals("unlimited")))
            throw new IllegalArgumentException("timed or unlimited");

        boolean timed = args[0].equals("timed");
        Budget budget = Budget.until(System.nanoTime() + 600_000_000_000L);
        for (int round = 0; round < 6; round++) {
            RuntimeMetrics.Snapshot before = RuntimeMetrics.snapshot();
            long started = System.nanoTime();
            int checksum = 0;
            for (int i = 0; i < 1000; i++) {
                var operation = TaskScope.named("deadline-cost").cancellable();

                if (timed) operation.withBudget(budget);

                checksum += operation.call(scope -> 1);
            }
            long elapsed = System.nanoTime() - started;
            RuntimeMetrics.Snapshot after = RuntimeMetrics.snapshot();

            if (checksum != 1000) throw new AssertionError("Incomplete operations");

            if (round != 0)
                System.out.println("{\"timed\":" + timed + ",\"round\":" + round
                        + ",\"calls\":1000,\"elapsed_ns\":" + elapsed + ",\"allocated_bytes\":"
                        + (after.allocatedBytes - before.allocatedBytes) + "}");
        }
    }
}
