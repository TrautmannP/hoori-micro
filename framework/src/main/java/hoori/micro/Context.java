package hoori.micro;

import hoori.http.Request;
import java.io.IOException;
import java.util.Map;

/**
 * Explicit per-request call context: inbound correlation/local SDK deadline plus the broker.
 * Passed as a parameter, never stored in a ThreadLocal.
 */
public final class Context {
    private final ServiceBroker broker;
    private final Request request;

    Context(ServiceBroker broker, Request request) {
        this.broker = broker;
        this.request = request;
    }

    /** Inbound HTTP request, or null for startup/background work. Its ID is not an identity. */
    public Request request() {
        return request;
    }

    /** Typed call; the major version comes from the caller's dependsOn(). */
    public <I, O> O call(Action<I, O> action, I input) throws IOException {
        return broker.call(request, action, input);
    }

    /** Generic call, e.g. call("recipes.get", Map.of("id", 1)). Result is a JsonTree value. */
    public Object call(String action, Map<String, ?> params) throws IOException {
        return broker.call(request, action, params);
    }
}
