package hoori.micro;

import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpServer;
import hoori.http.Limits;
import hoori.http.Response;
import hoori.rest.Router;
import hoori.rest.json.JsonLimits;
import hoori.runtime.Shutdown;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Explicit startup composition on Hoori's cooperative single carrier. run() owns draining and pool
 * cleanup. Configure routes/dependencies before run(), never in handlers.
 */
public final class Microservice implements AutoCloseable {

  private final ServiceConfig config;
  private final Router router = new Router();
  private final HttpClient http;
  private final ServiceClient client;
  private final JsonLimits jsonLimits;

  private volatile HttpServer server;
  private volatile boolean closed, stopRequested, applicationReady = true;

  private boolean started;
  private Thread signalWatcher;

  private Microservice(ServiceConfig config, ServiceDirectory directory) {
    this.config = config;
    jsonLimits = new JsonLimits(64, 16384, 128, config.bodyBytes);
    http =
        new HttpClient(
            limits(config.clientConnections, config.clientTimeoutMillis),
            config.clientPerOrigin,
            config.clientIdleMillis);
    client = new ServiceClient(directory, http, jsonLimits);
    router.get("/health/live", request -> health(isLive()));
    router.get("/health/ready", request -> health(isReady()));
    router.get(
        "/metrics",
        request ->
            new Response(
                200,
                new Headers().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8"),
                server.metrics().prometheus().getBytes(StandardCharsets.UTF_8)));
    router.onError(
        (request, failure) -> {
          // Intentionally no stacktrace, URI, request/response body or secret in logs/errors.
          String id = request == null ? "unavailable" : request.id();
          System.err.println(
              "request_failed service="
                  + config.name
                  + " request_id="
                  + id
                  + " type="
                  + failure.getClass().getName());
          if (failure instanceof ServiceCallException)
            return Response.text(502, "Upstream service unavailable");
          if (failure instanceof InterruptedIOException)
            return Response.text(503, "Request interrupted or expired");
          return Response.text(500, "Internal Server Error");
        });
  }

  public static Microservice create(String defaultName, String... dependencies) {
    return create(defaultName, Environment.system(), dependencies);
  }

  public static Microservice create(String defaultName, Environment env, String... dependencies) {
    return new Microservice(
        ServiceConfig.from(defaultName, env), ServiceDirectory.from(env, dependencies));
  }

  public Router routes() {
    return router;
  }

  public ServiceClient client() {
    return client;
  }

  public ServiceConfig config() {
    return config;
  }

  public JsonLimits jsonLimits() {
    return jsonLimits;
  }

  /** Local application readiness, not an automatic recursive dependency health check. */
  public void ready(boolean value) {
    applicationReady = value;
    HttpServer current = server;
    if (current != null) current.setReady(value);
  }

  public boolean isLive() {
    return server != null && server.isLive();
  }

  public boolean isReady() {
    return server != null && server.isReady();
  }

  /** Stop admission; run() drains dispatched requests before closing the outbound pool. */
  public void stop() {
    stopRequested = true;
    HttpServer current = server;
    if (current != null) current.stopAccepting();
  }

  public void run() throws IOException {
    if (started || closed || stopRequested)
      throw new IllegalStateException("Service already run or closed");
    started = true;
    try {
      server =
          new HttpServer(
              config.bindAddress,
              config.port,
              limits(config.serverConnections, config.requestTimeoutMillis),
              router.freeze());
      server.setReady(applicationReady);
      signalWatcher =
          new Thread(
              () -> {
                try {
                  while (!closed && !isLive()) Thread.sleep(1);
                  while (!closed && !stopRequested && !Shutdown.requested()) Thread.sleep(50);
                  if (!closed) stop();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
      signalWatcher.setDaemon(true);
      signalWatcher.start();
      System.out.println("service_starting name=" + config.name + " port=" + config.port);
      server.run();
    } finally {
      try {
        // HttpServer.run returns when admission stops, NOT when handlers have drained.
        // Only the owner thread drains; the signal watcher must NOT close the client.
        if (server != null) {
          boolean drained = server.shutdown(config.shutdownGraceMillis);
          System.out.println("service_stopped name=" + config.name + " drained=" + drained);
        }
      } finally {
        close();
      }
    }
  }

  /** Immediate abort/cleanup. Prefer stop() while run() is active for graceful shutdown. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    stopRequested = true;
    if (server != null) server.close();
    http.close();
    if (signalWatcher != null && signalWatcher != Thread.currentThread()) signalWatcher.interrupt();
  }

  private Limits limits(int connections, int timeout) {
    return new Limits(64, 16384, config.bodyBytes, connections, 100, timeout);
  }

  private static Response health(boolean up) {
    return Response.text(up ? 200 : 503, up ? "UP" : "DOWN");
  }
}
