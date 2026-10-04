# Quellstand und Quellen

Inspektion: 4. Oktober 2026 per `gh` und gegen den sauberen lokalen Checkout.
Repository: `TrautmannP/hoori` (privat).
Gepinnter Commit: `6f581305baa31f75b6ffdf8214f527b966ac66d1`.

Wesentliche Quellen (HTTP-/REST-Grundlagen aus der bisherigen Baseline,
Tasks-, DTO-, optionale Daten- und Distributionsverträge direkt geprüft):

- `sdk/hoori-http-api/README.md`, `HttpClient.java`, `HttpServer.java`, `Limits.java`,
  `Headers.java`, `Request.java`, `RequestBudget.java`, `Response.java`: URI-Pooling/DNS/HTTPS,
  Kontext, Ressourcenlimits und exakte Drain-Semantik.
- `sdk/hoori-rest-api/README.md`, `pom.xml`, `Router.java`, `Attribute.java`,
  `json/Json.java`, `JsonReader.java`, `JsonWriter.java`, `JsonLimits.java`:
  explizite Routen, Fehler und Codec-Schnittstellen.
- `sdk/hoori-rest-{annotations,processor}`, `sdk/hoori-validation-{api,processor}`,
  `sdk/hoori-rest-validation-api`, `examples/hello-rest-validation` und
  `scripts/validation_test.py`: originale Record-Codecs, Avaje 2.18/Jakarta 3.1.1,
  explizite Adapter, unterstütztes Profil, Fehlerformat und Provider-JAR-Prüfsummen.
- `examples/hello-rest/README.md`: unabhängige Consumer, CLI-Argumente,
  operative Lifecycle-Nutzung und Cold-Compilation-Hinweise.
- `scripts/build-distribution.sh`, `scripts/build-bundle.sh`:
  Distribution, Prüfsummen, Guest-Lizenz und systemseitige native Abhängigkeiten.
- `sdk/hoori-concurrent-api`: `Tasks`, `TaskSpec` und Original-POM;
  `sdk/hoori-concurrent-http-api`: README, `RequestScopes`, `HttpTasks` und POM.
- `TaskContext`, `TaskDiagnostics`, `Cancellation`, `DeadlineTimers` und `Operation`:
  Kontext-/Timer-Ownership, Kapazität und tatsächlicher Abschluss.
- `sdk/hoori-task-processor` und `examples/hello-task-codegen`: Buildzeit-Fassaden;
  `sdk/hoori-{transaction,jdbc,jdbi}-api`, `examples/hello-task-jdbc` sowie
  `scripts/jdbi_test.py`, `jdbc_test.py`, `jdbc_peers.py`: lokale Transaktionen,
  echte Datenbank/Fault-Peers und physische Ressourcenfreigabe.
- `guest-classlib/PROVENANCE.md`: `System.getenv(String)` und
  `--allow-environment-read`.
- `crates/hoori-cli/src/main.rs`: `build-info`, insbesondere Feature-Liste als JSON-Array.

Pfadpräfix für die Repository-Quellen:
`https://github.com/TrautmannP/hoori/blob/6f581305baa31f75b6ffdf8214f527b966ac66d1/`

Öffentliche Primärquellen:

- Docker Compose Networking:
  https://docs.docker.com/compose/how-tos/networking/
- Docker Compose Startup Order:
  https://docs.docker.com/compose/how-tos/startup-order/

Die Quellen belegen bestehende API-Verträge, nicht die erfolgreiche Ausführung
dieses neu erstellten Consumers. Dieser Unterschied ist in `validation.md` erfasst.

## Upgrade-Regel

Ein anderer Hoori-Commit erfordert einen bewussten Lock-Update samt erneuter Maven-,
Guest- und Container-Abnahme. Bei neuen Artefaktversionen auch die POM-Properties
und die Versionen/SDK-Auswahl in `hoori.lock.json` aktualisieren. Nie bloß den Lock ändern,
um einen tatsächlich inkompatiblen Build durch die Prüfung zu bekommen.

SHA256SUMS plus Receipt prüfen Konsistenz einer bereits vertrauenswürdig bezogenen
Distribution. Sie sind keine digitale Signatur und beweisen allein keine Herkunft
eines von einem Angreifer vollständig ersetzten Pakets. Runtime-Binary und gesamte
Distribution müssen aus einem vertrauenswürdigen Build/Artefaktspeicher stammen.
