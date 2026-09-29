package hoori.micro;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.json.Json;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/** TTL-bound discovery state. Only visible changes replace the one cached, bounded snapshot. */
public final class Registry {
    private static final byte[] EMPTY = new byte[0];
    private final long ttlNanos, startedNanos = System.nanoTime();
    private final String epoch =
            "r" + Long.toString(System.currentTimeMillis()) + "-" + Long.toString(startedNanos & Long.MAX_VALUE);
    private final JsonLimits limits;
    private final LinkedHashMap<String, Registered> instances = new LinkedHashMap<>();
    private long revision = 1;
    private boolean complete;
    private Catalog cached;
    private byte[] encoded;

    Registry(int ttlMillis, JsonLimits limits) {
        if (ttlMillis < 1 || limits == null) throw new IllegalArgumentException();

        ttlNanos = ttlMillis * 1_000_000L;
        this.limits = limits;
    }

    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create(Service.named("registry"))) {
            new Registry(app.config().registryTtlMillis, app.jsonLimits()).mount(app);
            app.run();
        }
    }

    void mount(Microservice app) {
        app.routes().put("/v1/instances/{id}", request -> {
            protocol(request.raw().headers, false);
            Catalog.Instance instance = request.body(Catalog.INSTANCE, limits);

            if (!instance.id.equals(request.pathParam("id"))) throw new RequestException(400, "Instance ID mismatch");

            long now = System.nanoTime();
            register(instance, now);

            return reply(request.raw().headers, now);
        });
        app.routes().post("/v1/instances/{id}/lease", request -> {
            protocol(request.raw().headers, true);

            if (request.raw().body.length != 0) throw new RequestException(400, "Lease body must be empty");

            long now = System.nanoTime();

            if (!renew(request.pathParam("id"), now)) throw new RequestException(404, "Unknown lease");

            return reply(request.raw().headers, now);
        });
        app.routes().delete("/v1/instances/{id}", request -> {
            protocol(request.raw().headers, false);
            remove(request.pathParam("id"));

            return Responses.empty(204);
        });
        app.routes().get("/v1/catalog", request -> {
            protocol(request.raw().headers, false);

            return reply(request.raw().headers, System.nanoTime());
        });
    }

    synchronized Catalog register(Catalog.Instance instance, long now) {
        Catalog current = snapshot(now);
        Registered old = instances.get(instance.id);

        if (old != null && old.instance.sameMetadata(instance)) {
            old.expiresNanos = now + ttlNanos;

            return current;
        }

        if (old == null && instances.size() == Catalog.MAX_INSTANCES) throw new RequestException(429, "Registry full");

        Catalog.Instance[] proposed = new Catalog.Instance[current.instances.length + (old == null ? 1 : 0)];
        for (int i = 0; i < current.instances.length; i++) {
            Catalog.Instance existing = current.instances[i];
            proposed[i] = existing.id.equals(instance.id) ? instance : existing;
        }

        if (old == null) proposed[proposed.length - 1] = instance;

        Catalog next = new Catalog(epoch, revision + 1, complete, proposed);
        byte[] bytes;
        try {
            bytes = Json.encode(next, Catalog.CODEC, limits);

            // Reserve revision digit growth and the longer complete=false spelling before committing.
            if (bytes.length + 19 - Long.toString(next.revision).length() + (complete ? 1 : 0) > limits.maxOutputBytes)
                throw new JsonException("Catalog byte limit");
        } catch (JsonException tooLarge) {
            throw new RequestException(413, "Catalog byte limit");
        }
        instances.put(instance.id, new Registered(instance, now + ttlNanos));
        revision = next.revision;
        cached = next;
        encoded = bytes;

        return cached;
    }

    synchronized boolean renew(String id, long now) {
        advance(now);
        Registered registered = instances.get(id);

        if (registered == null) return false;

        registered.expiresNanos = now + ttlNanos;

        return true;
    }

    synchronized void remove(String id) {
        if (instances.remove(id) != null) changed();
    }

    synchronized Catalog snapshot(long now) {
        advance(now);

        if (cached == null) {
            ArrayList<Catalog.Instance> live = new ArrayList<>();
            for (Registered registered : instances.values()) live.add(registered.instance);
            cached = new Catalog(epoch, revision, complete, live.toArray(new Catalog.Instance[0]));
            encoded = Json.encode(cached, Catalog.CODEC, limits);
        }

        return cached;
    }

    synchronized Response reply(Headers known, long now) {
        snapshot(now);
        Headers headers = new Headers()
                .add(Catalog.PROTOCOL_HEADER, "2")
                .add(Catalog.EPOCH_HEADER, epoch)
                .add(Catalog.REVISION_HEADER, Long.toString(revision));

        if ("2".equals(known.get(Catalog.PROTOCOL_HEADER))
                && epoch.equals(known.get(Catalog.EPOCH_HEADER))
                && Long.toString(revision).equals(known.get(Catalog.REVISION_HEADER)))
            return new Response(204, headers, EMPTY);

        return new Response(200, headers.add("Content-Type", "application/json"), encoded);
    }

    private static void protocol(Headers headers, boolean required) {
        String version = headers.get(Catalog.PROTOCOL_HEADER);

        if (version == null && !required || "2".equals(version)) return;

        throw new RequestException(426, "Catalog protocol 2 required");
    }

    private void advance(long now) {
        boolean change = false;
        for (Iterator<Registered> it = instances.values().iterator(); it.hasNext(); ) {
            if (it.next().expiresNanos - now <= 0) {
                it.remove();
                change = true;
            }
        }

        if (!complete && now - startedNanos >= ttlNanos) {
            complete = true;
            change = true;
        }

        if (change) changed();
    }

    private void changed() {
        revision++;
        cached = null;
        encoded = null;
    }

    private static final class Registered {
        final Catalog.Instance instance;
        long expiresNanos;

        Registered(Catalog.Instance instance, long expiresNanos) {
            this.instance = instance;
            this.expiresNanos = expiresNanos;
        }
    }
}
