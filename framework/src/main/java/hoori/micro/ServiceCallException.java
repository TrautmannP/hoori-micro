package hoori.micro;

import hoori.validation.Violation;
import java.util.List;

/** Never contains upstream response bodies, credentials or raw URLs. Status 0 means no response. */
public final class ServiceCallException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String service;
    private final int upstreamStatus;
    private final HttpEndpoint endpoint;
    private final String code;
    private final List<Violation> violations;

    ServiceCallException(String service, int upstreamStatus, String reason, Throwable cause) {
        this(service, upstreamStatus, reason, cause, null, List.of(), null);
    }

    ServiceCallException(
            String service,
            int upstreamStatus,
            String reason,
            Throwable cause,
            HttpEndpoint endpoint,
            List<Violation> violations,
            String code) {
        super("Service " + service + ": " + reason, cause);
        this.service = service;
        this.upstreamStatus = upstreamStatus;
        this.endpoint = endpoint;
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public String service() {
        return service;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    /** Direct HTTP contract, or null when no error response was received. */
    public HttpEndpoint endpoint() {
        return endpoint;
    }

    public String code() {
        return code;
    }

    /** Bounded field paths and codes only; never a peer message or rejected value. */
    public List<Violation> violations() {
        return violations;
    }
}
