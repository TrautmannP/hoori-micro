# Historical acceptance before the MVC switch

The following results belong exclusively to the old pins/APIs named in each section. Current
evidence is in [validation.md](../validation.md).

## Annotation-based DTOs (4 October 2026)

- `scripts/build.sh`: **25 JUnit tests and Spotless** passed; code generation by the original
  Hoori/Avaje processors. Both optional examples build.
- Portable checks: **117 assertions, 5 formatter fixtures, 24 Python tests**. External runtime
  JARs must match the SHA256 pins exactly; additional processor/foreign JARs are rejected during
  staging.
- `scripts/test-hoori-core.sh`: **interpreter/mixed passed**. The generated DTO checks run
  additionally with GC stress each and without JDK/processor on the runtime path: number strings,
  required fields, types, duplicates, overflow, list limit, safe `id`/`ids[n]` errors and unchanged
  JSON results.
- `test_composition.py --facade`: **both engines passed**. Direct RPCs and the gateway deliver
  bounded field errors. Invalid inputs reach no handler and start no fan-out. Broken validators
  stay 500; failures of an inner call stay 502 outside. Task facade, budgets, cancellation and
  drain pass.
- `test_data.py`: **both engines passed**, with real PostgreSQL and the original fault peers
  matching the pin. Invalid draft IDs start neither remote reads nor DB acquisition.
  Commit/rollback/UNKNOWN, cancel and recovery pass.
- `scripts/smoke.py`: **both engines passed**. The containers run real Hoori services without
  JDK/Maven; rolling updates, registry outage, saturation, catalog changes, recovery and SIGTERM
  drain pass.
- Receipt SHA256: `0d5421b2a679ec5c420a71484edc76e62831822f87df95b7fc8b85f33e753d4c`.
  Pin, runtime/app JAR hashes and check evidence: [DTO acceptance](annotated-dtos.json).

This is a bounded DTO profile and a local consumer acceptance. The following tasks v2 evidence
and performance measurements stem from the earlier pin `7d7245a`; they were not blanket
re-qualified by this upgrade. In particular, `test-task-runtime.sh`, `test_admission.py`,
`test_budgets.py`, `test_control.py`, `test_tasks.py` and the performance benchmarks were not
repeated separately for this pin.

## Tasks v2 foundation (#19)

- `scripts/build.sh`: Maven/Spotless and **22 JUnit tests** passed.
- `scripts/test-core.sh`: **116 assertions and 5 formatter fixtures**; Python `unittest`:
  **20 tests**, including POM mixing, runtime binding, cache isolation and unchanged staging.
- `scripts/test-task-runtime.sh`: independent SDK consumer with real RequestScopes and parallel
  HttpTasks passed in **interpreter/mixed**. Compiled without Micro classes; executed with
  `PATH`/`JAVA_HOME=/nonexistent` and exactly the five runtime JARs from the lock, without
  annotations/processor/DB.
- `scripts/test-hoori-core.sh` and `scripts/smoke.py`: **both engines passed**. The images start
  registry, gateway and business services; real DNS/HTTP calls, saturation, rolling/catalog
  changes, recovery and SIGTERM drain pass. Raw data:
  [mixed](../benchmarks/issue-19-smoke-mixed.json.gz),
  [interpreter](../benchmarks/issue-19-smoke-interpreter.json.gz).
- Receipt SHA256: `8c6d4340bc272b9d73f18972585ae55a8f364df8d77e993a1e9752e32c585e23`. The image
  contains the complete original distribution with 13 SDKs plus guest base; loaded are guest
  base, HTTP, REST, Concurrent, Concurrent HTTP. No JDK/Maven in the image, no additional
  runtime/VM package format.

## Managed HTTP core (#20–#22, #24–#25)

- Maven/Spotless: **23 JUnit tests**, including unchanged failure causes and
  COMMITTED/ROLLED_BACK/UNKNOWN classification. Portable checks:
  **117 assertions, 5 formatter fixtures, 20 Python tests**.
- `test-hoori-core.sh`: core, admission and TaskChecks on real HooriVM; cancellation before
  registration, cancel/permit races, 256 registration cycles, fail-fast failure priority and
  reusable specs/service contexts.
- `test_admission.py`, `test_budgets.py`, `test_control.py`: **interpreter/mixed passed**. Still
  real Hoori pools, decreasing serial budgets, validated wire values, separate control recovery
  and admission before codecs.
- `test_tasks.py`: **interpreter/mixed passed**. Keep-alive and overlapping requests keep their
  correlation; no credentials/raw requests in children. Specs created in advance read the current
  context and start no codec beforehand. Controlled pool/body waits cancel individually;
  concurrent and later calls on the same client work. Both parallel calls start before the
  responses are released. Fail-fast keeps the business error; settled does not swallow a global
  deadline. Batch order/parallelism limit, child capacity, body/child/encoder/cleanup failures and
  safe response texts are checked.
- A controlled finally gate keeps the root and incoming permit occupied although the local
  operation body has already returned. Health stays reachable; `TaskDiagnostics` shows READ/DRAIN.
  Response and resource release follow only after the gate opens. Afterwards
  roots/diagnostic references/waiters/permits are empty.
- SIGTERM: success within grace; a slow fan-out is cancelled locally after 400 ms grace, child
  finally and resources drain before client close. A delayed registry does not extend this
  deadline. Separately: root already finished, 4 MiB response to a slow receiver still open;
  transport keeps its remaining grace and then honestly reports `roots_drained=true drained=false`.
  Handler stop, repeated close, 32 start/stop interleavings and failures on an already occupied
  listener pass.
- Five load/failure/deadline cycles with explicit GC and idle in the same process: no retained
  request/body WeakReferences, no active roots/permits/waiters, empty diagnostics list; observed
  tasks/handles return to bounded values. This is targeted Micro ownership evidence, not a
  constant RSS promise or a renewed full upstream timer/VM/DNS/TLS acceptance.
- Negative case: non-cooperating code stays active beyond grace. The harness ends it externally;
  this run proves **no drain**, and the log does not claim one either. A socket abort proves no
  rollback of a remote write.

Raw data including fixture/framework hashes and GC samples:
[mixed](../benchmarks/tasks-core-mixed.json.gz),
[interpreter](../benchmarks/tasks-core-interpreter.json.gz). Additionally, the complete Docker
smoke passed in both engines: [mixed](../benchmarks/tasks-core-smoke-mixed.json.gz),
[interpreter](../benchmarks/tasks-core-smoke-interpreter.json.gz), including rolling/catalog
changes, saturation, registry outage and SIGTERM drain.

## Service composition (#23)

`test_composition.py` starts the actual demo definitions with test-local gates: registry,
recipes, pantry, shopping and gateway. **Interpreter/mixed pass**. Both providers begin before
their responses are released. Checked are typed results, correlation without credentials, local
fail-fast cancellation, successful empty versus unavailable dashboard data and unmasked global
admission. Five batch elements respect at most two calls and the input order; all-complete
processes all elements even after an item failure. The equivalent bulk action needs only one RPC.
SIGTERM drains the local fan-out. The remote provider may keep working independently after the
caller cancels.

Maven/Spotless (23 JUnit), core (117 assertions/5 formatter fixtures), Python (20) and guest core
pass. The extended Docker smoke with all five roles also passes in both engines. Its global
shopping limit of one call checks queues; overlap is proven separately by the native test with two
calls. Raw data: [composition mixed](../benchmarks/composition-mixed.json.gz),
[interpreter](../benchmarks/composition-interpreter.json.gz),
[Docker mixed](../benchmarks/composition-smoke-mixed.json.gz),
[interpreter](../benchmarks/composition-smoke-interpreter.json.gz).

## Optional facades (#26)

The separate Maven build `optional_example.py build task-facade` installs original
annotation/processor POMs from the same verified distribution set. It generates and packages
ordinary application classes; the HTTP reactor gets no additional dependencies. The small
formatter path is explicit for the separate module invocation; the formatting rules stay the same.

`test_composition.py --facade` passes in **interpreter/mixed**: real broker calls via discovery,
TaskSpec creation before any request boundary without delegate call, reuse with new correlation,
checked exceptions with finally completion, 800 ms method budget and a shorter 250 ms parent,
cancellation and subsequent recovery. The processes run with `PATH`/`JAVA_HOME=/nonexistent` and
without annotation/processor JARs on the classpath. Raw data:
[mixed](../benchmarks/composition-facade-mixed.json.gz),
[interpreter](../benchmarks/composition-facade-interpreter.json.gz). The generic upstream processor
matrix is not duplicated. Maven/Spotless (23 JUnit), core (117 assertions/5 formatter fixtures),
Python (21) and guest core pass; the unchanged HTTP classpath also starts in the repeated Docker
smoke in both engines: [mixed](../benchmarks/facade-smoke-mixed.json.gz),
[interpreter](../benchmarks/facade-smoke-interpreter.json.gz).

## Optional local data operations (#26/#27)

The independent `local-data` build uses original Transaction/JDBC/Jdbi POMs, pgJDBC 42.7.13 and
Jdbi Core 3.55.0. `StoreScoped` is generated by the same processor and receives the stable manager
explicitly. Both engines run without build annotations/processor/JDK/Maven on the runtime
classpath. The launcher additionally allows file read: the real driver needs the host time zone.
Pure HTTP processes still get only their previous SDK selection.

`test_data.py` passes in **interpreter/mixed**, with PostgreSQL 18.6 from the upstream digest and
unchanged database/fault peer helpers checked against `7d7245a`:

- Real invoke requests and both discovered remote providers; no DB acquire while the preparing
  remote reads wait at the controlled gate. Explicit and generated boundaries store record/outbox
  together; SQL constraints are also rolled back correctly through the generated delegate.
- Body, mandatory child, deadline and caught inner REQUIRED failures roll back. The child finally
  gate holds the connection until the actual end. Foreign children fail with lookup and retained
  handle before SQL; independently committed child data survive the parent rollback.
- Concurrent requests own different PostgreSQL backend PIDs. After a confirmed commit, data remain
  despite callback/encoding failures. An actually suppressed COMMIT ACK produces UNKNOWN, a single
  acquire and a physical discard without retry. Logs distinguish COMMITTED, ROLLED_BACK and
  UNKNOWN. An encoding failure after the end of the local boundary has no active transaction
  context; it does not falsely report rollback.
- Three query cancel/recovery cycles: PostgreSQL provably waits on the advisory lock, real driver
  cancellation reports SQLSTATE 57014, then rollback and close happen before return. Jdbi keeps
  the SQL error as the primary cause: safe 500, internally ROLLED_BACK. Subsequent requests work.
  After GC/idle zero open or retained test connections; observed eight active tasks and five
  handles.
- SIGTERM during the same real DB waiting work drains and rolls back; afterwards the service-wide
  test resource closes with zero active connections. The example uses no pool; an arbitrary pool
  integration is therefore not qualified. Acquisition can outlast the work deadline and is bounded
  by the documented finite driver limits.

Raw data with original JAR/fixture hashes and recovery samples:
[mixed](../benchmarks/data-mixed.json.gz), [interpreter](../benchmarks/data-interpreter.json.gz).
Maven/Spotless (23 JUnit), core (117 assertions/5 formatter fixtures), Python (23), guest core and
a repeated DB-free Docker smoke pass in both engines:
[mixed](../benchmarks/data-smoke-mixed.json.gz),
[interpreter](../benchmarks/data-smoke-interpreter.json.gz). The complete upstream
driver/TLS/GC matrix was not re-run here.

## Joint completion (#28)

The additional `CapacityChecks` pass in **interpreter/mixed** on the same release: two fully
occupied Micro roots, all 1024 SDK timer registrations and the VM limit with 1023 child tasks. An
additional Micro root fails before its business body in each case. Already admitted resources
drain, slots become free, diagnostics are empty and the same service accepts work again
afterwards. The timer test holds real nested deadlines; no private SDK hooks.
[Native output, runtime and fixture identities](../benchmarks/tasks-capacity-native.json.gz).

The final build with Spotless/23 JUnit, 117 core assertions, five formatter fixtures, 23 Python
tests and guest core passes. The native HTTP/composition/facade/DB checks documented above and the
last Docker smoke use the same unchanged framework JAR
(`6d0a4b06b558497cf20541381766b3ddbf74ee0c972ae89b9df0533b5b63f090`). For #28 only test fixtures,
benchmark tooling and documentation were added; the already passed complete matrices were not
duplicated again. The real Docker cost control is documented in
[benchmarks.md](../benchmarks.md#tasks-v2-3-october-2026). #8/#10/#11 remain independent open
work; tasks v2 is no production or performance approval.

## Previous business acceptance

The following previous business acceptance belongs to the set `d8906e6`, clean headless release
for `x86_64-unknown-linux-gnu`; VM, guest base and all SDK JARs from the same distribution. It does
not replace a renewed tasks v2 acceptance. Temurin 21.0.6, Maven 3.9.16, Docker 29.8.1, Compose
v5.5.1. Base image: amd64 digest from `docker/Dockerfile`, signed Debian package sources from
30 September 2026.

## Actually executed

| Check | Result | Limit of the statement |
|---|---|---|
| `scripts/build.sh` / `mvn clean verify`, Spotless | **22 JUnit tests passed** | Real SDK JARs; JUnit on HotSpot with a transport seam, no native network approval from it |
| `scripts/test-core.sh` | **116 assertions, 5 formatter fixtures passed** | Host JDK; names, configuration and bounds |
| Python `unittest` | **14 tests passed** | Distribution check, benchmark counting and bounded open load |
| `scripts/test-hoori-core.sh`, both engines | **passed** | Real guest core/admission check including interrupt, deadline, stop/close |
| `scripts/test_control.py`, both engines | **passed** | Separate control pool; repeated timeouts, re-registration and bounded deregistration |
| `scripts/test_admission.py`, both engines | **passed** | Admission before DTO/encoding, two load peaks, expiry without wire work, stop/drain and empty gates/pools |
| `scripts/test_budgets.py`, both engines | **passed** | Real SDK pool probe plus three native guest processes; relative budgets and prepared gateway snapshots |
| `scripts/smoke.py`, both engines | **passed** | Real images, three load peaks, rolling/catalog changes and outages under continuing business calls, resource measurement, cleanup |
| `scripts/benchmark.py`, both engines | **24 A–D runs complete; gateway p99 missed** | Three fresh processes per variant/engine, same set and limits; runtime/SDK control separate |
| D with 64 changing public routes / separate profiling | **complete** | Size control, natural GC and empty handles/completed tasks at exit; no performance approval |

## Budgets and gateway (#6/#7)

After 800 ms of controlled pool waiting, the 2000 ms wire value drops immediately before writing
the request; reused and new connections are checked separately. Gateway → Shopping → Recipes and
two serial recipe calls share the same deadline. Background contexts with a shorter budget work;
expired contexts start no further child call. Native checks confirm 400 for
malformed/duplicate/negative/oversized internal values and 504 for zero/expired budgets. External
budget/action/version headers do not extend the gateway policy; the request ID is preserved,
Authorization is not forwarded.

Cancellation releases its own SDK slot and the permit; other calls of the same client keep
working. Two saturation cycles deliver counted 200/504 and then normal responses without a process
restart. At the end all gates and pools are empty; all three processes end with exit 0 and
`drained=true`.

Routes are prepared together with catalog/revision before publication; requests also use this
snapshot for selection. Native checks cover changing/removing a route, denied permission,
contradicting rolling metadata, zero/invalid path parameters and count/metadata overflow. Valid
128/100 additional routes are accepted first; only the extension to more than 256 routes or 64 KiB
metadata fails. No partial update; the old snapshot ages out and converges without restart after
the excess is removed. JUnit additionally confirms unchanged array identity and atomic revisions.

## Joint image/load acceptance (#8/#10)

The smoke test uses 0.5 CPU, 256 MiB container memory and a 32 MiB logical guest heap per role.
Old/new recipe instances share the previous 0.5 CPU during the rolling check. Individual advertise
addresses and actions are checked; shopping/gateway keep container ID, image ID and start time.
Three saturation cycles across one registry TTL show bounded active/waiting calls, zero SDK pending
and living control leases. After 5.5 s of quiet each, heap/committed heap, RSS, cgroup including
file cache, tasks and handles are recorded.

Under continuing GETs, a new action is published, registry timeouts are triggered by pausing, and a
registry outage until catalog expiry and a new epoch are checked. SIGKILL of a provider removes its
registration only via TTL; subsequent re-creation and twice removing/publishing a route converge
without consumer/gateway restart. Errors are counted separately, in particular 502 during provider
changes and 404 after catalog expiry. There are no business retries and no promise of
uninterrupted rolling updates. SIGTERM with a paused registry drains the admitted slow call (200),
rejects the waiter (503) and ends with `drained=true`, exit 0.

Image inventory, native ELF dependencies, certificates/guest license and the absence of host
JDK/Maven/Rust were checked in the actual image. `curl` stays for health and diagnostics; 50 probes
are measured separately. Runtime/SDK checksums, fixed base/package sources and reproducible Maven
JARs preserve the upgrade boundary. Measurements and raw data are in
[benchmarks.md](../benchmarks.md). For open normal load, slow calls and recovery, A–D deliver
384/384 correct responses per engine. Closed gateway load, however, misses the 1000 ms p99 limit in
all six runs, partly also the 1 % error limit.

## Limits and checks not executed

- Relative transfer does not measure send/transit/receiver parsing time exactly; remote work is
  not interrupted automatically by a caller cancellation.
- HTTPS with its own wire header probe, invalid certificates, denied DNS/connect capabilities,
  forced DNS/IP changes and an expired shutdown grace were not re-checked in this consumer. The
  Docker smoke uses real DNS resolution. DNS/connect/TLS delays were not injected deliberately; the
  wire probe delays the pool and checks reuse as well as a new connect.
- JUnit runs on HotSpot; native statements come exclusively from guest/HTTP/Docker checks. No
  Dahemm migration or production/security approval.
- Short repeated load cycles show resources within the fixed limits; without forced GC, heap/RSS
  snapshots prove no long-term live retention. Mixed RSS rises clearly; an isolated measurement of
  native memory including JIT is not available.
- Healthcheck costs are measured for the current image; a direct before/after comparison of the
  same probes is still missing for the #8 acceptance.
- A Micro-owned generator, action/routing indexes and a raw body copy optimization are deferred for
  lack of proven benefit. The optional upstream processor is qualified separately above; no
  additional runtime interception.

## Reproduce

```bash
./scripts/build.sh /path/to/pinned/headless/distribution
./scripts/test-core.sh
python3 -m unittest discover -s scripts/tests -v
# Run all following commands also with HOORI_ENGINE=interpreter:
./scripts/test-hoori-core.sh
python3 scripts/test_control.py
python3 scripts/test_admission.py
python3 scripts/test_budgets.py
python3 scripts/smoke.py
```

The smoke test writes its bounded results to `.cache/hoori-micro-check-<pid>.json` and removes only
its own Compose project. For the comparable A–D measurement and separate profiling see
[benchmarks.md](../benchmarks.md).
