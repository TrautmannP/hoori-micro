package hoori.micro;

import hoori.micro.openapi.ContractJson;
import hoori.micro.openapi.OpenApiDocument;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable registry snapshot: instances and the HTTP endpoints each one actually offers. Unknown JSON
 * fields are skipped for additive evolution; invalid names, URLs or routes reject the document.
 */
final class Catalog {
    static final int MAX_INSTANCES = 256;
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

    /** Round-robin over instances of this major version that offer this endpoint, never per service. */
    Instance select(String service, int version, String endpoint, int turn) {
        return select(service, version, endpoint, turn, null);
    }

    Instance select(String service, int version, String endpoint, int turn, Gateway.Route published) {
        // ponytail: linear scan over at most 256 instances; index by endpoint if it shows up in profiles.
        int count = 0;
        for (Instance instance : instances) if (instance.offers(service, version, endpoint, published)) count++;

        if (count == 0) return null;

        int pick = (turn & Integer.MAX_VALUE) % count;
        for (Instance instance : instances)
            if (instance.offers(service, version, endpoint, published) && pick-- == 0) return instance;
        throw new IllegalStateException();
    }

    static final class Entry {
        final HttpEndpoint contract;
        final String key, permission, operationHash;

        Entry(HttpEndpoint contract, String permission) {
            this(contract, permission, null);
        }

        Entry(HttpEndpoint contract, String permission, String operationHash) {
            this.contract = Objects.requireNonNull(contract);
            this.key = contract.key();
            this.permission = permission;
            this.operationHash = operationHash;
        }
    }

    static final class Instance {
        final String id, service, url, contractHash, apiGroup;
        final int version;
        final Entry[] endpoints;

        Instance(String id, String service, int version, String url, Entry[] endpoints) {
            this(id, service, version, url, endpoints, null, null);
        }

        Instance(
                String id,
                String service,
                int version,
                String url,
                Entry[] endpoints,
                String contractHash,
                String apiGroup) {
            this.id = id;
            this.service = service;
            this.version = version;
            this.url = url;
            this.endpoints = endpoints;
            this.contractHash = contractHash;
            this.apiGroup = apiGroup;
        }

        boolean offers(String service, int version, String key) {
            return offers(service, version, key, null);
        }

        boolean offers(String service, int version, String key, Gateway.Route published) {
            if (this.version != version || !this.service.equals(service)) return false;

            for (Entry entry : endpoints)
                if (entry.key.equals(key)
                        && (published == null || entry.permission != null && published.matches(this, entry)))
                    return true;

            return false;
        }

        boolean sameMetadata(Instance other) {
            if (!id.equals(other.id)
                    || !service.equals(other.service)
                    || version != other.version
                    || !url.equals(other.url)
                    || !Objects.equals(contractHash, other.contractHash)
                    || !Objects.equals(apiGroup, other.apiGroup)
                    || endpoints.length != other.endpoints.length) return false;

            for (int i = 0; i < endpoints.length; i++) {
                Entry a = endpoints[i], b = other.endpoints[i];

                if (!a.key.equals(b.key)
                        || !Objects.equals(a.permission, b.permission)
                        || !Objects.equals(a.operationHash, b.operationHash)) return false;
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
            if (key == null) key = "all";

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

                    if (colon < 1 || dependencies.size() == ServiceDefinition.MAX_DEPENDENCIES)
                        throw new IllegalArgumentException("Invalid catalog dependency");

                    String name = ServiceName.require(dependency.substring(0, colon));
                    String number = dependency.substring(colon + 1);
                    int version = Integer.parseInt(number);

                    if (version < 1
                            || version > ServiceDefinition.MAX_VERSION
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

                ArrayList<Entry> endpoints = new ArrayList<>();
                for (Entry entry : instance.endpoints) {
                    if (dependency || published && entry.permission != null)
                        endpoints.add(published ? entry : new Entry(entry.contract, null));
                }

                if (!endpoints.isEmpty())
                    selected.add(new Instance(
                            instance.id,
                            instance.service,
                            instance.version,
                            instance.url,
                            endpoints.toArray(new Entry[0]),
                            published ? instance.contractHash : null,
                            published ? instance.apiGroup : null));
            }

            return new Catalog(source.epoch, source.revision, source.complete, selected.toArray(new Instance[0]));
        }
    }

    static final JsonCodec<Instance> INSTANCE = new JsonCodec<>() {
        @Override
        public Instance read(JsonReader input) {
            String id = null, service = null, url = null, contractHash = null, apiGroup = null;
            long version = 0;
            boolean declared = false;
            ArrayList<Entry> endpoints = new ArrayList<>();
            input.beginObject();
            while (input.hasNext()) {
                switch (input.nextName()) {
                    case "id" -> id = input.nextString();
                    case "service" -> service = input.nextString();
                    case "version" -> version = input.nextLong();
                    case "url" -> url = input.nextString();
                    case "contractHash" -> contractHash = input.nextString();
                    case "apiGroup" -> apiGroup = input.nextString();
                    case "endpoints" -> {
                        declared = true;
                        input.beginArray();
                        while (input.hasNext()) {
                            if (endpoints.size() == ServiceDefinition.MAX_ENDPOINTS)
                                throw new JsonException("Too many endpoints");

                            endpoints.add(entry(input));
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

                if (version < 1 || version > ServiceDefinition.MAX_VERSION || url == null || !declared)
                    throw new IllegalArgumentException();

                url = ServiceConfig.origin(url, "registration");

                if ((contractHash == null) != (apiGroup == null)
                        || contractHash != null
                                && (!ContractJson.isHash(contractHash) || !OpenApiDocument.name(apiGroup)))
                    throw new IllegalArgumentException();

                for (int i = 0; i < endpoints.size(); i++) {
                    Entry entry = endpoints.get(i);

                    if (entry.permission != null) Gateway.permission(service, entry.permission);

                    if (entry.operationHash != null
                                    && (entry.permission == null
                                            || contractHash == null
                                            || !ContractJson.isHash(entry.operationHash))
                            || contractHash != null && entry.permission != null && entry.operationHash == null)
                        throw new IllegalArgumentException();

                    for (int j = 0; j < i; j++)
                        if (endpoints.get(j).key.equals(entry.key)) throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException invalid) {
                throw new JsonException("Invalid registration");
            }

            return new Instance(
                    id, service, (int) version, url, endpoints.toArray(new Entry[0]), contractHash, apiGroup);
        }

        private Entry entry(JsonReader input) {
            String method = null,
                    path = null,
                    consumes = null,
                    produces = null,
                    permission = null,
                    operationHash = null;
            input.beginObject();
            while (input.hasNext()) {
                switch (input.nextName()) {
                    case "method" -> method = input.nextString();
                    case "path" -> path = input.nextString();
                    case "consumes" -> consumes = input.nextString();
                    case "produces" -> produces = input.nextString();
                    case "permission" -> permission = input.nextString();
                    case "operationHash" -> operationHash = input.nextString();
                    default -> input.skipValue();
                }
            }
            input.endObject();
            try {
                return new Entry(new HttpEndpoint(method, path, consumes, produces), permission, operationHash);
            } catch (IllegalArgumentException invalid) {
                throw new JsonException("Invalid HTTP endpoint");
            }
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
                    .name("endpoints")
                    .beginArray();
            for (Entry entry : value.endpoints) {
                output.beginObject()
                        .name("method")
                        .value(entry.contract.method())
                        .name("path")
                        .value(entry.contract.path())
                        .name("consumes")
                        .value(entry.contract.consumes())
                        .name("produces")
                        .value(entry.contract.produces());

                if (entry.permission != null) output.name("permission").value(entry.permission);

                if (entry.operationHash != null) output.name("operationHash").value(entry.operationHash);

                output.endObject();
            }
            output.endArray();

            if (value.contractHash != null)
                output.name("contractHash")
                        .value(value.contractHash)
                        .name("apiGroup")
                        .value(value.apiGroup);

            output.endObject();
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
