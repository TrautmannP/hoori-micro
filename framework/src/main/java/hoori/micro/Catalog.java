package hoori.micro;

import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.ArrayList;

/**
 * Immutable registry snapshot: instances and the actions each one actually offers. Unknown JSON
 * fields are skipped for additive evolution; invalid names, URLs or routes reject the document.
 */
final class Catalog {
    static final int MAX_INSTANCES = 256;
    static final Catalog EMPTY = new Catalog(false, new Instance[0]);

    final boolean complete;
    final Instance[] instances;

    Catalog(boolean complete, Instance[] instances) {
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

        Instance(String id, String service, int version, String url, Entry[] actions) {
            this.id = id;
            this.service = service;
            this.version = version;
            this.url = url;
            this.actions = actions;
        }

        boolean offers(String service, int version, String action) {
            if (this.version != version || !this.service.equals(service)) return false;

            for (Entry entry : actions) if (entry.name.equals(action)) return true;

            return false;
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
            boolean complete = false;
            ArrayList<Instance> instances = new ArrayList<>();
            input.beginObject();
            while (input.hasNext()) {
                switch (input.nextName()) {
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

            return new Catalog(complete, instances.toArray(new Instance[0]));
        }

        @Override
        public void write(Catalog value, JsonWriter output) {
            output.beginObject()
                    .name("complete")
                    .value(value.complete)
                    .name("instances")
                    .beginArray();
            for (Instance instance : value.instances) INSTANCE.write(instance, output);
            output.endArray().endObject();
        }
    };
}
