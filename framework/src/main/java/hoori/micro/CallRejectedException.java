package hoori.micro;

import java.io.IOException;

/** Local capacity or lifecycle rejection, safely mapped to HTTP 503. Never replay a write. */
public final class CallRejectedException extends IOException {
    private static final long serialVersionUID = 1L;
    private final boolean stopping;

    CallRejectedException() {
        this(false);
    }

    CallRejectedException(boolean stopping) {
        super(stopping ? "Service stopping" : "Local call capacity unavailable");
        this.stopping = stopping;
    }

    public boolean stopping() {
        return stopping;
    }
}
