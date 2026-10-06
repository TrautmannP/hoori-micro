# Source baseline

Inspected on 4 October 2026 via `gh` and against the clean local checkout.
Repository: `TrautmannP/hoori` (private).
Pinned commit: `83d2b8fc83ffee6ed7c748409ff7b4802d8a341b`.

Main sources (HTTP/REST foundations from the previous baseline; tasks, DTO, optional data and
distribution contracts checked directly):

- `sdk/hoori-http-api/README.md`, `HttpClient.java`, `HttpServer.java`, `Limits.java`,
  `Headers.java`, `Request.java`, `RequestBudget.java`, `Response.java`: URI pooling/DNS/HTTPS,
  context, resource limits and exact drain semantics.
- `sdk/hoori-rest-api/README.md`, `pom.xml`, `Router.java`, `Attribute.java`,
  `json/Json.java`, `JsonReader.java`, `JsonWriter.java`, `JsonLimits.java`:
  explicit routes, errors and codec interfaces.
- `sdk/hoori-rest-{annotations,processor}`, `sdk/hoori-validation-{api,processor}`,
  `sdk/hoori-rest-validation-api`, `examples/hello-rest-validation` and
  `scripts/validation_test.py`: original record codecs, Avaje 2.18/Jakarta 3.1.1,
  generated adapters, supported profile, error format and provider JAR checksums.
- `sdk/hoori-rest-mvc-{api,processor}`, `HttpContract`, `JsonCodecs`, `ControllerRoutes`,
  `MvcErrors` and `HttpResult`: shared binding/DTO model and finite response contracts.
- `sdk/hoori-validation-avaje{,-processor}`: neutral validation SPI and original Avaje
  factories; no Avaje provider in the Micro core.
- `examples/hello-rest/README.md`: independent consumers, CLI arguments, operational lifecycle
  usage and cold-compilation notes.
- `scripts/build-distribution.sh`, `scripts/build-bundle.sh`: distribution, checksums, guest
  license and system-level native dependencies.
- `sdk/hoori-concurrent-api`: `Tasks`, `TaskSpec` and original POM;
  `sdk/hoori-concurrent-http-api`: README, `RequestScopes`, `HttpTasks` and POM.
- `TaskContext`, `TaskDiagnostics`, `Cancellation`, `DeadlineTimers` and `Operation`:
  context/timer ownership, capacity and actual completion.
- `sdk/hoori-task-processor` and `examples/hello-task-codegen`: build-time facades;
  `sdk/hoori-{transaction,jdbc,jdbi}-api`, `examples/hello-task-jdbc` and
  `scripts/jdbi_test.py`, `jdbc_test.py`, `jdbc_peers.py`: local transactions, real
  database/fault peers and physical resource release.
- `guest-classlib/PROVENANCE.md`: `System.getenv(String)` and `--allow-environment-read`.
- `crates/hoori-cli/src/main.rs`: `build-info`, in particular the feature list as a JSON array.

Path prefix for repository sources:
`https://github.com/TrautmannP/hoori/blob/83d2b8fc83ffee6ed7c748409ff7b4802d8a341b/`

Public primary sources:

- Docker Compose networking: https://docs.docker.com/compose/how-tos/networking/
- Docker Compose startup order: https://docs.docker.com/compose/how-tos/startup-order/

The sources document existing API contracts, not the successful execution of this newly built
consumer. That difference is recorded in `validation.md`.

## Upgrade rule

Another Hoori commit requires a deliberate lock update with renewed Maven, guest and container
acceptance. For new artifact versions, also update the POM properties and the versions/SDK
selection in `hoori.lock.json`. Never change only the lock to get an actually incompatible build
through the check.

SHA256SUMS plus the receipt check the consistency of a distribution that was already obtained
from a trusted source. They are not a digital signature and do not by themselves prove the origin
of a package an attacker replaced entirely. The runtime binary and the whole distribution must
come from a trusted build or artifact store.
