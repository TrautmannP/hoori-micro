package hoori.micro;

import hoori.http.Response;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import hoori.rest.mvc.HttpResult;
import java.io.IOException;

/** Shared-pool support for generated HTTP clients. No context is captured at construction. */
public final class RemoteClient {
    @FunctionalInterface
    public interface Encoder {
        ClientRequest encode(JsonLimits limits);
    }

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(Response response, JsonLimits limits);
    }

    private final Microservice app;
    private final String service;
    private final int version;

    public RemoteClient(Microservice app, String service, int version) {
        this.app = app;
        this.service = service;
        this.version = version;
        app.dependency(service, version);
    }

    public <T> T call(HttpEndpoint endpoint, Encoder encoder, Decoder<T> decoder) {
        Context context = app.context();
        Invocation invocation = context.invocation();
        try {
            return app.broker()
                    .call(invocation, context.effectiveBudget(), service, version, endpoint, encoder, decoder);
        } catch (IOException failure) {
            throw new TransportFailure(failure);
        }
    }

    public static <T> T json(Response response, JsonCodec<T> codec, JsonLimits limits) {
        if (response.status == 204 || !ServiceBroker.jsonContentType(response.headers))
            throw new JsonException("Expected JSON response");

        T result = Json.decode(response.body, codec, limits);

        if (result == null) throw new JsonException("Null response");

        return result;
    }

    public static Void empty(Response response) {
        if (response.status != 204 || response.body.length != 0) throw new JsonException("Expected 204 response");

        return null;
    }

    public static <T> HttpResult<T> result(Response response, JsonCodec<T> codec, JsonLimits limits) {
        if (codec == null && response.body.length != 0) throw new JsonException("Expected empty response");

        HttpResult<T> result = response.body.length == 0
                ? HttpResult.empty(response.status)
                : HttpResult.of(response.status, json(response, codec, limits));
        var headers = HttpResponses.headers(response.headers);
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);

            if (HttpResponses.resultHeader(name)) result.header(name, headers.value(i));
        }

        return result;
    }

    /** A checked transport failure at a synchronous interface; the classifier preserves its cause. */
    static final class TransportFailure extends RuntimeException {
        TransportFailure(IOException cause) {
            super("Client operation failed", cause);
        }
    }
}
