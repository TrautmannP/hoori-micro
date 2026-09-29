package hoori.micro;

import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.json.JsonLimits;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Central catalog of TTL-bound instance registrations. Brokers fetch it with their heartbeat and
 * then call each other directly; the registry is never on the business request path. State is
 * in memory: after a restart, services re-register with their next heartbeat, and the catalog is
 * marked incomplete until one TTL has passed. Unauthenticated; trust boundary = the private network.
 */
public final class Registry {
    private final long ttlNanos, startedNanos = System.nanoTime();
    private final LinkedHashMap<String, Registered> instances = new LinkedHashMap<>();

    Registry(int ttlMillis) {
        ttlNanos = ttlMillis * 1_000_000L;
    }

    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create(Service.named("registry"))) {
            new Registry(app.config().registryTtlMillis).mount(app);
            app.run();
        }
    }

    void mount(Microservice app) {
        JsonLimits limits = app.jsonLimits();
        app.routes().put("/v1/instances/{id}", request -> {
            Catalog.Instance instance = request.body(Catalog.INSTANCE, limits);

            if (!instance.id.equals(request.pathParam("id"))) throw new RequestException(400, "Instance ID mismatch");

            return Responses.json(200, register(instance, System.nanoTime()), Catalog.CODEC, limits);
        });
        app.routes().delete("/v1/instances/{id}", request -> {
            remove(request.pathParam("id"));

            return Responses.empty(204);
        });
        app.routes()
                .get("/v1/catalog", request -> Responses.json(200, snapshot(System.nanoTime()), Catalog.CODEC, limits));
    }

    synchronized Catalog register(Catalog.Instance instance, long now) {
        purge(now);

        if (!instances.containsKey(instance.id) && instances.size() == Catalog.MAX_INSTANCES)
            throw new RequestException(503, "Registry full");

        instances.put(instance.id, new Registered(instance, now + ttlNanos));

        return snapshot(now);
    }

    synchronized void remove(String id) {
        instances.remove(id);
    }

    synchronized Catalog snapshot(long now) {
        purge(now);
        ArrayList<Catalog.Instance> live = new ArrayList<>();
        for (Registered registered : instances.values()) live.add(registered.instance);

        return new Catalog(now - startedNanos >= ttlNanos, live.toArray(new Catalog.Instance[0]));
    }

    private void purge(long now) {
        for (Iterator<Registered> it = instances.values().iterator(); it.hasNext(); )
            if (it.next().expiresNanos - now <= 0) it.remove();
    }

    private static final class Registered {
        final Catalog.Instance instance;
        final long expiresNanos;

        Registered(Catalog.Instance instance, long expiresNanos) {
            this.instance = instance;
            this.expiresNanos = expiresNanos;
        }
    }
}
