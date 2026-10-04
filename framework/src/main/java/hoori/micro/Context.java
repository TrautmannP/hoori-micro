package hoori.micro;

import hoori.concurrent.Budget;
import hoori.concurrent.Cancellation;
import hoori.http.Headers;
import hoori.http.Request;
import hoori.http.RequestBudget;
import hoori.rest.RequestException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

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

    /** Only known provider endpoints accept a relative wire budget. Gateways ignore it. */
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
