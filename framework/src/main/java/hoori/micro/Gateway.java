package hoori.micro;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.rest.Handler;
import hoori.rest.Middleware;
import hoori.rest.Request;
import hoori.rest.Router;
import hoori.rest.json.JsonException;
import hoori.rest.mvc.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Publishes only explicit controller endpoints from one prepared, bounded catalog snapshot. */
public final class Gateway implements Middleware {
    static final Route[] EMPTY_ROUTES = new Route[0];
    static final Router EMPTY_ROUTER = new Router().freeze();
    static final int MAX_ROUTES = 256, MAX_ROUTE_BYTES = 65536;
    // Router selects the exact provider grammar. This marker never reaches the wire.
    private static final Response MATCH = new Response(200, new Headers(), new byte[0]);
    private final ServiceBroker broker;
    private final Microservice app;
    private final BiPredicate<Request, String> policy;

    private Gateway(Microservice app, BiPredicate<Request, String> policy) {
        if (policy == null) throw new NullPointerException("policy");

        this.app = app;
        this.broker = app.broker();
        this.policy = policy;
        broker.followPublicCatalog();
    }

    public static void mount(Microservice app, BiPredicate<Request, String> policy) {
        app.routes().use(new Gateway(app, policy));
    }

    /** Demo permissions are granted to every caller; this is not authentication. */
    public static void main(String[] args) throws Exception {
        String configured = Environment.system().get("HOORI_GATEWAY_PERMISSIONS");
        List<String> granted = new ArrayList<>();

        if (configured != null && !configured.isEmpty()) {
            for (String value : configured.split(",")) {
                int colon = value.indexOf(':');
                permission(colon < 0 ? "" : value.substring(0, colon), value);
                granted.add(value);
            }
        }

        try (Microservice app = Microservice.create("gateway")) {
            mount(app, (request, permission) -> granted.contains(permission));
            app.run();
        }
    }

    @Override
    public Response handle(Request request, Handler next) throws Exception {
        if (!request.routeTemplate().equals("<unmatched>")) return next.handle(request);

        Context context = app.context();
        Invocation invocation = context.invocation();
        try (Admission.Permit permit = broker.admit(invocation, context.effectiveBudget())) {
            ServiceBroker.View snapshot = broker.snapshot();
            Response match = snapshot.routing.handle(request.raw());

            if (match != MATCH) return match;

            Route selected = null;
            for (Route route : snapshot.routes)
                if (route.contract.method().equals(request.method()) && route.path.equals(request.routeTemplate())) {
                    selected = route;
                    break;
                }

            if (selected == null) throw new IllegalStateException("Missing prepared route");

            if (!policy.test(request, selected.permission)) return new Problem(403, "forbidden").response();

            Headers headers = new Headers();
            for (int i = 0; i < request.raw().headers.size(); i++) {
                String name = request.raw().headers.name(i);

                if (name.equalsIgnoreCase("Accept") || name.equalsIgnoreCase("Content-Type"))
                    headers.add(name, request.raw().headers.value(i));
            }
            Response response = broker.invoke(
                    permit,
                    invocation,
                    selected.service,
                    selected.version,
                    selected.contract,
                    request.raw().target,
                    headers,
                    request.raw().body,
                    snapshot);

            if (response.status < 200 || response.status >= 300) {
                ServiceCallException failure = broker.failure(selected.service, selected.contract, response);

                if (!failure.violations().isEmpty()) return ValidationErrors.response(failure.violations());

                if (response.status >= 400 && response.status < 500 && response.status != 421) {
                    Response safe = new Problem(
                                    response.status, failure.code() == null ? "request_rejected" : failure.code())
                            .response();
                    String allow = response.headers.get("Allow");

                    if (response.status == 405 && HttpResponses.safeAllow(allow)) safe.headers.add("Allow", allow);

                    return safe;
                }

                throw failure;
            }

            if (response.status == 204 && response.body.length != 0
                    || response.body.length > 0 && !ServiceBroker.jsonContentType(response.headers))
                throw new ServiceCallException(selected.service, response.status, "invalid response media", null);

            try {
                return new Response(response.status, HttpResponses.headers(response.headers), response.body);
            } catch (IllegalArgumentException invalid) {
                throw new ServiceCallException(selected.service, response.status, "invalid response metadata", null);
            }
        }
    }

    static Router routing(Route[] routes) {
        if (routes.length == 0) return EMPTY_ROUTER;

        Router router = new Router();
        for (Route route : routes) router.route(route.contract.method(), route.path, ignored -> MATCH);

        return router.freeze();
    }

    static Route[] build(Catalog catalog) {
        ArrayList<Route> distinct = new ArrayList<>();
        int bytes = 0, work = 0;
        for (Catalog.Instance instance : catalog.instances)
            for (Catalog.Entry entry : instance.endpoints)
                if (entry.permission != null) {
                    Route route = new Route(instance, entry);
                    boolean known = false;
                    for (Route previous : distinct) {
                        if ((++work & 63) == 0) Thread.yield();

                        if (route.sameDefinition(previous)) {
                            known = true;
                            break;
                        }
                    }

                    if (known) continue;

                    bytes += route.service.length() + route.contract.key().length() + route.permission.length();

                    if (distinct.size() == MAX_ROUTES || bytes > MAX_ROUTE_BYTES)
                        throw new JsonException("Gateway route snapshot limit");

                    distinct.add(route);
                }
        // Parse each template once for the normal, conflict-free catalog.
        Route[] all = distinct.toArray(new Route[0]);
        try {
            routing(all);

            return all;
        } catch (IllegalArgumentException conflict) {
            // Identify both sides below so unrelated routes remain available.
        }
        // ponytail: bounded O(n²) fallback for conflicting catalogs; the SDK owns the grammar.
        ArrayList<Route> accepted = new ArrayList<>();
        for (Route route : distinct) {
            boolean clash = false;
            for (Route other : distinct) {
                if ((++work & 63) == 0) Thread.yield();

                if (other != route
                        && (route.sameTarget(other)
                                || conflicts(route.contract.method(), route.path, other.contract.method(), other.path)))
                    clash = true;
            }

            if (clash) System.err.println("gateway_route_withheld service=" + route.service);
            else accepted.add(route);
        }

        return accepted.toArray(new Route[0]);
    }

    static boolean conflicts(String methodA, String pathA, String methodB, String pathB) {
        try {
            new Router()
                    .route(methodA, pathA, ignored -> MATCH)
                    .route(methodB, pathB, ignored -> MATCH)
                    .freeze();

            return false;
        } catch (IllegalArgumentException conflict) {
            return true;
        }
    }

    static void permission(String service, String permission) {
        if (permission == null || !permission.startsWith(service + ":") || service.isEmpty())
            throw new IllegalArgumentException("Permission must be <service>:<scope>");

        ServiceName.require(permission.substring(service.length() + 1));
    }

    static final class Route {
        final String service, path, permission;
        final HttpEndpoint contract;
        final int version;

        Route(Catalog.Instance instance, Catalog.Entry entry) {
            service = instance.service;
            version = instance.version;
            contract = entry.contract;
            path = contract.path();
            permission = entry.permission;
        }

        boolean sameTarget(Route other) {
            return service.equals(other.service)
                    && version == other.version
                    && contract.method().equals(other.contract.method())
                    && path.equals(other.path);
        }

        boolean sameDefinition(Route other) {
            return sameTarget(other) && contract.equals(other.contract) && permission.equals(other.permission);
        }
    }
}
