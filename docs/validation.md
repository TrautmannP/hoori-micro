# Validation

As of **5 October 2026**. The functional acceptance of the three framework cost optimizations
runs against the clean headless release distribution
`83d2b8fc83ffee6ed7c748409ff7b4802d8a341b`, guest base `0.4.0`, SDKs `0.1.0`, build JDK
`21.0.12.1`. The lock checks original POMs, receipt and SHA256s. Eight runtime SDKs plus six
pinned Avaje/Jakarta JARs are on the HTTP classpath; processor and optional DB JARs are not.

**Passed:** 38 JUnit tests (25 framework, 13 processor), 117 portable core assertions,
5 formatter fixtures and 24 Python tests. The additional 23 OpenAPI assertions run on the host
and natively. All native gates in the following table passed in **interpreter and mixed**. The
[acceptance receipt](validation/performance.json) maps app/runtime hashes, checks and raw evidence
hashes of this acceptance.

| Gate | Subject |
|---|---|
| `scripts/test-core.sh` | Portable core/formatter invariants |
| `python3 -m unittest discover -s scripts/tests -v` | Distribution check, classpath, build/stage isolation |
| `scripts/build.sh <distribution>` | Clean Maven build, format check and JUnit |
| `scripts/test-hoori-core.sh` | Core, admission, tasks, capacities, resource ownership and OpenAPI hash/conflict rules |
| `python3 scripts/test_http.py` | CRUD direct/client/gateway, 15 public operations, contract hashes, manifest, groups and local docs |
| `python3 scripts/test_admission.py` | Admission before encoding, queue budget and recovery |
| `python3 scripts/test_budgets.py` | Budget propagation, serial hops and bounded gateway snapshots |
| `python3 scripts/test_tasks.py` | Request context, failure priority, child/cleanup boundaries and capacity |
| `python3 scripts/test_control.py` | Registry renewal, timeout recovery and separate control pool |
| `python3 scripts/smoke.py` | Docker, seven OpenAPI publication states per engine, missing artifact, rolling updates, TTL/epochs, saturation and SIGTERM |

The new admission regression proves free permits when cancellation-callback capacity is exhausted
and complete queue cleanup after a failed registration. The
[five performance rounds](benchmarks.md#framework-optimizations-5-october-2026) and 24 final HTTP
comparison runs use the same runtime pin. Three isolated cost improvements remain; FIFO and
release yield were discarded. This does not prove an HTTP capacity gain or a met gateway p99
target. Hoori follow-up: [#231](https://github.com/TrautmannP/hoori/issues/231) and
[#232](https://github.com/TrautmannP/hoori/issues/232).

Native gates run with `HOORI_ENGINE=mixed` and `HOORI_ENGINE=interpreter`. Raw evidence lives
under `.cache/`; the compact receipt is versioned. Registration, catalog fetch, lease and
deregistration use the same unversioned HTTP routes. The smoke test checks them without a version
handshake; catalog filters, freshness, size limits and re-registration remain checked.

The build negative cases check, among others, missing/wrong routes, DTO schemas, constraints,
required parameters and incompatible baselines. Native checks prove lossless numbers, reference
resolution, per-operation stable hashes and withholding of contradicting contracts. In the Docker
test, an actually missing contract artifact makes the docs unavailable while business calls keep
working. After recovery, rolling update and registry restart, document and manifest match again
with the same publication ID.

Not re-run: `test_application_build.py`, `test_clients.py`, `test_mvc.py --gc-stress`,
`test_composition.py --facade`, `test_data.py`, `test-task-runtime.sh`, historical
load/performance matrices and CI. The local HTML reference was checked over real HTTP, without
visual browser acceptance.

The previous [OpenAPI/registry acceptance](validation/openapi.json) stays tied to its code and
JAR state. Earlier independent app, GC stress, task facade and PostgreSQL runs are in git state
`97fe5b3`. The previous [MVC acceptance](validation/mvc-http.json), the
[first MVC cut](validation/mvc-application.json), historical
[DTO/tasks evidence](validation/history-pre-mvc.md) and [benchmark data](benchmarks.md) stay tied
to their respective code/runtime states. The OpenAPI acceptance covers the documented JSON
profile (manual: `guide/openapi`), not full OpenAPI or JSON Schema. It is local and functional:
no CI, production or performance approval, no full Spring/Jakarta/JDK compatibility. #8, #10 and
#11 keep their own open requirements.

## IntelliJ configurations, 7 October 2026

The 17 shared configurations (manual: `guide/ide`) were checked as XML including compound
references. The build and both optional builds pass with JDK `21.0.12.1` and the unchanged
runtime pin. Portable core/formatter and 24 Python tests are green; the guest core runs in mixed
and interpreter. HTTP, MVC with GC stress, task facade, local data and Docker smoke pass in mixed.
The local start commands for all five demo roles and CRUD were checked including real Hoori
process selection, HTTP and SIGTERM drain.

No visual IntelliJ acceptance. Interpreter variants of the HTTP/example/smoke gates and the other
gates listed above were not re-run for this change; their previous receipts stay tied to the
earlier state.

## OpenAPI improvements, 7 October 2026

Issues #51–#54 and #57 use the unchanged runtime pin, JDK `21.0.12.1` and Maven `3.9.11`.
Passed: 41 JUnit tests, 117 portable assertions, 5 formatter fixtures, 27 Python tests,
the manual build, native core/38 OpenAPI assertions and `test_openapi.py` in **mixed and
interpreter**. The generated HTTP fixture verifies defaults (including empty strings), byte/short
boundaries, parameter overrides, literal `$ref` fields/examples, fixed diagnostic states,
conflicts, expiry and recovery. Existing HTTP/MVC checks passed in mixed.

The Docker mixed run passed publication, registry/lease/filter checks and missing-artifact
isolation/recovery, then stopped before resource sampling: this runner cannot read Docker's
`memory.stat` through its cgroup namespace. The full smoke is **not passed**. Staged artifacts
needed public read/traverse permissions because the runner defaults to `umask 0077`; image
non-root execution and security settings were preserved. Framework/processor artifacts were
installed before `build.sh`; this does not resolve the clean-cache first-build issue #46.

The [receipt](validation/openapi-improvements.json) binds the implementation revision, runtime,
JARs and raw evidence hashes. Remaining Docker stages, interpreter Docker smoke, MVC GC stress,
optional examples and performance matrices were not completed here; historical receipts remain
tied to their earlier code states.
