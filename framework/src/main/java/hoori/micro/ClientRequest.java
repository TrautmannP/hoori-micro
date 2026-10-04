package hoori.micro;

import hoori.http.Headers;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonLimits;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Encoding support for generated clients; constructed only after outgoing admission. */
public final class ClientRequest {
    private static final byte[] EMPTY = new byte[0];
    private String path;
    private StringBuilder query;
    final Headers headers = new Headers();
    byte[] body = EMPTY;

    public ClientRequest(String template) {
        this.path = template;
    }

    public ClientRequest path(String name, Object value) {
        if (value == null || value.toString().isEmpty()) throw new IllegalArgumentException("Required path value");

        path = path.replace("{" + name + "}", encode(value));
        bounded();

        return this;
    }

    public ClientRequest query(String name, Object value, boolean required) {
        if (value == null) {
            if (required) throw new IllegalArgumentException("Required query value");

            return this;
        }

        if (query == null) query = new StringBuilder();

        query.append(query.length() == 0 ? '?' : '&')
                .append(encode(name))
                .append('=')
                .append(encode(value));
        bounded();

        return this;
    }

    public ClientRequest header(String name, Object value, boolean required) {
        if (!allowedHeader(name)) throw new IllegalArgumentException("Header is reserved or contains credentials");

        if (value == null) {
            if (required) throw new IllegalArgumentException("Required header value");

            return this;
        }

        headers.add(name, text(value));

        return this;
    }

    public <T> ClientRequest body(T value, JsonCodec<T> codec, JsonLimits limits) {
        if (value == null) throw new NullPointerException("body");

        body = Json.encode(value, codec, limits);

        return this;
    }

    public static boolean allowedHeader(String name) {
        return !(name.equalsIgnoreCase("Host")
                || name.equalsIgnoreCase("Connection")
                || name.equalsIgnoreCase("Content-Length")
                || name.equalsIgnoreCase("Transfer-Encoding")
                || name.equalsIgnoreCase("Authorization")
                || name.equalsIgnoreCase("Proxy-Authorization")
                || name.equalsIgnoreCase("Cookie")
                || name.equalsIgnoreCase("Set-Cookie")
                || name.equalsIgnoreCase("Upgrade")
                || name.equalsIgnoreCase("TE")
                || name.equalsIgnoreCase("Trailer")
                || name.equalsIgnoreCase("Keep-Alive")
                || name.equalsIgnoreCase("Proxy-Connection")
                || name.equalsIgnoreCase("X-Request-ID")
                || name.regionMatches(true, 0, "X-Hoori-", 0, 8));
    }

    String target() {
        if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0 || !path.startsWith("/"))
            throw new IllegalArgumentException("Unbound client template");

        return query == null ? path : path + query;
    }

    private void bounded() {
        if (path.length() + (query == null ? 0 : query.length()) > 8192)
            throw new IllegalArgumentException("Client target limit");
    }

    private static String text(Object value) {
        return value instanceof Enum<?> enumeration ? enumeration.name() : value.toString();
    }

    private static String encode(Object value) {
        String text = text(value);

        if (text.length() > 8192) throw new IllegalArgumentException("Client parameter limit");

        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
