package hoori.micro;

/** Immutable correlation only. It contains no request, credentials, scope or resource handles. */
public final class Invocation {
    public enum Origin {
        REQUEST,
        SERVICE
    }

    private final String requestId;
    private final Origin origin;
    final long deadlineNanos;

    Invocation(String requestId, Origin origin, long deadlineNanos) {
        this.requestId = requestId;
        this.origin = origin;
        this.deadlineNanos = deadlineNanos;
    }

    /** SDK-validated correlation value; never an authenticated identity. */
    public String requestId() {
        return requestId;
    }

    public Origin origin() {
        return origin;
    }
}
