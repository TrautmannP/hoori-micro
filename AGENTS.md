# Working on Hoori Micro

- Read README.md and docs/architecture.md. This is a separate consumer repository;
  do not move Dahemm migration into the Hoori VM MS0–MS5 roadmap.
- Use the real `hoori-http-api` / `hoori-rest-api`. Do not replace networking with
  java.net.http, Spring, Servlet or test stubs. Do not copy the SDK implementation.
- Keep dependencies, routes, codecs and ownership explicit. No annotation scanning,
  DI container, registry, broker, ORM or general proxy without a concrete requirement.
- Never invent API signatures or mark native acceptance complete from HotSpot tests.
  Keep hoori.lock.json and docs/validation.md honest. Prefer the smallest relevant test.
- Do not create a client/pool per request. Do not automatically replay writes,
  follow redirects, forward credentials, or derive metric labels from request data.
- `HttpServer.run()` ending is not proof of a completed drain. Preserve shutdown
  ordering: stop admission, drain handlers, close outbound clients.
- Hoori currently uses cooperative guest execution. No assumptions of CPU preemption
  or arbitrary JDK compatibility. Do not add ThreadLocal request context.
- Run scripts/test-core.sh and Python unit checks for portable changes. For transport,
  lifecycle, API or dependency changes run scripts/build.sh, test-hoori-core.sh and
  scripts/smoke.py using the real pinned distribution. Report unrun checks explicitly.
- Demos have no production authorization/persistence. Never present them as migrated
  Dahemm business services. Do not log credentials, bodies or raw upstream failures.
- Keep docs short and remove obsolete instructions when behavior changes. No invented
  performance wins or unnecessarily broad governance/test matrices.
