package hoori.micro;

import static hoori.micro.openapi.ContractJson.object;

import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.Limits;
import hoori.http.RequestBudget;
import hoori.http.Response;
import hoori.micro.openapi.ContractJson;
import hoori.micro.openapi.OpenApiDocument;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Optional gateway documentation worker. No contract I/O runs in a request or registry heartbeat. */
public final class OpenApi implements AutoCloseable {
    private static final byte[] EMPTY = new byte[0];
    private final Microservice app;
    private final HttpClient client;
    private final Thread worker;
    private volatile boolean closed;
    private volatile Published published;
    private Map<String, OpenApiDocument> cache = new LinkedHashMap<>();

    private record Published(String id, Map<String, byte[]> groups) {}

    private OpenApi(Microservice app) {
        this.app = app;
        client = new HttpClient(
                new Limits(64, 16384, ContractJson.MAX_BYTES, 1, 100, 1000).withPendingAcquires(0), 1, 5000);
        worker = new Thread(this::run, "micro-openapi");
    }

    public static void mount(Microservice app) {
        if (!app.broker().followsPublicCatalog()) throw new IllegalStateException("Mount Gateway before OpenApi");

        OpenApi api = app.own(new OpenApi(app));
        app.controlRoute("GET", "/openapi.json", request -> api.document("public"));
        app.controlRoute("GET", "/_hoori/openapi/groups/{group}", request -> api.document(request.pathParam("group")));
        app.controlRoute("GET", "/_hoori/openapi", request -> api.index());
        app.controlRoute(
                "GET",
                "/_hoori/docs",
                request -> new Response(
                        200,
                        new Headers()
                                .add("Content-Type", "text/html; charset=utf-8")
                                .add(
                                        "Content-Security-Policy",
                                        "default-src 'none'; connect-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                                .add("X-Content-Type-Options", "nosniff"),
                        PAGE.getBytes(StandardCharsets.UTF_8)));
        api.worker.start();
    }

    private Published current() {
        Published current = published;
        GatewayPublication source = app.broker().snapshot().publication;

        return current != null && source != null && current.id.equals(source.id) ? current : null;
    }

    private Response document(String group) {
        Published current = current();

        if (current == null) return Response.text(503, "OpenAPI publication unavailable");

        byte[] content = current.groups.get(group);

        if (content == null) return Response.text(404, "OpenAPI group not published");

        return response(current.id, content);
    }

    private Response index() {
        Published current = current();

        if (current == null) return Response.text(503, "OpenAPI publication unavailable");

        return response(
                current.id,
                ContractJson.bytes(Map.of(
                        "publicationId", current.id, "groups", new java.util.ArrayList<>(current.groups.keySet()))));
    }

    private static Response response(String id, byte[] content) {
        return new Response(
                200,
                new Headers()
                        .add("Content-Type", "application/json")
                        .add("Cache-Control", "no-store")
                        .add("X-Hoori-Publication", id),
                content);
    }

    private void run() {
        while (!closed) {
            try {
                if (app.isReady()) refresh();
            } catch (IOException | RuntimeException failed) {
                // Missing, mismatched or oversized documents never replace a complete publication.
                // No provider errors or document contents are logged.
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException stopped) {
                return;
            }
        }
    }

    private void refresh() throws IOException {
        GatewayPublication source = app.broker().snapshot().publication;

        if (source == null || !source.complete || published != null && published.id.equals(source.id)) return;

        Map<String, OpenApiDocument> documents = new LinkedHashMap<>();
        int bytes = 0;
        RequestBudget budget = RequestBudget.afterMillis(5000);
        for (GatewayPublication.Selection selection : source.selections) {
            if (documents.containsKey(selection.hash())) continue;

            if (documents.size() == 64) throw new IOException("OpenAPI document count limit");

            OpenApiDocument document = cache.get(selection.hash());

            if (document == null) document = fetch(selection, budget);

            bytes += document.byteSize();

            if (bytes > 262144) throw new IOException("OpenAPI cache byte limit");

            documents.put(selection.hash(), document);
        }
        Map<String, byte[]> groups = aggregate(source, documents);
        GatewayPublication latest = app.broker().snapshot().publication;

        if (latest == null || !latest.id.equals(source.id) || closed || budget.isExpired()) return;

        cache = documents;
        published = new Published(source.id, Map.copyOf(groups));
    }

    private OpenApiDocument fetch(GatewayPublication.Selection selection, RequestBudget budget) throws IOException {
        // Exact artifact and matching instance; no round-robin by service name and no request-derived URL.
        for (Catalog.Instance provider : selection.providers()) {
            if (closed || budget.isExpired()) throw new IOException("OpenAPI refresh stopped");

            try {
                Response response = client.exchange(
                        URI.create(provider.url + "/_hoori/openapi/" + selection.hash()),
                        "GET",
                        new Headers().add("Accept", "application/json"),
                        EMPTY,
                        budget);

                if (response.status != 200
                        || !ServiceBroker.jsonContentType(response.headers)
                        || response.body.length > ContractJson.MAX_BYTES
                        || !selection.hash().equals(ContractJson.hash(response.body))) continue;

                OpenApiDocument document = new OpenApiDocument(response.body);

                if (document.hash().equals(selection.hash())) return document;
            } catch (IOException | IllegalArgumentException | hoori.rest.json.JsonException rejected) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("OpenAPI stopped");
            }
        }
        throw new IOException("OpenAPI artifact unavailable");
    }

    static Map<String, byte[]> aggregate(GatewayPublication source, Map<String, OpenApiDocument> documents) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, Set<String>> ids = new LinkedHashMap<>();
        for (GatewayPublication.Selection selection : source.selections) {
            Gateway.Route route = selection.route();
            OpenApiDocument document = documents.get(selection.hash());
            OpenApiDocument.require(
                    document != null
                            && document.hash().equals(selection.hash())
                            && document.serviceVersion() == route.version
                            && document.group().equals(route.apiGroup),
                    "OpenAPI artifact identity");
            OpenApiDocument.Operation operation = null;
            for (var offered : document.operations())
                if (offered.method().equals(route.contract.method())
                        && offered.path().equals(route.path)) operation = offered;
            OpenApiDocument.require(
                    operation != null
                            && operation.permission().equals(route.permission)
                            && operation.hash().equals(route.operationHash),
                    "OpenAPI operation identity");
            Map<String, Object> group = groups.get(route.apiGroup);

            if (group == null) {
                OpenApiDocument.require(groups.size() < 16, "OpenAPI API group limit");
                group = new LinkedHashMap<>();
                group.put("openapi", "3.1.0");
                group.put("info", Map.of("title", "Hoori Micro " + route.apiGroup, "version", source.id));
                group.put("servers", java.util.List.of(Map.of("url", "/")));
                group.put("x-hoori-publication-id", source.id);
                group.put("paths", new LinkedHashMap<String, Object>());
                groups.put(route.apiGroup, group);
                ids.put(route.apiGroup, new HashSet<>());
            }

            OpenApiDocument.require(ids.get(route.apiGroup).add(operation.id()), "Duplicate aggregated operationId");
            Map<String, Object> paths = object(group.get("paths"));
            Map<String, Object> path = object(paths.computeIfAbsent(route.path, ignored -> new LinkedHashMap<>()));
            String method = route.contract.method().toLowerCase(java.util.Locale.ROOT);
            OpenApiDocument.require(!path.containsKey(method), "Duplicate aggregated route");
            path.put(method, document.expandedOperation(operation));
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (var group : groups.entrySet()) result.put(group.getKey(), ContractJson.bytes(group.getValue()));

        return result;
    }

    @Override
    public void close() throws InterruptedException {
        closed = true;
        worker.interrupt();
        client.close();
        boolean interrupted = Thread.interrupted();
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException stopped) {
                interrupted = true;
            }
        }
        cache.clear();
        published = null;

        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final String PAGE = """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Hoori Micro API</title><style>
            body{font:16px system-ui;max-width:1000px;margin:40px auto;padding:0 20px;background:#fafafa;color:#172032}
            input,select{font:inherit;padding:8px}summary{cursor:pointer;padding:12px}details{background:white;border:1px solid #ddd;margin:12px 0}
            pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:12px}a{color:#1755aa}#status{overflow-wrap:anywhere}
            </style><h1>Hoori Micro API</h1><p>Published gateway contracts. Permissions describe routes; this page does not authenticate callers.</p>
            <label>API group <select id="group"></select></label> <label>Filter <input id="filter" type="search"></label>
            <p><a id="download">OpenAPI JSON</a></p><p id="status" role="status">Loading publication…</p><main id="operations"></main>
            <script>
            const group=document.querySelector('#group'),filter=document.querySelector('#filter'),status=document.querySelector('#status'),out=document.querySelector('#operations');let api;
            async function load(){out.replaceChildren();try{const url='/_hoori/openapi/groups/'+encodeURIComponent(group.value);const response=await fetch(url,{cache:'no-store'});if(!response.ok)throw Error('Publication unavailable ('+response.status+')');api=await response.json();document.querySelector('#download').href=url;status.textContent='Publication '+api['x-hoori-publication-id'];render()}catch(error){status.textContent=error.message}}
            function render(){out.replaceChildren();const query=filter.value.toLowerCase();for(const[path,methods]of Object.entries(api.paths)){for(const[method,operation]of Object.entries(methods)){const title=method.toUpperCase()+' '+path+' — '+(operation.summary||operation.operationId);if(!title.toLowerCase().includes(query))continue;const detail=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');summary.textContent=title;pre.textContent=JSON.stringify(operation,null,2);detail.append(summary,pre);out.append(detail)}}}
            group.addEventListener('change',load);filter.addEventListener('input',()=>{if(api)render()});
            fetch('/_hoori/openapi',{cache:'no-store'}).then(async response=>{if(!response.ok)throw Error('Publication unavailable ('+response.status+')');return response.json()}).then(index=>{for(const name of index.groups){const option=document.createElement('option');option.value=name;option.textContent=name;group.append(option)}if(index.groups.length)load();else status.textContent='No routes published'}).catch(error=>status.textContent=error.message);
            </script></html>
            """;
}
