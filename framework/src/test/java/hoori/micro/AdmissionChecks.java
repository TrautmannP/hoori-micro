package hoori.micro;

import hoori.http.RequestBudget;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/** Runnable on HotSpot and the real guest: queue ownership, wakeups, deadlines and drain. */
public final class AdmissionChecks {
    public static void main(String[] args) throws Exception {
        Admission failFast = new Admission(1, 0);
        try (Admission.Permit held = failFast.acquire(RequestBudget.afterMillis(5000), false)) {
            try {
                failFast.acquire(RequestBudget.afterMillis(5000), false);
                throw new AssertionError("Overload admitted");
            } catch (CallRejectedException expected) {
                require(failFast.stats().active == 1 && failFast.stats().pending == 0, "Fail-fast bounds");
            }
        }
        require(failFast.stats().active == 0 && failFast.stats().rejected == 1, "Fail-fast release");

        Admission queue = new Admission(1, 1);
        try (Admission.Permit held = queue.acquire(RequestBudget.afterMillis(5000), false)) {
            try {
                queue.acquire(RequestBudget.afterMillis(50), false);
                throw new AssertionError("Expired waiter admitted");
            } catch (SocketTimeoutException expected) {
                require(queue.stats().pending == 0 && queue.stats().expired == 1, "Deadline waiter cleanup");
            }
            Throwable[] error = new Throwable[1];
            Thread cancelled = waiter(queue, error);
            waitPending(queue);
            cancelled.interrupt();
            cancelled.join(5000);
            require(!cancelled.isAlive() && error[0] instanceof InterruptedIOException, "Cancellation cleanup");
            require(queue.stats().active == 1 && queue.stats().pending == 0, "Cancelled waiter released");

            error[0] = null;
            Thread stopped = waiter(queue, error);
            waitPending(queue);
            queue.stop();
            stopped.join(5000);
            require(!stopped.isAlive() && error[0] instanceof CallRejectedException, "Stop rejects waiting work");
            held.check(queue); // Previously admitted work stays valid during graceful stop.
        }
        try (Admission.Permit drain = queue.acquire(RequestBudget.afterMillis(5000), true)) {
            drain.check(queue); // A dispatched handler may continue immediately during drain.
        }
        queue.close();
        try {
            queue.acquire(RequestBudget.afterMillis(5000), true);
            throw new AssertionError("Closed admission restarted");
        } catch (CallRejectedException expected) {
            require(queue.stats().active == 0 && queue.stats().pending == 0, "Closed queue cleanup");
        }

        Admission closing = new Admission(1, 1);
        try (Admission.Permit held = closing.acquire(RequestBudget.afterMillis(5000), false)) {
            Throwable[] error = new Throwable[1];
            Thread waiter = waiter(closing, error);
            waitPending(closing);
            closing.close();
            waiter.join(5000);
            require(!waiter.isAlive() && error[0] instanceof CallRejectedException, "Close wakes waiters");
            try {
                held.check(closing);
                throw new AssertionError("Closed owner accepted an active permit");
            } catch (CallRejectedException expected) {
                // Active data must observe immediate close before another codec/exchange.
            }
        }
        require(closing.stats().active == 0 && closing.stats().pending == 0, "Close permits released");
        System.out.println("Admission checks passed: bounds, deadline, interrupt, stop, drain and close");
    }

    private static Thread waiter(Admission admission, Throwable[] error) {
        Thread thread = new Thread(() -> {
            try (Admission.Permit unexpected = admission.acquire(RequestBudget.afterMillis(5000), true)) {
                throw new AssertionError("Waiting work entered after cancellation/stop/close");
            } catch (IOException expected) {
                if (expected instanceof InterruptedIOException
                        && !Thread.currentThread().isInterrupted()) {
                    error[0] = new AssertionError("Cancellation lost interrupt status");
                } else error[0] = expected;
            }
        });
        thread.start();

        return thread;
    }

    private static void waitPending(Admission admission) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (admission.stats().pending == 0 && System.nanoTime() - deadline < 0) Thread.sleep(1);
        require(admission.stats().pending == 1, "Waiter not registered");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
