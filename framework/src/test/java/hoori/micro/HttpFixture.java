package hoori.micro;

import hoori.rest.Responses;
import hoori.rest.json.JsonCodec;

/** Low-level HTTP fixtures for encoding/admission counters; never application wiring. */
final class HttpFixture {
    @FunctionalInterface
    interface Body {
        Object handle(Context context, Object input) throws Exception;
    }

    static HttpEndpoint post(String path) {
        return new HttpEndpoint("POST", path, "application/json", "application/json");
    }

    static void post(Microservice app, String path, String permission, JsonCodec<Object> input, Body body) {
        app.endpoint(
                post(path),
                permission,
                request -> Responses.json(
                        200,
                        body.handle(app.context(), request.body(input, app.jsonLimits())),
                        JsonTree.CODEC,
                        app.jsonLimits()));
    }

    static Object call(RemoteClient client, String path, Object value) {
        return call(client, path, value, JsonTree.CODEC);
    }

    static Object call(RemoteClient client, String path, Object value, JsonCodec<Object> input) {
        return client.call(
                post(path),
                limits -> new ClientRequest(path).body(value, input, limits),
                (response, limits) -> RemoteClient.json(response, JsonTree.CODEC, limits));
    }
}
