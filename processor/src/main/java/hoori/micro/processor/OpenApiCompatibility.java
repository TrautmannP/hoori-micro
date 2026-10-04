package hoori.micro.processor;

import hoori.micro.openapi.OpenApiDocument;
import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative, reviewable baseline gate: existing wire contracts are immutable within a major. */
final class OpenApiCompatibility {
    private OpenApiCompatibility() {}

    static void check(OpenApiDocument before, OpenApiDocument after) {
        OpenApiDocument.require(after.serviceVersion() >= before.serviceVersion(), "OpenAPI major version decreased");

        if (after.serviceVersion() > before.serviceVersion()) return;

        OpenApiDocument.require(before.group().equals(after.group()), "OpenAPI API group changed within a major");
        Map<String, OpenApiDocument.Operation> operations = new LinkedHashMap<>();
        for (var operation : after.operations()) operations.put(operation.key(), operation);
        for (var previous : before.operations()) {
            var next = operations.get(previous.key());
            OpenApiDocument.require(
                    next != null
                            && previous.id().equals(next.id())
                            && previous.hash().equals(next.hash()),
                    "OpenAPI baseline changed at " + previous.key()
                            + "; preserve the contract or increase the service major version");
        }
    }
}
