# Architecture (internal)

Design invariants and their reasons, for people changing the framework. How to *use* Hoori Micro
is documented in the manual (`pages/content/docs`); keep user-facing material there.

Hoori Micro is an independent consumer of the original Hoori SDKs. A finite constructor graph
generated at build time replaces technical registration in application code.

## Build and bootstrap

- One `@MicroApplication` per Maven module. The Micro processor resolves the graph and generates
  `<Application-FQN>MicroModule`; `Micro.run` loads exactly that class by name. No runtime scans,
  scope language, reflective field injection or general proxies.
- Controller/advice and DTO/validation adapters come from Hoori's MVC processor; clients use its
  shared HTTP/DTO model. Avaje is the starter's validation provider; the HTTP core depends only
  on the neutral SPI. Original processors run only at build time.
- Library modules export components via `@MicroModule` and are imported explicitly. Metadata stays
  in the JAR; foreign JARs are never searched.
- Graph bounds: 128 components/beans, 16 owned closeable resources. Owned `AutoCloseable`
  singletons close once, in reverse creation order. A bootstrap failure cleans up created
  resources before listeners, readiness or registration start.

## Request boundary

- Every business request passes the same incoming admission and `RequestScopes`/`Executions`
  boundary. The HTTP SDK has already read the bounded raw body; DTO binding, validation and
  business work start only after admission. No second request root in the MVC adapter, no
  `ThreadLocal` context.
- `TaskContext` carries correlation, origin and remaining budget. The mutable raw request stays
  with the HTTP owner. A reused client reads the current context on every call; calls outside a
  Micro boundary fail before encoding and network.
- Outgoing admission covers encoding, the actual exchange and decoding. One data pool per app,
  plus a separate control connection without an extra queue. No per-request clients, automatic
  write replays, redirects or credential forwarding.
- `X-Hoori-Budget-Ms` is written in the original before-write hook, i.e. after pool/DNS/connect/TLS
  waiting. It is a relative remaining budget, not a global real-time deadline and not a remote
  cancellation protocol.
- Failure priority: admission, cancellation, deadline and mandatory cleanup win over business
  advice. The response is released only after the local child/resource drain. Hoori is
  cooperative; non-cooperating CPU work cannot be preempted arbitrarily.

## Discovery and gateway

- The catalog holds service, major version, instance ID, checked origin and at most 128 HTTP
  endpoints per instance (method, template, consumes/produces, optional permission). OpenAPI apps
  add operation hashes plus contract hash and API group per instance; schemas stay with the
  service. The HTTP key is independent of Java method names and request data.
- Registry over HTTP/JSON (`/_hoori/instances/{id}`, `/lease`, `/_hoori/catalog`). TTL, epochs,
  revisions and the `complete` marker bound re-registration and restarts; unchanged
  epoch/revision/view confirm with 204. Filters: `none`, `public`, `services=name:version,...`
  (max. 32). The registry catalog and its answers stay bounded, also before filtering.
- The request path uses only a local immutable snapshot. Client selection is by service, version
  and exact endpoint contract. Providers check `X-Hoori-Endpoint`/`X-Hoori-Version`; mismatch is
  421 without retry. Empty/expired views never offer a wrong substitute instance.
- The registrar prepares routing and catalog together (max. 256 distinct public routes, 64 KiB
  metadata). Conflicts withhold both definitions; overflowing updates do not extend the old view.
  Unchanged confirmations reuse the frozen routing.
- Gateway and providers use the same SDK router. Method, raw path/query and bounded body stay
  separate and unchanged. Only `Accept` and `Content-Type` are forwarded. Known bounded
  validation/problem errors are checked and re-serialized; an inner remote failure is never
  presented as validation of the outer input.
- OpenAPI routes must also match permission, API group and operation hash. The publication
  manifest is built from the same snapshot. The optional documentation process fetches
  hash-bound artifacts through its own client outside requests and the heartbeat and replaces the
  aggregate only after a full match and a second snapshot comparison.

## Parallel work and optional data

- Client methods are synchronous; parallelism is explicit via the original `Tasks` API. The
  original `@GenerateTasks` processor generates facades; the app graph injects them.
- `@TaskScoped`/`@Transactional` decoration applies only to supported public interfaces with
  exactly one implementation; transactions need exactly one stable manager. Concrete and
  self-invoked methods get no interception.
- In the data example, remote reads are prepared before the local transaction; data and outbox
  commit together before response encoding. Handles are owner-bound; children never inherit them.
  `UNKNOWN` and `COMMITTED` are preserved, never treated as rollback or retried. DB/driver/Jdbi
  JARs belong only to the optional runtime classpath.

## Shutdown

Stop sets unready and closes admission; deregistration runs independently. Admitted requests may
use existing slots; waiting/new work is rejected; after the grace period cancellation is
triggered. Only after the actual request/child/HTTP drain do app resources and both clients
close. `HttpServer.run()` returning is no proof of drain, and neither is a forced kill.

## Out of scope

Registry and internal endpoints are unauthenticated in this demo. No general proxy engine, ORM,
runtime DI/AOP, event broker, Spring compatibility or Dahemm migration. See
[roadmap.md](roadmap.md).
