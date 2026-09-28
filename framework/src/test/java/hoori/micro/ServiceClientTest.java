package hoori.micro;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonLimits;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ServiceClientTest {
    private static final JsonCodec<String> STRING = new JsonCodec<>() {
        public String read(JsonReader reader) { return reader.nextString(); }
        public void write(String value, JsonWriter writer) { writer.value(value); }
    };
    private final ServiceDirectory directory = ServiceDirectory.from(key -> null, "recipes");

    @Test
    void typedGetUsesNamedOriginAndOnlyExplicitHeaders() throws IOException {
        int[] calls = {0};
        ServiceClient client = new ServiceClient(directory, (context, target, method, headers, body) -> {
            calls[0]++;
            assertNull(context);
            assertEquals("http://recipes:8080/v1", target.toString());
            assertEquals("GET", method);
            assertEquals("application/json", headers.get("Accept"));
            assertNull(headers.get("Authorization"));
            assertEquals(0, body.length);
            return json(200, "\"ok\"");
        }, JsonLimits.DEFAULT);
        assertEquals("ok", client.getJson(null, "recipes", "/v1", STRING));
        assertEquals(1, calls[0]);
    }

    @Test
    void writesAreNotRetriedAfterAnAmbiguousTransportFailure() {
        int[] calls = {0};
        ServiceClient client = new ServiceClient(directory, (context, target, method, headers, body) -> {
            calls[0]++;
            assertEquals("POST", method);
            assertEquals("application/json", headers.get("Content-Type"));
            assertEquals("\"milk\"", new String(body, StandardCharsets.UTF_8));
            throw new IOException("peer may already have committed");
        }, JsonLimits.DEFAULT);
        ServiceCallException failure = assertThrows(ServiceCallException.class,
                () -> client.postJson(null, "recipes", "/v1", "milk", STRING, STRING));
        assertEquals(1, calls[0]);
        assertEquals(0, failure.upstreamStatus());
        assertEquals("recipes", failure.service());
    }

    @Test
    void redirectsErrorsAndInvalidJsonAreNotHidden() {
        for (int status : new int[] {301, 302, 307, 404, 429, 500, 503}) {
            int[] calls = {0};
            ServiceClient client = new ServiceClient(directory, (context, target, method, headers, body) -> {
                calls[0]++;
                return json(status, "sensitive upstream body");
            }, JsonLimits.DEFAULT);
            ServiceCallException failure = assertThrows(ServiceCallException.class,
                    () -> client.getJson(null, "recipes", "/", STRING));
            assertEquals(status, failure.upstreamStatus());
            assertFalse(failure.getMessage().contains("sensitive"));
            assertEquals(1, calls[0]);
        }
        for (String body : new String[] {"", "null", "{invalid", "\"ok\" trailing"}) {
            ServiceClient client = constant(json(200, body));
            assertThrows(ServiceCallException.class, () -> client.getJson(null, "recipes", "/", STRING));
        }
    }

    @Test
    void jsonResponseMediaTypeIsDeliberatelyNarrow() throws IOException {
        for (String value : new String[] {"application/json", "Application/JSON; charset=UTF-8",
                "application/json; charset=\"utf-8\""}) {
            ServiceClient client = constant(new Response(200, new Headers().add("Content-Type", value),
                    "\"ok\"".getBytes(StandardCharsets.UTF_8)));
            assertEquals("ok", client.getJson(null, "recipes", "/", STRING));
        }
        for (String value : new String[] {"text/html", "application/json; charset=latin1",
                "application/json; charset=utf-8; other=1", "application/problem+json"}) {
            ServiceClient client = constant(new Response(200, new Headers().add("Content-Type", value),
                    "\"ok\"".getBytes(StandardCharsets.UTF_8)));
            assertThrows(ServiceCallException.class, () -> client.getJson(null, "recipes", "/", STRING));
        }
        ServiceClient duplicate = constant(new Response(200, new Headers()
                .add("Content-Type", "application/json").add("content-type", "application/json"),
                "\"ok\"".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ServiceCallException.class, () -> duplicate.getJson(null, "recipes", "/", STRING));
    }

    @Test
    void interruptedIoIsPropagatedWithoutRetryOrReclassification() {
        InterruptedIOException original = new InterruptedIOException("cancelled");
        ServiceClient client = new ServiceClient(directory, (context, target, method, headers, body) -> {
            throw original;
        }, JsonLimits.DEFAULT);
        assertSame(original, assertThrows(InterruptedIOException.class,
                () -> client.getJson(null, "recipes", "/", STRING)));
    }

    @Test
    void invalidDestinationAndMissingCodecFailBeforeIo() {
        ServiceClient client = new ServiceClient(directory, (context, target, method, headers, body) -> {
            fail("No transport call expected");
            return null;
        }, JsonLimits.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> client.getJson(null, "unlisted", "/", STRING));
        assertThrows(IllegalArgumentException.class, () -> client.getJson(null, "recipes", "//other", STRING));
        assertThrows(NullPointerException.class, () -> client.getJson(null, "recipes", "/", null));
    }

    private ServiceClient constant(Response response) {
        return new ServiceClient(directory, (context, target, method, headers, body) -> response, JsonLimits.DEFAULT);
    }

    private static Response json(int status, String body) {
        return new Response(status, new Headers().add("Content-Type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
