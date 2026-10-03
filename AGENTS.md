# Working on Hoori Micro

- Read README.md and docs/architecture.md. This is a separate consumer repository;
  do not move Dahemm migration into the Hoori VM MS0–MS5 roadmap.
- Use the real `hoori-http-api` / `hoori-rest-api`. Do not replace networking with
  java.net.http, Spring, Servlet or test stubs. Do not copy the SDK implementation.
- Keep dependencies, actions, codecs and ownership explicit. No annotation scanning,
  DI container, ORM, message broker or general proxy. The action registry, broker and
  gateway stay limited to the model in docs/architecture.md; the registry is never
  on the request path. Published gateway routes require http() + requirePermission().
- Never invent API signatures or mark native acceptance complete from HotSpot tests.
  Keep hoori.lock.json and docs/validation.md honest. Prefer the smallest relevant test.
- Do not create a client/pool per request. Do not automatically replay writes,
  follow redirects, forward credentials, or derive metric labels from request data.
- Optional data examples use the original Transaction/JDBC/Jdbi SDKs and a stable
  manager. Keep remote preparation outside short local transactions; never share
  handles with children or report UNKNOWN/COMMITTED as rollback. Keep DB and
  build-time processor dependencies out of the HTTP core.
- Hoori's guest classlib is partial (no String.repeat, Long.toHexString,
  Double.isInfinite, Math.floorMod). Check new JDK calls against it; run guest checks.
- `HttpServer.run()` ending is not proof of a completed drain. Preserve shutdown
  ordering: stop admission (and deregister), drain handlers, close outbound clients.
- Hoori currently uses cooperative guest execution. No assumptions of CPU preemption
  or arbitrary JDK compatibility. Use explicit TaskContext keys for managed execution;
  do not add ThreadLocal request context. The optional upstream annotation processor
  runs only at build time; generated delegates are wired explicitly, never scanned.
- Run scripts/test-core.sh and Python unit checks for portable changes. For transport,
  lifecycle, API or dependency changes run scripts/build.sh, test-hoori-core.sh and
  scripts/smoke.py using the real pinned distribution. Report unrun checks explicitly.
- Demos have no production authorization or persistence design; the optional data
  example owns only its demo database. Never present them as migrated Dahemm business
  services. Do not log credentials, bodies or raw upstream failures.
- Keep docs short and remove obsolete instructions when behavior changes. No invented
  performance wins or unnecessarily broad governance/test matrices.
