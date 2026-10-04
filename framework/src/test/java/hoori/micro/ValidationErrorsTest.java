package hoori.micro;

import static org.junit.jupiter.api.Assertions.*;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.validation.Violation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ValidationErrorsTest {
    @Test
    void acceptsOnlyTheBoundedProtocolAndKeepsNestedFailuresUpstream() {
        List<Violation> fields = List.of(new Violation("ids[0]", "positive"));
        Response valid = ValidationErrors.response(fields);
        assertEquals(fields, ValidationErrors.read(valid));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ValidationErrors.read(valid).clear());
        var failure = new ServiceCallException(
                "recipes",
                400,
                "unexpected HTTP status",
                null,
                new HttpEndpoint("GET", "/recipes/{id}", "", "application/json"),
                fields,
                null);
        assertEquals("/recipes/{id}", failure.endpoint().path());
        assertEquals(502, Failures.classify(failure).status());
        assertFalse(failure.getMessage().contains("positive"));
        for (String body : List.of(
                "not json",
                "{}",
                "{\"code\":\"validation_failed\",\"violations\":[]}",
                "{\"code\":\"validation_failed\",\"violations\":[{\"path\":\"id\",\"code\":\"positive\",\"value\":\"SECRET\"}]}",
                "{\"code\":\"validation_failed\",\"violations\":[{\"path\":\"/private\",\"code\":\"positive\"}]}",
                "{\"code\":\"validation_failed\",\"violations\":[{\"path\":\"id\",\"code\":\"SECRET message\"}]}",
                "{\"code\":\"validation_failed\",\"violations\":[{\"path\":\"id\",\"code\":\"positive\"}],\"message\":\"SECRET\"}",
                new String(valid.body, StandardCharsets.UTF_8) + " true"))
            assertTrue(ValidationErrors.read(json(400, body)).isEmpty(), body);
        assertTrue(ValidationErrors.read(new Response(500, valid.headers, valid.body))
                .isEmpty());
        assertTrue(ValidationErrors.read(new Response(400, new Headers(), valid.body))
                .isEmpty());
        assertTrue(ValidationErrors.read(new Response(400, valid.headers, new byte[12289]))
                .isEmpty());
        String item = "{\"path\":\"id\",\"code\":\"positive\"}";
        assertEquals(
                32,
                ValidationErrors.read(json(400, envelope(String.join(",", java.util.Collections.nCopies(32, item)))))
                        .size());
        assertTrue(ValidationErrors.read(json(400, envelope(String.join(",", java.util.Collections.nCopies(33, item)))))
                .isEmpty());
    }

    private static String envelope(String items) {
        return "{\"code\":\"validation_failed\",\"violations\":[" + items + "]}";
    }

    private static Response json(int status, String body) {
        return new Response(
                status, new Headers().add("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8));
    }
}
