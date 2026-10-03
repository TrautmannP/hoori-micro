package hoori.micro;

import hoori.concurrent.Cancellation;
import hoori.http.RequestBudget;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.LockSupport;

/** One bounded application queue per direction; callbacks and I/O never hold its lock. */
final class Admission {
    // ponytail: one global limit and bounded waiter scan; split by dependency only when isolation is needed.
    private final Object lock = new Object();
    private final Thread[] waiters;
    private final int maxActive;
    private int active, pending;
    private long rejected, expired;
    private boolean stopping;
    private volatile boolean closed;

    Admission(int maxActive, int maxPending) {
        if (maxActive < 1 || maxPending < 0) throw new IllegalArgumentException("Admission limits");

        this.maxActive = maxActive;
        waiters = new Thread[maxPending];
    }

    Permit acquire(RequestBudget budget, boolean allowDuringDrain) throws IOException {
        if (budget == null) throw new NullPointerException("budget");

        Thread current = Thread.currentThread();
        Cancellation token = Cancellation.current();
        int slot = -1;
        // Register outside the admission lock. A cancel before park leaves an unpark permit.
        try (var registration = token == null ? null : token.onCancel(() -> LockSupport.unpark(current))) {
            for (; ; ) {
                Cancellation.checkpoint();
                // Admission and cancellation request share this linearization point. If a reason
                // already exists, checkpoint cannot deliver new callbacks; cleanup may be shielded.
                synchronized (token == null ? lock : token) {
                    if (token != null && token.isCancellationRequested()) Cancellation.checkpoint();

                    synchronized (lock) {
                        interrupted();

                        if (budget.isExpired()) {
                            if (expired < Long.MAX_VALUE) expired++;

                            throw new SocketTimeoutException("Call admission deadline expired");
                        }

                        if (closed || stopping && (slot >= 0 || !allowDuringDrain)) throw reject();

                        if (active < maxActive) {
                            Permit permit = new Permit(this, budget);

                            if (slot >= 0) {
                                remove(slot);
                                slot = -1;
                            }

                            active++;

                            return permit;
                        }

                        if (stopping) throw reject();

                        if (slot < 0) {
                            if (pending == waiters.length) throw reject();

                            slot = 0;
                            while (waiters[slot] != null) slot++;
                            waiters[slot] = current;
                            pending++;
                        }
                    }
                }
                // Unpark before park leaves a permit; registration/removal stays under the same monitor.
                LockSupport.parkNanos(this, budget.remainingNanos());
            }
        } finally {
            if (slot >= 0)
                synchronized (lock) {
                    remove(slot);
                }
        }
    }

    private void remove(int slot) {
        waiters[slot] = null;
        pending--;
    }

    private void wake() {
        if (pending > 0) for (Thread waiter : waiters) if (waiter != null) LockSupport.unpark(waiter);
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Call admission interrupted");
    }

    private CallRejectedException reject() {
        if (rejected < Long.MAX_VALUE) rejected++;

        return new CallRejectedException(stopping || closed);
    }

    void stop() {
        synchronized (lock) {
            stopping = true;
            wake();
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            stopping = true;
            wake();
        }
    }

    Stats stats() {
        synchronized (lock) {
            return new Stats(active, pending, rejected, expired);
        }
    }

    static final class Stats {
        final int active, pending;
        final long rejected, expired;

        Stats(int active, int pending, long rejected, long expired) {
            this.active = active;
            this.pending = pending;
            this.rejected = rejected;
            this.expired = expired;
        }
    }

    static final class Permit implements AutoCloseable {
        private final Admission owner;
        final RequestBudget budget;
        private volatile boolean released;
        private boolean expirationRecorded;

        private Permit(Admission owner, RequestBudget budget) {
            this.owner = owner;
            this.budget = budget;
        }

        void check(Admission expected) throws IOException {
            if (owner != expected || released) throw new IllegalStateException("Admission permit owner");

            Cancellation.checkpoint();
            interrupted();

            if (owner.closed) {
                synchronized (owner.lock) {
                    throw owner.reject();
                }
            }

            if (budget.isExpired()) {
                expired();
                throw new SocketTimeoutException("Call deadline expired");
            }
        }

        void expired() {
            synchronized (owner.lock) {
                if (!released && !expirationRecorded) {
                    if (owner.expired < Long.MAX_VALUE) owner.expired++;

                    expirationRecorded = true;
                }
            }
        }

        @Override
        public void close() {
            synchronized (owner.lock) {
                if (!released) {
                    released = true;
                    owner.active--;
                    owner.wake();
                }
            }
        }
    }
}
