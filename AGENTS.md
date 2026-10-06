# Working on Hoori Micro

- Read README.md, docs/architecture.md and the relevant manual pages in
  pages/content/docs. This is a separate consumer repository;
  do not move Dahemm migration into the Hoori VM MS0–MS5 roadmap.
- Use the real `hoori-http-api` / `hoori-rest-api`. Do not replace networking with
  java.net.http, Spring, Servlet or test stubs. Do not copy the SDK implementation.
- Generate the finite application constructor graph at build time. Use the original
  Hoori MVC adapters and Jakarta constraints; no runtime bean scanning, general DI/AOP
  container, ORM, message broker or general proxy. The registry is never on the
  request path. Published controller endpoints require @GatewayRoute(permission=...).
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
  do not add ThreadLocal request context. Original upstream processors run only at
  build time; the generated application graph constructs delegates, never scans them.
- Run scripts/test-core.sh and Python unit checks for portable changes. For transport,
  lifecycle, API or dependency changes run scripts/build.sh, test-hoori-core.sh and
  scripts/smoke.py using the real pinned distribution. Report unrun checks explicitly.
- Demos have no production authorization or persistence design; the optional data
  example owns only its demo database. Never present them as migrated Dahemm business
  services. Do not log credentials, bodies or raw upstream failures.
- User-facing docs live only in the manual (pages/content/docs, English MDX); docs/ is
  internal (architecture invariants, validation, benchmarks, source baseline, roadmap).
  Update the matching manual page in the same change as any API, annotation, config,
  endpoint, metric or limit change. Example READMEs only point to the manual. Run
  `cd pages && npm run build` for manual changes.
- Write everything in English. Keep docs short and remove obsolete instructions when
  behavior changes. No invented performance wins or unnecessarily broad governance/test
  matrices.
