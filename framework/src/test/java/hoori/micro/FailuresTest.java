package hoori.micro;

import static org.junit.jupiter.api.Assertions.*;

import hoori.concurrent.OperationFailedException;
import hoori.concurrent.ScopeExtension;
import hoori.concurrent.TaskScope;
import hoori.rest.RequestException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

final class FailuresTest {
    @Test
    void publicPolicyPreservesCoordinatorStatusAndOriginalFailures() {
        assertEquals(
                422,
                Failures.classify(new RequestException(422, "Invalid quantity")).status());
        assertEquals(
                500,
                Failures.classify(new IllegalStateException("private detail")).status());
        assertEquals(
                Failures.Kind.STOPPING,
                Failures.classify(new CallRejectedException(true)).kind());
        assertEquals(
                Failures.Kind.IO_TIMEOUT,
                Failures.classify(new SocketTimeoutException("secret")).kind());
        assertEquals(
                502,
                Failures.classify(new ServiceCallException("recipes", 500, "private detail", null))
                        .status());
        assertEquals(
                503,
                Failures.classify(new ServiceCallException("recipes", 503, "private detail", null))
                        .status());
        for (ScopeExtension.Status expected : new ScopeExtension.Status[] {
            ScopeExtension.Status.COMMITTED, ScopeExtension.Status.UNKNOWN, ScopeExtension.Status.ROLLED_BACK
        }) {
            IOException original = new IOException("MUST-NOT-BE-PUBLIC");
            ScopeExtension.Coordinator coordinator = scope -> new ScopeExtension.Transaction() {
                ScopeExtension.Status status = ScopeExtension.Status.ACTIVE;

                public void commit() throws Exception {
                    status = expected;

                    if (expected == ScopeExtension.Status.UNKNOWN) throw original;
                }

                public void rollback() {
                    status = ScopeExtension.Status.ROLLED_BACK;
                }

                public ScopeExtension.Status status() {
                    return status;
                }
            };
            OperationFailedException failure = assertThrows(
                    OperationFailedException.class,
                    () -> TaskScope.named("classification").with(coordinator).run(scope -> {
                        if (expected == ScopeExtension.Status.ROLLED_BACK) throw original;

                        if (expected == ScopeExtension.Status.COMMITTED)
                            scope.own((AutoCloseable) () -> {
                                throw original;
                            });
                    }));
            int suppressed = original.getSuppressed().length;
            Failures.Result classified = Failures.classify(failure);
            assertEquals(expected, classified.transaction());
            assertEquals(500, classified.status());
            assertFalse(classified.message().contains("MUST-NOT"));
            assertSame(original, failure.getCause());
            assertEquals(suppressed, original.getSuppressed().length);
        }
    }
}
