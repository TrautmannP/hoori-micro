package hoori.micro;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable registry snapshot: instances and the actions each one actually offers. Unknown JSON
 * fields are skipped for additive evolution; invalid names, URLs or routes reject the document.
 */
final class Catalog {
    static final int MAX_INSTANCES = 256;
    static final String PROTOCOL_HEADER = "X-Hoori-Catalog-Protocol";
    static final String EPOCH_HEADER = "X-Hoori-Catalog-Epoch", REVISION_HEADER = "X-Hoori-Catalog-Revision";
    static final String VIEW_HEADER = "X-Hoori-Catalog-View", KNOWN_VIEW_HEADER = "X-Hoori-Catalog-Known-View";
    static final Catalog EMPTY = new Catalog("", 0, false, new Instance[0]);

    final String epoch;
    final long revision;
    final boolean complete;
    final Instance[] instances;

    Catalog(String epoch, long revision, boolean complete, Instance[] instances) {
        this.epoch = epoch;
        this.revision = revision;
        this.complete = complete;
        this.instances = instances;
    }

    /** Round-robin over instances of this major version that offer this action, never per service. */
    Instance select(String service, int version, String action, int turn) {
        // ponytail: linear scan over at most 256 instances; index by action if it shows up in profiles.
        int count = 0;
        for (Instance instance : instances) if (instance.offers(service, version, action)) count++;

        if (count == 0) return null;

        int pick = (turn & Integer.MAX_VALUE) % count;
        for (Instance instance : instances)
            if (instance.offers(service, version, action) && pick-- == 0) return instance;
        throw new IllegalStateException();
    }

    static final class Entry {
        final String name, method, path, permission;

        Entry(String name, String method, String path, String permission) {
            this.name = name;
            this.method = method;
            this.path = path;
            this.permission = permission;
        }
    }

    static final class Instance {
        final String id, service, url;
        final int version;
        final Entry[] actions;
        final URI invokeTarget;

        Instance(String id, String service, int version, String url, Entry[] actions) {
            this.id = id;
            this.service = service;
            this.version = version;
            this.url = url;
            this.actions = actions;
            invokeTarget = URI.create(url + ServiceBroker.INVOKE_PATH);
        }

        boolean offers(String service, int version, String action) {
            if (this.version != version || !this.service.equals(service)) return false;

            for (Entry entry : actions) if (entry.name.equals(action)) return true;

            return false;
        }

        boolean sameMetadata(Instance other) {
            if (!id.equals(other.id)
                    || !service.equals(other.service)
                    || version != other.version
                    || !url.equals(other.url)
                    || actions.length != other.actions.length) return false;

            for (int i = 0; i < actions.length; i++) {
                Entry a = actions[i], b = other.actions[i];

                if (!a.name.equals(b.name)
                        || !Objects.equals(a.method, b.method)
                        || !Objects.equals(a.path, b.path)
                        || !Objects.equals(a.permission, b.permission)) return false;
            }

            return true;
        }
    }

    /** Fixed startup selection, not a cache keyed by incoming business requests. */
    static final class Filter {
        final String key;
        private final boolean all, published;
        private final Map<String, Integer> dependencies;

        private Filter(String key, boolean all, boolean published, Map<String, Integer> dependencies) {
            this.key = key;
            this.all = all;
            this.published = published;
            this.dependencies = dependencies;
        }

        static Filter consumer(Map<String, Integer> dependencies, boolean published) {
            StringBuilder key = new StringBuilder(published ? "public" : "none");

            if (!dependencies.isEmpty()) {
                key = new StringBuilder(published ? "public;services=" : "services=");
                for (Map.Entry<String, Integer> dependency : dependencies.entrySet()) {
                    if (key.charAt(key.length() - 1) != '=') key.append(',');

                    key.append(dependency.getKey()).append(':').append(dependency.getValue());
                }
            }

            return parse(key.toString());
        }

        static Filter parse(String key) {
            if (key == null) key = "all"; // Old protocol-2 clients still receive the full catalog.

            if (key.length() > 2300) throw new IllegalArgumentException("Catalog view too long");

            LinkedHashMap<String, Integer> dependencies = new LinkedHashMap<>();
            boolean published = key.equals("public") || key.startsWith("public;services=");

            if (!key.equals("all") && !key.equals("none") && !key.equals("public")) {
                String prefix = published ? "public;services=" : "services=";

                if (!key.startsWith(prefix)) throw new IllegalArgumentException("Invalid catalog view");

                int start = prefix.length();
                do {
                    int end = key.indexOf(',', start);

                    if (end < 0) end = key.length();

                    String dependency = key.substring(start, end);
                    int colon = dependency.indexOf(':');

                    if (colon < 1 || dependencies.size() == Service.MAX_DEPENDENCIES)
                        throw new IllegalArgumentException("Invalid catalog dependency");

                    String name = ServiceName.require(dependency.substring(0, colon));
                    String number = dependency.substring(colon + 1);
                    int version = Integer.parseInt(number);

                    if (version < 1
                            || version > Service.MAX_VERSION
                            || !number.equals(Integer.toString(version))
                            || dependencies.put(name, version) != null)
                        throw new IllegalArgumentException("Invalid catalog dependency");

                    start = end + 1;
                } while (start <= key.length());
            }

            return new Filter(key, key.equals("all"), published, dependencies);
        }

        Catalog apply(Catalog source) {
            if (all) return source;

            ArrayList<Instance> selected = new ArrayList<>();
            for (Instance instance : source.instances) {
                Integer version = dependencies.get(instance.service);
                boolean dependency = version != null && version == instance.version;

                if (!dependency && !published) continue;

                ArrayList<Entry> actions = new ArrayList<>();
                for (Entry entry : instance.actions) {
                    if (dependency || published && entry.path != null)
                        actions.add(published ? entry : new Entry(entry.name, null, null, null));
                }

                if (!actions.isEmpty())
                    selected.add(new Instance(
                            instance.id,
                            instance.service,
                            instance.version,
                            instance.url,
                            actions.toArray(new Entry[0])));
            }

            return new Catalog(source.epoch, source.revision, source.complete, selected.toArray(new Instance[0]));
        }
    }

    static final JsonCodec<Instance> INSTANCE = new JsonCodec<>() {
        @Override
        public Instance read(JsonReader input) {
            String id = null, service = null, url = null;
            long version = 0;
            ArrayList<Entry> actions = new ArrayList<>();
            input.beginObject();
            while (input.hasNext()) {
                switch (input.nextName()) {
                    case "id" -> id = input.nextString();
                    case "service" -> service = input.nextString();
                    case "version" -> version = input.nextLong();
                    case "url" -> url = input.nextString();
                    case "actions" -> {
                        input.beginArray();
                        while (input.hasNext()) {
                            if (actions.size() == Service.MAX_ACTIONS) throw new JsonException("Too many actions");

                            actions.add(entry(input));
                        }
                        input.endArray();
                    }
                    default -> input.skipValue();
                }
            }
            input.endObject();
            try {
                ServiceName.require(id);
                ServiceName.require(service);

                if (version < 1 || version > Service.MAX_VERSION || url == null) throw new IllegalArgumentException();

                url = ServiceConfig.origin(url, "registration");
                for (int i = 0; i < actions.size(); i++) {
                    Entry entry = actions.get(i);
                    ServiceName.require(entry.name);

                    if ((entry.method == null) != (entry.path == null)
                            || (entry.path == null) != (entry.permission == null)) throw new IllegalArgumentException();

                    if (entry.path != null) {
                        if (!Gateway.METHODS.contains(entry.method)) throw new IllegalArgumentException();

                        Gateway.template(entry.path);
                        Gateway.permission(service, entry.permission);
                    }

                    for (int j = 0; j < i; j++)
                        if (actions.get(j).name.equals(entry.name)) throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException invalid) {
                throw new JsonException("Invalid registration");
            }

            return new Instance(id, service, (int) version, url, actions.toArray(new Entry[0]));
        }

        private Entry entry(JsonReader input) {
            String name = null, method = null, path = null, permission = null;
            input.beginObject();
            while (input.hasNext()) {
                switch (input.nextName()) {
                    case "name" -> name = input.nextString();
                    case "method" -> method = input.nextString();
                    case "path" -> path = input.nextString();
                    case "permission" -> permission = input.nextString();
                    default -> input.skipValue();
                }
            }
            input.endObject();

            return new Entry(name, method, path, permission);
        }

        @Override
        public void write(Instance value, JsonWriter output) {
            output.beginObject()
                    .name("id")
                    .value(value.id)
                    .name("service")
                    .value(value.service)
                    .name("version")
                    .value(value.version)
                    .name("url")
                    .value(value.url)
                    .name("actions")
                    .beginArray();
            for (Entry entry : value.actions) {
                output.beginObject().name("name").value(entry.name);

                if (entry.path != null)
                    output.name("method")
                            .value(entry.method)
                            .name("path")
                            .value(entry.path)
                            .name("permission")
                            .value(entry.permission);

                output.endObject();
            }
            output.endArray().endObject();
        }
    };

    static final JsonCodec<Catalog> CODEC = new JsonCodec<>() {
        @Override
        public Catalog read(JsonReader input) {
            String epoch = null;
            long revision = 0;
            boolean complete = false;
            ArrayList<Instance> instances = new ArrayList<>();
            input.beginObject();
            while (input.hasNext()) {
                switch (input.nextName()) {
                    case "epoch" -> epoch = input.nextString();
                    case "revision" -> revision = input.nextLong();
                    case "complete" -> complete = input.nextBoolean();
                    case "instances" -> {
                        input.beginArray();
                        while (input.hasNext()) {
                            if (instances.size() == MAX_INSTANCES) throw new JsonException("Too many instances");

                            instances.add(INSTANCE.read(input));
                        }
                        input.endArray();
                    }
                    default -> input.skipValue();
                }
            }
            input.endObject();
            try {
                ServiceName.require(epoch);

                if (revision < 1) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new JsonException("Invalid catalog version");
            }

            return new Catalog(epoch, revision, complete, instances.toArray(new Instance[0]));
        }

        @Override
        public void write(Catalog value, JsonWriter output) {
            output.beginObject()
                    .name("epoch")
                    .value(value.epoch)
                    .name("revision")
                    .value(value.revision)
                    .name("complete")
                    .value(value.complete)
                    .name("instances")
                    .beginArray();
            for (Instance instance : value.instances) INSTANCE.write(instance, output);
            output.endArray().endObject();
        }
    };
}
