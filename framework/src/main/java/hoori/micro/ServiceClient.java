package hoori.micro;

import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.Request;
import hoori.http.Response;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;

/**
 * Named HTTP calls over an application-owned Hoori pool. No retries, redirects, ambient identity,
 * ThreadLocal context or peer-body-to-public-error forwarding.
 */
public final class ServiceClient {
  @FunctionalInterface
  interface Exchange {
    Response send(Request context, URI target, String method, Headers headers, byte[] body)
        throws IOException;
  }

  private static final byte[] EMPTY = new byte[0];
  private final ServiceDirectory directory;
  private final Exchange exchange;
  private final JsonLimits jsonLimits;

  /** The caller owns and closes http, usually through Microservice. */
  public ServiceClient(ServiceDirectory directory, HttpClient http, JsonLimits jsonLimits) {
    this(directory, adapter(http), jsonLimits);
  }

  // Package-private seam for deterministic contract tests, not an alternative production transport.
  ServiceClient(ServiceDirectory directory, Exchange exchange, JsonLimits jsonLimits) {
    if (directory == null || exchange == null || jsonLimits == null)
      throw new NullPointerException();
    this.directory = directory;
    this.exchange = exchange;
    this.jsonLimits = jsonLimits;
  }

  private static Exchange adapter(HttpClient http) {
    if (http == null) throw new NullPointerException("http");
    return (context, target, method, headers, body) ->
        context == null
            ? http.exchange(target, method, headers, body)
            : http.exchange(context, target, method, headers, body);
  }

  /**
   * context may be null for startup/background calls. Otherwise pass request.raw(). Only explicitly
   * supplied headers and Hoori's request ID are sent. Raw HTTP statuses remain visible to callers;
   * typed JSON helpers require a successful JSON response.
   */
  public Response exchange(
      Request context,
      String service,
      String method,
      String pathAndQuery,
      Headers headers,
      byte[] body)
      throws IOException {
    URI target = directory.target(service, pathAndQuery);
    if (method == null || headers == null || body == null) throw new NullPointerException();
    try {
      return exchange.send(context, target, method, headers, body);
    } catch (InterruptedIOException cancelledOrExpired) {
      // Keep the SDK's cancellation/timeout semantics and interrupt status intact.
      throw cancelledOrExpired;
    } catch (IOException failed) {
      throw new ServiceCallException(service, 0, "transport failed", failed);
    }
  }

  public <T> T getJson(Request context, String service, String target, JsonCodec<T> codec)
      throws IOException {
    if (codec == null) throw new NullPointerException("codec");
    Response response =
        exchange(
            context,
            service,
            "GET",
            target,
            new Headers().add("Accept", "application/json"),
            EMPTY);
    return decode(service, response, codec);
  }

  public <I, O> O postJson(
      Request context,
      String service,
      String target,
      I value,
      JsonCodec<I> requestCodec,
      JsonCodec<O> responseCodec)
      throws IOException {
    if (value == null || requestCodec == null || responseCodec == null)
      throw new NullPointerException();

    byte[] body = Json.encode(value, requestCodec, jsonLimits);
    Response response =
        exchange(
            context,
            service,
            "POST",
            target,
            new Headers().add("Accept", "application/json").add("Content-Type", "application/json"),
            body);

    return decode(service, response, responseCodec);
  }

  private <T> T decode(String service, Response response, JsonCodec<T> codec)
      throws ServiceCallException {

    if (response.status < 200 || response.status >= 300) {
      throw new ServiceCallException(service, response.status, "unexpected HTTP status", null);
    }

    if (!jsonContentType(response.headers)) {
      throw new ServiceCallException(service, response.status, "expected application/json", null);
    }

    try {
      T result = Json.decode(response.body, codec, jsonLimits);

      if (result == null) {
        throw new JsonException("Null JSON response");
      }

      return result;
    } catch (JsonException invalid) {
      // Do not retain decoder messages: a custom codec may include peer data there.
      throw new ServiceCallException(service, response.status, "invalid JSON response", null);
    }
  }

  private static boolean jsonContentType(Headers headers) {
    String type = null;

    for (int i = 0; i < headers.size(); i++) {

      if (headers.name(i).equalsIgnoreCase("Content-Type")) {

        if (type != null) {
          return false;
        }

        type = headers.value(i).trim();
      }
    }

    if (type == null) {
      return false;
    }

    int semicolon = type.indexOf(';');

    if (semicolon < 0) {
      return type.equalsIgnoreCase("application/json");
    }

    if (!type.substring(0, semicolon).trim().equalsIgnoreCase("application/json")) {
      return false;
    }

    String parameter = type.substring(semicolon + 1).trim();
    int equals = parameter.indexOf('=');

    if (equals < 0 || !parameter.substring(0, equals).trim().equalsIgnoreCase("charset")) {
      return false;
    }

    String charset = parameter.substring(equals + 1).trim();

    return charset.equalsIgnoreCase("utf-8") || charset.equalsIgnoreCase("\"utf-8\"");
  }
}
