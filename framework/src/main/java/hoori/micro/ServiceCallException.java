package hoori.micro;

import java.io.IOException;

/** Never contains upstream response bodies, credentials or raw URLs. Status 0 means no response. */
public final class ServiceCallException extends IOException {
    private static final long serialVersionUID = 1L;
    private final String service;
    private final int upstreamStatus;

    ServiceCallException(String service, int upstreamStatus, String reason, Throwable cause) {
        super("Service " + service + ": " + reason, cause);
        this.service = service;
        this.upstreamStatus = upstreamStatus;
    }

    public String service() { return service; }
    public int upstreamStatus() { return upstreamStatus; }
}
