package hoori.micro;

import hoori.rest.Router;
import java.util.List;

/** A finite HTTP contract. Keys never depend on Java method names or request values. */
public record HttpEndpoint(String method, String path, String consumes, String produces) {
    public HttpEndpoint {
        if (method == null
                || !List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                        .contains(method)
                || path == null
                || path.length() > 256
                || path.startsWith("/_hoori")
                || !("".equals(consumes) || "application/json".equals(consumes))
                || !("".equals(produces) || "application/json".equals(produces)))
            throw new IllegalArgumentException("Unsupported endpoint contract");

        Router.pathParameters(method, path);
    }

    public String key() {
        return method + " " + path + " " + (consumes.isEmpty() ? "-" : consumes) + " "
                + (produces.isEmpty() ? "-" : produces);
    }
}
