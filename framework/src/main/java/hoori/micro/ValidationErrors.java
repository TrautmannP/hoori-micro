package hoori.micro;

import hoori.http.Response;
import hoori.rest.Responses;
import hoori.rest.json.Json;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import hoori.rest.validation.ValidatedBody;
import hoori.validation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The only upstream error envelope that Micro accepts; all other peer bodies stay private. */
final class ValidationErrors {
    private static final JsonLimits LIMITS = new JsonLimits(3, 256, 32, ValidatedBody.MAX_ERROR_BYTES);

    static List<Violation> read(Response response) {
        if (response.status != 400
                || !ServiceBroker.jsonContentType(response.headers)
                || response.body.length > ValidatedBody.MAX_ERROR_BYTES) return List.of();

        try {
            Object decoded = Json.decode(response.body, JsonTree.CODEC, LIMITS);

            if (!(decoded instanceof Map<?, ?> envelope)
                    || envelope.size() != 2
                    || !"validation_failed".equals(envelope.get("code"))
                    || !(envelope.get("violations") instanceof List<?> failures)
                    || failures.isEmpty()
                    || failures.size() > 32) return List.of();

            List<Violation> safe = new ArrayList<>();
            for (Object failure : failures) {
                if (!(failure instanceof Map<?, ?> fields)
                        || fields.size() != 2
                        || !(fields.get("path") instanceof String path)
                        || !(fields.get("code") instanceof String code)) return List.of();

                safe.add(new Violation(path, code));
            }

            return List.copyOf(safe);
        } catch (JsonException | IllegalArgumentException invalid) {
            return List.of();
        }
    }

    static Response response(List<Violation> violations) {
        List<Object> fields = new ArrayList<>();
        for (Violation violation : violations) fields.add(Map.of("path", violation.path(), "code", violation.code()));

        return Responses.json(400, Map.of("code", "validation_failed", "violations", fields), JsonTree.CODEC, LIMITS);
    }
}
