package hoori.micro;

import hoori.http.Response;
import hoori.rest.Request;
import hoori.rest.Responses;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Single source of a service's actions and dependencies. The same definition drives the local
 * /_hoori/invoke dispatcher and the published registry catalog. Build on the startup thread only;
 * Microservice.create validates and freezes it.
 */
public final class Service {
    @FunctionalInterface
    public interface Handler<I, O> {
        O handle(Context ctx, I input) throws Exception;
    }

    static final int MAX_ACTIONS = 128, MAX_DEPENDENCIES = 32, MAX_VERSION = 9999;

    final String name;
    int version = 1;
    final LinkedHashMap<String, Definition<?, ?>> actions = new LinkedHashMap<>();
    final LinkedHashMap<String, Integer> dependencies = new LinkedHashMap<>();
    private Definition<?, ?> last;
    private boolean frozen;

    private Service(String name) {
        this.name = ServiceName.require(name);
    }

    public static Service named(String name) {
        return new Service(name);
    }

    /** Major contract version. Callers bind to it with dependsOn(name, major). */
    public Service version(int major) {
        mutable();

        if (major < 1 || major > MAX_VERSION) throw new IllegalArgumentException("Version 1-9999");

        version = major;

        return this;
    }

    public <I, O> Service action(String name, JsonCodec<I> input, JsonCodec<O> output, Handler<I, O> handler) {
        mutable();

        if (input == null || output == null || handler == null) throw new NullPointerException();

        if (actions.containsKey(ServiceName.require(name))) throw new IllegalArgumentException("Duplicate action");

        if (actions.size() == MAX_ACTIONS) throw new IllegalArgumentException("At most 128 actions");

        last = new Definition<>(name, input, output, handler);
        actions.put(name, last);

        return this;
    }

    /** Offers the previous action to gateways. Not public until requirePermission() is also set. */
    public Service http(String method, String pathTemplate) {
        mutable();

        if (last == null) throw new IllegalStateException("Declare an action first");

        Gateway.template(pathTemplate);

        if (!Gateway.METHODS.contains(method)) throw new IllegalArgumentException("Unsupported HTTP method");

        last.method = method;
        last.path = pathTemplate;

        return this;
    }

    /** Permission a gateway policy must grant; namespaced as "<service>:<scope>". */
    public Service requirePermission(String permission) {
        mutable();

        if (last == null) throw new IllegalStateException("Declare an action first");

        Gateway.permission(name, permission);
        last.permission = permission;

        return this;
    }

    public Service dependsOn(String service, int major) {
        mutable();

        if (dependencies.containsKey(ServiceName.require(service)))
            throw new IllegalArgumentException("Duplicate service dependency");

        if (major < 1 || major > MAX_VERSION) throw new IllegalArgumentException("Version 1-9999");

        if (dependencies.size() == MAX_DEPENDENCIES) throw new IllegalArgumentException("At most 32 dependencies");

        dependencies.put(service, major);

        return this;
    }

    Service freeze() {
        if (frozen) throw new IllegalStateException("Service already used by a Microservice");

        ArrayList<Catalog.Entry> published = new ArrayList<>();
        for (Definition<?, ?> action : actions.values()) {
            if ((action.path == null) != (action.permission == null))
                throw new IllegalArgumentException("Action " + action.name + ": http() requires requirePermission()");

            if (action.path == null) continue;

            for (Catalog.Entry other : published)
                if (Gateway.conflicts(action.method, action.path, other.method, other.path))
                    throw new IllegalArgumentException("Conflicting HTTP routes: " + action.name + ", " + other.name);
            published.add(action.entry());
        }
        frozen = true;

        return this;
    }

    private void mutable() {
        if (frozen) throw new IllegalStateException("Service definition is frozen");
    }

    static final class Definition<I, O> {
        final String name;
        final JsonCodec<I> input;
        final JsonCodec<O> output;
        final Handler<I, O> handler;
        String method, path, permission;

        Definition(String name, JsonCodec<I> input, JsonCodec<O> output, Handler<I, O> handler) {
            this.name = name;
            this.input = input;
            this.output = output;
            this.handler = handler;
        }

        Catalog.Entry entry() {
            return new Catalog.Entry(name, method, path, permission);
        }

        Response invoke(Context ctx, Request request, JsonLimits limits) throws Exception {
            O result = handler.handle(ctx, request.body(input, limits));

            if (result == null) throw new IllegalStateException("Action returned null");

            return Responses.json(200, result, output, limits);
        }
    }
}
