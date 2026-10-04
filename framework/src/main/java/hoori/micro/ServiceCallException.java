package hoori.micro;

import hoori.validation.Violation;
import java.io.IOException;
import java.util.List;

/** Never contains upstream response bodies, credentials or raw URLs. Status 0 means no response. */
public final class ServiceCallException extends IOException {
    private static final long serialVersionUID = 1L;
    private final String service;
    private final int upstreamStatus;
    private final String action;
    private final List<Violation> violations;

    ServiceCallException(String service, int upstreamStatus, String reason, Throwable cause) {
        this(service, upstreamStatus, reason, cause, null, List.of());
    }

    ServiceCallException(
            String service,
            int upstreamStatus,
            String reason,
            Throwable cause,
            String action,
            List<Violation> violations) {
        super("Service " + service + ": " + reason, cause);
        this.service = service;
        this.upstreamStatus = upstreamStatus;
        this.action = action;
        this.violations = List.copyOf(violations);
    }

    public String service() {
        return service;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    /** Qualified direct target, or null when no error response was received. */
    public String action() {
        return action;
    }

    /** Bounded field paths and codes only; never a peer message or rejected value. */
    public List<Violation> violations() {
        return violations;
    }
}
