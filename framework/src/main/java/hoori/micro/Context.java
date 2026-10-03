package hoori.micro;

import hoori.concurrent.Budget;
import hoori.concurrent.Cancellation;
import hoori.concurrent.TaskSpec;
import hoori.http.Headers;
import hoori.http.Request;
import hoori.http.RequestBudget;
import hoori.rest.RequestException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * Service-owned broker facade. Calls read the current managed execution; the facade retains no request.
 */
public final class Context {
    static final String BUDGET_HEADER = "X-Hoori-Budget-Ms";
    static final int MAX_BUDGET_MILLIS = 600000;
    private final ServiceBroker broker;

    Context(ServiceBroker broker) {
        this.broker = broker;
    }

    public Invocation invocation() {
        return broker.executions.current();
    }

    /** Owner-local HTTP data. Structured children and service work cannot access it. Never capture it in tasks. */
    public Request ownerRequest() {
        return broker.executions.ownerRequest();
    }

    public Budget budget() {
        invocation();

        return Budget.current();
    }

    /** Typed call; the major version comes from the caller's dependsOn(). */
    public <I, O> O call(Action<I, O> action, I input) throws IOException {
        Invocation invocation = invocation();

        return broker.call(invocation, effectiveBudget(), action, input);
    }

    /** Generic call, e.g. call("recipes.get", Map.of("id", 1)). Result is a JsonTree value. */
    public Object call(String action, Map<String, ?> params) throws IOException {
        Invocation invocation = invocation();

        return broker.call(invocation, effectiveBudget(), action, params);
    }

    /** Deferred, reusable work. Inputs are retained by reference, not copied; no context is captured. */
    public <I, O> TaskSpec<O> task(Action<I, O> action, I input) {
        if (action == null || input == null) throw new NullPointerException();

        return () -> call(action, input);
    }

    public TaskSpec<Object> task(String action, Map<String, ?> params) {
        ServiceName.qualified(action);

        if (params == null) throw new NullPointerException("params");

        return () -> call(action, params);
    }

    RequestBudget effectiveBudget() throws IOException {
        check();
        RequestBudget request = RequestBudget.until(invocation().deadlineNanos);
        Budget work = budget();

        if (work.isFinite()) request = request.limitedTo(RequestBudget.until(work.deadlineNanos()));

        return request.limitedToMillis(broker.callTimeoutMillis());
    }

    void check() throws IOException {
        invocation();
        Cancellation.checkpoint();

        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Request cancelled");

        if (RequestBudget.until(invocation().deadlineNanos).isExpired())
            throw new SocketTimeoutException("Request budget expired");
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
