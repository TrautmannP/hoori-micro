package hoori.micro;

import java.io.IOException;

/** Local capacity or lifecycle rejection, safely mapped to HTTP 503. Never replay a write. */
public final class CallRejectedException extends IOException {
    private static final long serialVersionUID = 1L;

    CallRejectedException() {
        super("Local call capacity unavailable");
    }
}
