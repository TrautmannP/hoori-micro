package hoori.micro;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.rest.Handler;
import hoori.rest.Middleware;
import hoori.rest.Request;
import hoori.rest.RequestException;
import hoori.rest.json.Json;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Publishes catalog actions that their provider declared with http() and requirePermission(), and
 * only while the policy grants that permission. Rebuilt from each new catalog snapshot; no route
 * list is maintained here. Conflicting routes from different actions are all withheld.
 */
public final class Gateway implements Middleware {
    static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final Route[] EMPTY_ROUTES = new Route[0];
    static final int MAX_ROUTES = 256, MAX_ROUTE_BYTES = 65536;

    private final ServiceBroker broker;
    private final Microservice app;
    private final BiPredicate<Request, String> policy;
    private final JsonLimits limits;

    private Gateway(Microservice app, BiPredicate<Request, String> policy) {
        if (policy == null) throw new NullPointerException("policy");

        broker = app.broker();
        this.app = app;
        this.policy = policy;
        limits = app.jsonLimits();
        broker.followPublicCatalog();
    }

    /** policy(request, permission) decides access; a service dependency is never a permission. */
    public static void mount(Microservice app, BiPredicate<Request, String> policy) {
        app.routes().use(new Gateway(app, policy));
    }

    /**
     * Development gateway: grants exactly the permissions in HOORI_GATEWAY_PERMISSIONS (comma
     * separated) to every caller. No authentication; not a production authorization policy.
     */
    public static void main(String[] args) throws Exception {
        String configured = Environment.system().get("HOORI_GATEWAY_PERMISSIONS");
        List<String> granted = new ArrayList<>();

        if (configured != null && !configured.isEmpty()) {
            int start = 0;
            while (start <= configured.length()) {
                int end = configured.indexOf(',', start);

                if (end < 0) end = configured.length();

                String permission = configured.substring(start, end);
                int colon = permission.indexOf(':');
                permission(colon < 0 ? "" : permission.substring(0, colon), permission);
                granted.add(permission);
                start = end + 1;
            }
        }

        try (Microservice app = Microservice.create(Service.named("gateway"))) {
            mount(app, (request, permission) -> granted.contains(permission));
            app.run();
        }
    }

    @Override
    public Response handle(Request request, Handler next) throws Exception {
        // Static routes (health, metrics, local controllers) always win over published actions.
        if (!request.routeTemplate().equals("<unmatched>")) return next.handle(request);

        // One outgoing permit covers route parameters, input encoding and the direct RPC.
        Context context = app.context();
        Invocation invocation = context.invocation();
        try (Admission.Permit permit = broker.admit(invocation, context.effectiveBudget())) {
            ServiceBroker.View snapshot = broker.snapshot();

            String target = request.raw().target;
            int query = target.indexOf('?');
            String[] segments = segments(query < 0 ? target : target.substring(0, query));
            Route route = null;
            for (Route candidate : snapshot.routes)
                if (candidate.method.equals(request.method())
                        && candidate.matches(segments)
                        && (route == null || candidate.specificity > route.specificity)) route = candidate;

            if (route == null) return next.handle(request);

            if (!policy.test(request, route.permission)) return Response.text(403, "Forbidden");

            Map<String, Object> input = params(request, route, segments);
            context.check();
            byte[] params = Json.encode(input, JsonTree.CODEC, limits);
            byte[] result;
            try {
                result =
                        broker.invoke(permit, invocation, route.service, route.version, route.action, params, snapshot);
            } catch (ServiceCallException rejected) {
                int status = rejected.upstreamStatus();

                if (!rejected.violations().isEmpty()) return ValidationErrors.response(rejected.violations());

                // Other peer bodies stay private. Nested call failures are mapped by the provider to 502.
                if (status >= 400 && status < 500 && status != 421) return Response.text(status, "Request rejected");

                throw rejected;
            }

            return new Response(200, new Headers().add("Content-Type", "application/json; charset=utf-8"), result);
        }
    }

    /** Path parameters as JSON strings, merged into an optional JSON object body. */
    private Map<String, Object> params(Request request, Route route, String[] segments) {
        Map<String, Object> params = new LinkedHashMap<>();

        if (request.raw().body.length > 0) {
            if (!(request.body(JsonTree.CODEC, limits) instanceof Map<?, ?> body))
                throw new RequestException(400, "Expected a JSON object");

            for (Map.Entry<?, ?> entry : body.entrySet()) params.put((String) entry.getKey(), entry.getValue());
        }

        for (int i = 0; i < segments.length; i++)
            if (route.segments[i] == null) {
                String name = route.names[i];

                if (params.containsKey(name)) throw new RequestException(400, "Ambiguous parameter");

                params.put(name, segments[i]);
            }

        return params;
    }

    static Route[] build(Catalog catalog) {
        ArrayList<Route> distinct = new ArrayList<>();
        int bytes = 0, work = 0;
        for (Catalog.Instance instance : catalog.instances)
            for (Catalog.Entry entry : instance.actions)
                if (entry.path != null) {
                    Route route = new Route(instance, entry);
                    boolean known = false;
                    for (Route other : distinct) {
                        if ((++work & 63) == 0) Thread.yield();

                        if (other.sameAction(route)) {
                            known = true;
                            break;
                        }
                    }

                    if (!known) {
                        bytes += route.service.length()
                                + route.action.length()
                                + route.method.length()
                                + route.path.length()
                                + route.permission.length();

                        if (distinct.size() == MAX_ROUTES || bytes > MAX_ROUTE_BYTES)
                            throw new JsonException("Gateway route snapshot limit");

                        distinct.add(route);
                    }
                }
        // ponytail: O(n²) over at most 256 distinct routes; index only after a measured update bottleneck.
        ArrayList<Route> accepted = new ArrayList<>();
        for (Route route : distinct) {
            boolean clash = false;
            for (Route other : distinct) {
                if ((++work & 63) == 0) Thread.yield();

                if (other != route
                        && (route.sameActionName(other)
                                || overlap(route.method, route.segments, other.method, other.segments))) clash = true;
            }

            if (clash)
                System.err.println("gateway_route_withheld service=" + route.service + " action=" + route.action);
            else accepted.add(route);
        }

        return accepted.toArray(new Route[0]);
    }

    /** Route template → segments; null marks a {parameter}. */
    static String[] template(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("/_hoori") || path.length() > 256)
            throw new IllegalArgumentException("Expected /path template outside /_hoori");

        String[] segments = split(path);
        String[] names = names(segments);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            if (names[i] != null) {
                for (int j = 0; j < i; j++)
                    if (names[i].equals(names[j])) throw new IllegalArgumentException("Duplicate path parameter");
                segments[i] = null;
                continue;
            }

            if (segment.isEmpty()) throw new IllegalArgumentException("Empty path segment");

            for (int j = 0; j < segment.length(); j++) {
                char c = segment.charAt(j);

                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~".indexOf(c) >= 0))
                    throw new IllegalArgumentException("Unsupported path literal");
            }
        }

        return segments;
    }

    /** Same method and templates that can match the same path with equal specificity. */
    static boolean conflicts(String methodA, String pathA, String methodB, String pathB) {
        return overlap(methodA, template(pathA), methodB, template(pathB));
    }

    private static boolean overlap(String methodA, String[] a, String methodB, String[] b) {
        if (!methodA.equals(methodB) || a.length != b.length || specificity(a) != specificity(b)) return false;

        for (int i = 0; i < a.length; i++) if (a[i] != null && b[i] != null && !a[i].equals(b[i])) return false;

        return true;
    }

    static void permission(String service, String permission) {
        if (permission == null || !permission.startsWith(service + ":") || service.isEmpty())
            throw new IllegalArgumentException("Permission must be <service>:<scope>");

        ServiceName.require(permission.substring(service.length() + 1));
    }

    private static String[] names(String[] segments) {
        String[] names = new String[segments.length];
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            if (segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}")) {
                String name = segment.substring(1, segment.length() - 1);
                for (int j = 0; j < name.length(); j++) {
                    char c = name.charAt(j);

                    if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || j > 0 && c >= '0' && c <= '9'))
                        throw new IllegalArgumentException("Invalid path parameter");
                }
                names[i] = name;
            }
        }

        return names;
    }

    private static int specificity(String[] segments) {
        int literals = 0;
        for (String segment : segments) if (segment != null) literals++;

        return literals;
    }

    private static String[] split(String path) {
        if (path.equals("/")) return new String[0];

        ArrayList<String> segments = new ArrayList<>();
        int start = 1;
        for (int i = 1; i <= path.length(); i++)
            if (i == path.length() || path.charAt(i) == '/') {
                segments.add(path.substring(start, i));
                start = i + 1;
            }

        return segments.toArray(new String[0]);
    }

    /** Percent-decoded, strict UTF-8 request path segments. */
    private static String[] segments(String path) {
        if (!path.startsWith("/")) throw new RequestException(400, "Invalid request path");

        String[] segments = split(path);
        for (int i = 0; i < segments.length; i++) {
            String raw = segments[i];
            byte[] bytes = new byte[raw.length()];
            int length = 0;
            for (int j = 0; j < raw.length(); j++) {
                char c = raw.charAt(j);

                if (c == '%' && j + 2 < raw.length()) {
                    int high = Character.digit(raw.charAt(j + 1), 16), low = Character.digit(raw.charAt(j + 2), 16);

                    if (high < 0 || low < 0) throw new RequestException(400, "Invalid URL encoding");

                    bytes[length++] = (byte) (high * 16 + low);
                    j += 2;
                } else if (c == '%' || c <= 32 || c >= 127) {
                    throw new RequestException(400, "Invalid URL encoding");
                } else bytes[length++] = (byte) c;
            }
            byte[] exact = new byte[length];
            System.arraycopy(bytes, 0, exact, 0, length);
            try {
                segments[i] = Json.decodeUtf8(exact);
            } catch (JsonException invalid) {
                throw new RequestException(400, "Invalid URL encoding");
            }
        }

        return segments;
    }

    static final class Route {
        final String service, action, method, path, permission;
        final int version, specificity;
        final String[] segments, names;

        Route(Catalog.Instance instance, Catalog.Entry entry) {
            service = instance.service;
            version = instance.version;
            action = entry.name;
            method = entry.method;
            path = entry.path;
            permission = entry.permission;
            segments = template(path);
            names = names(split(path));
            specificity = specificity(segments);
        }

        boolean sameAction(Route other) {
            return sameActionName(other)
                    && method.equals(other.method)
                    && path.equals(other.path)
                    && permission.equals(other.permission);
        }

        boolean sameActionName(Route other) {
            return service.equals(other.service) && version == other.version && action.equals(other.action);
        }

        boolean matches(String[] request) {
            if (request.length != segments.length) return false;

            for (int i = 0; i < segments.length; i++)
                if (segments[i] == null ? request[i].isEmpty() : !segments[i].equals(request[i])) return false;

            return true;
        }
    }
}
