package hoori.micro;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.rest.json.Json;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import hoori.rest.mvc.Problem;
import java.util.Map;

/** Finite response metadata and public error envelopes; peer diagnostics remain private. */
final class HttpResponses {
    private static final JsonLimits PROBLEM_LIMITS = new JsonLimits(1, 32, 1, 128);

    static String problem(Response response) {
        if (response.status < 400
                || response.status > 499
                || response.status == 421
                || response.body.length > 128
                || !ServiceBroker.jsonContentType(response.headers)) return null;

        try {
            Object body = Json.decode(response.body, JsonTree.CODEC, PROBLEM_LIMITS);

            if (body instanceof Map<?, ?> fields && fields.size() == 1 && fields.get("code") instanceof String code)
                return new Problem(response.status, code).code();
        } catch (JsonException | IllegalArgumentException invalid) {
            /* Unknown peer payload. */
        }

        return null;
    }

    static boolean resultHeader(String name) {
        return name.equalsIgnoreCase("Location")
                || name.equalsIgnoreCase("ETag")
                || name.equalsIgnoreCase("Cache-Control")
                || name.equalsIgnoreCase("Vary")
                || name.equalsIgnoreCase("Retry-After")
                || name.equalsIgnoreCase("Last-Modified");
    }

    static Headers headers(Headers input) {
        Headers output = new Headers();
        for (int i = 0; i < input.size(); i++) {
            String name = input.name(i), value = input.value(i);

            if (!(resultHeader(name) || name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Allow")))
                continue;

            if (output.get(name) != null) throw new IllegalArgumentException("Duplicate response metadata");

            if (name.equalsIgnoreCase("Location") && !safeLocation(value)) continue;

            if (name.equalsIgnoreCase("Allow") && !safeAllow(value)) continue;

            output.add(name, value);
        }

        return output;
    }

    static boolean safeAllow(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;

        for (String method : value.split(",", -1))
            if (!java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                    .contains(method.trim())) return false;

        return true;
    }

    private static boolean safeLocation(String value) {
        if (!value.startsWith("/") || value.startsWith("//") || value.length() > 8192) return false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c <= 32 || c >= 127 || c == '\\') return false;
        }

        return true;
    }
}
