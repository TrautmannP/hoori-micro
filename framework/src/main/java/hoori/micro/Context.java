package hoori.micro;

import hoori.http.Headers;
import hoori.http.Request;
import hoori.http.RequestBudget;
import hoori.rest.RequestException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * Explicit per-request call context: correlation, one inherited monotonic budget and the broker.
 * Passed as a parameter, never stored in a ThreadLocal.
 */
public final class Context {
    static final String BUDGET_HEADER = "X-Hoori-Budget-Ms";
    static final int MAX_BUDGET_MILLIS = 600000;
    private final ServiceBroker broker;
    private final Request request;
    private final RequestBudget budget;

    Context(ServiceBroker broker, Request request) {
        this(broker, request, broker.defaultBudget(request));
    }

    Context(ServiceBroker broker, Request request, RequestBudget budget) {
        this.broker = broker;
        this.request = request;
        this.budget = budget;
    }

    /** Inbound HTTP request, or null for startup/background work. Its ID is not an identity. */
    public Request request() {
        return request;
    }

    public RequestBudget budget() {
        return budget;
    }

    /** A shorter child/root context; the parent deadline is never extended. */
    public Context limitedToMillis(int millis) {
        if (millis < 0 || millis > MAX_BUDGET_MILLIS) throw new IllegalArgumentException("Budget 0-600000 ms");

        return new Context(broker, request, budget.limitedToMillis(millis));
    }

    /** Typed call; the major version comes from the caller's dependsOn(). */
    public <I, O> O call(Action<I, O> action, I input) throws IOException {
        return broker.call(request, budget, action, input);
    }

    /** Generic call, e.g. call("recipes.get", Map.of("id", 1)). Result is a JsonTree value. */
    public Object call(String action, Map<String, ?> params) throws IOException {
        return broker.call(request, budget, action, params);
    }

    void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Request cancelled");

        if (budget.isExpired()) throw new SocketTimeoutException("Request budget expired");
    }

    /** Only the private invoke boundary accepts a relative wire budget. Gateways ignore it. */
    static RequestBudget incomingBudget(Headers headers, RequestBudget local) {
        String value = null;
        for (int i = 0; i < headers.size(); i++)
            if (headers.name(i).equalsIgnoreCase(BUDGET_HEADER)) {
                if (value != null) throw new RequestException(400, "Invalid request budget");

                value = headers.value(i);
            }

        if (value == null) return local;

        if (value.isEmpty() || value.length() > 6 || value.length() > 1 && value.charAt(0) == '0')
            throw new RequestException(400, "Invalid request budget");

        int millis = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c < '0' || c > '9') throw new RequestException(400, "Invalid request budget");

            millis = millis * 10 + c - '0';
        }

        if (millis > MAX_BUDGET_MILLIS) throw new RequestException(400, "Invalid request budget");

        return local.limitedToMillis(millis);
    }
}
