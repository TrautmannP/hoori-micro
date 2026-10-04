package hoori.micro;

import hoori.http.Response;
import hoori.rest.Request;
import hoori.rest.Responses;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonLimits;
import hoori.rest.validation.ValidatedBody;
import hoori.validation.DtoValidator;
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
        return action(name, input, output, null, handler);
    }

    /** A null validator keeps the codec-only contract. Validation uses the existing request scope. */
    public <I, O> Service action(
            String name, JsonCodec<I> input, JsonCodec<O> output, DtoValidator<I> validator, Handler<I, O> handler) {
        mutable();

        if (input == null || output == null || handler == null) throw new NullPointerException();

        if (actions.containsKey(ServiceName.require(name))) throw new IllegalArgumentException("Duplicate action");

        if (actions.size() == MAX_ACTIONS) throw new IllegalArgumentException("At most 128 actions");

        last = new Definition<>(name, input, output, validator, handler);
        actions.put(name, last);

        return this;
    }

    /** Reuses the same explicit wire contract on provider and consumer. */
    public <I, O> Service action(Action<I, O> contract, Handler<I, O> handler) {
        if (contract == null) throw new NullPointerException("contract");

        if (!name.equals(contract.service)) throw new IllegalArgumentException("Contract belongs to another service");

        return action(contract.operation, contract.input, contract.output, contract.validator, handler);
    }

    /** Offers the previous action to gateways. Not public until requirePermission() is also set. */
    public Service http(String method, String pathTemplate) {
        if (last == null) throw new IllegalStateException("Declare an action first");

        return http(last.name, method, pathTemplate);
    }

    /** Binds publication to this named local action, independent of registration order. */
    public Service http(String action, String method, String pathTemplate) {
        Definition<?, ?> definition = definition(action);
        Gateway.template(pathTemplate);

        if (!Gateway.METHODS.contains(method)) throw new IllegalArgumentException("Unsupported HTTP method");

        definition.method = method;
        definition.path = pathTemplate;

        return this;
    }

    /** Permission a gateway policy must grant; namespaced as "<service>:<scope>". */
    public Service requirePermission(String permission) {
        if (last == null) throw new IllegalStateException("Declare an action first");

        return requirePermission(last.name, permission);
    }

    public Service requirePermission(String action, String permission) {
        Definition<?, ?> definition = definition(action);
        Gateway.permission(name, permission);
        definition.permission = permission;

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

    private Definition<?, ?> definition(String action) {
        mutable();
        Definition<?, ?> definition = actions.get(ServiceName.require(action));

        if (definition == null) throw new IllegalArgumentException("Unknown local action");

        return definition;
    }

    static final class Definition<I, O> {
        final String name;
        final JsonCodec<I> input;
        final JsonCodec<O> output;
        final Handler<I, O> handler;
        final DtoValidator<I> validator;
        String method, path, permission;

        Definition(
                String name,
                JsonCodec<I> input,
                JsonCodec<O> output,
                DtoValidator<I> validator,
                Handler<I, O> handler) {
            this.name = name;
            this.input = input;
            this.output = output;
            this.handler = handler;
            this.validator = validator;
        }

        Catalog.Entry entry() {
            return new Catalog.Entry(name, method, path, permission);
        }

        Response invoke(Context ctx, Request request, JsonLimits limits) throws Exception {
            ctx.check();

            if (validator == null) return respond(ctx, request.body(input, limits), limits);

            Response response = ValidatedBody.handle(
                            input, limits, validator, (ignored, params) -> respond(ctx, params, limits))
                    .handle(request);
            ctx.check();

            return response;
        }

        private Response respond(Context ctx, I params, JsonLimits limits) throws Exception {
            ctx.check();
            O result = handler.handle(ctx, params);

            if (result == null) throw new IllegalStateException("Action returned null");

            ctx.check();

            return Responses.json(200, result, output, limits);
        }
    }
}
