package hoori.micro;

import hoori.concurrent.BulkFailedException;
import hoori.concurrent.Cancellation;
import hoori.concurrent.OperationFailedException;
import hoori.concurrent.ScopeExtension;
import hoori.concurrent.ScopeFailedException;
import hoori.concurrent.SubtaskFailedException;
import hoori.concurrent.TaskCancelledException;
import hoori.concurrent.TaskRejectedException;
import hoori.http.Response;
import hoori.rest.RequestException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/** Known SDK wrappers only; never rewrites causes/suppressed failures or recommends write retries. */
final class Failures {
    enum Kind {
        BUSINESS("business"),
        CAPACITY("capacity"),
        STOPPING("stopping"),
        CANCELLED("cancelled"),
        DEADLINE("deadline"),
        IO_TIMEOUT("io_timeout"),
        UPSTREAM("upstream"),
        INTERRUPTED("interrupted"),
        INTERNAL("internal");
        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    record Result(Kind kind, int status, String message, ScopeExtension.Status transaction) {
        Response response() {
            return Response.text(status, message);
        }
    }

    static Result classify(Throwable failure) {
        ScopeExtension.Status transaction = ScopeExtension.Status.NONE;
        Throwable cause = failure;
        // A depth bound also terminates cyclic cause graphs. Only semantic SDK wrappers are peeled.
        for (int depth = 0; depth < 32; depth++) {
            if (cause instanceof OperationFailedException operation) {
                if (transaction == ScopeExtension.Status.NONE) transaction = operation.status();
            } else if (!(cause instanceof ScopeFailedException
                    || cause instanceof SubtaskFailedException
                    || cause instanceof BulkFailedException)) break;

            if (cause.getCause() == null || cause.getCause() == cause) break;

            cause = cause.getCause();
        }

        if (cause instanceof RequestException business)
            return new Result(Kind.BUSINESS, business.status, business.getMessage(), transaction);

        if (cause instanceof CallRejectedException rejected)
            return new Result(
                    rejected.stopping() ? Kind.STOPPING : Kind.CAPACITY, 503, "Service unavailable", transaction);

        if (cause instanceof TaskRejectedException)
            return new Result(Kind.CAPACITY, 503, "Local task capacity unavailable", transaction);

        if (cause instanceof TaskCancelledException cancelled) {
            Cancellation.Reason reason = cancelled.reason();
            for (int depth = 0;
                    depth < 64 && reason.kind() == Cancellation.Kind.PARENT && reason.parent() != null;
                    depth++) reason = reason.parent();

            if (reason.cause() instanceof CallRejectedException rejected && rejected.stopping())
                return new Result(Kind.STOPPING, 503, "Service stopping", transaction);

            boolean deadline = reason.kind() == Cancellation.Kind.DEADLINE;

            return new Result(
                    deadline ? Kind.DEADLINE : Kind.CANCELLED,
                    deadline ? 504 : 503,
                    deadline ? "Request budget expired" : "Request cancelled",
                    transaction);
        }

        if (cause instanceof SocketTimeoutException)
            return new Result(Kind.IO_TIMEOUT, 504, "I/O budget expired", transaction);

        if (cause instanceof InterruptedIOException || cause instanceof InterruptedException)
            return new Result(Kind.INTERRUPTED, 503, "Request interrupted", transaction);

        if (cause instanceof ServiceCallException upstream)
            return new Result(
                    Kind.UPSTREAM,
                    upstream.upstreamStatus() == 503 || upstream.upstreamStatus() == 504
                            ? upstream.upstreamStatus()
                            : 502,
                    "Upstream service unavailable",
                    transaction);

        return new Result(Kind.INTERNAL, 500, "Internal Server Error", transaction);
    }
}
