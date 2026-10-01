# Quellstand und Quellen

Inspektion: 2. Oktober 2026 per `gh` und gegen den sauberen lokalen Checkout.
Repository: `TrautmannP/hoori` (privat).
Gepinnter Commit: `d8906e62f5aa335d6ddfeecb60156947a691f29e`.

Wesentliche direkt gelesene Quellen unter diesem Commit:

- `sdk/hoori-http-api/README.md`, `HttpClient.java`, `HttpServer.java`, `Limits.java`,
  `Headers.java`, `Request.java`, `RequestBudget.java`, `Response.java`: URI-Pooling/DNS/HTTPS,
  Kontext, Ressourcenlimits und exakte Drain-Semantik.
- `sdk/hoori-rest-api/README.md`, `pom.xml`, `Router.java`, `Attribute.java`,
  `json/Json.java`, `JsonReader.java`, `JsonWriter.java`, `JsonLimits.java`:
  explizite Routen, Fehler und Codec-Schnittstellen.
- `examples/hello-rest/README.md`: unabhängige Consumer, CLI-Argumente,
  operative Lifecycle-Nutzung und Cold-Compilation-Hinweise.
- `scripts/build-distribution.sh`, `scripts/build-bundle.sh`:
  Distribution, Prüfsummen, Guest-Lizenz und systemseitige native Abhängigkeiten.
- `guest-classlib/PROVENANCE.md`: `System.getenv(String)` und
  `--allow-environment-read`.
- `crates/hoori-cli/src/main.rs`: `build-info`, insbesondere Feature-Liste als JSON-Array.

Pfadpräfix für die Repository-Quellen:
`https://github.com/TrautmannP/hoori/blob/d8906e62f5aa335d6ddfeecb60156947a691f29e/`

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
und die Importversionen in `scripts/build.sh` aktualisieren. Nie bloß den Lock ändern,
um einen tatsächlich inkompatiblen Build durch die Prüfung zu bekommen.

SHA256SUMS plus Receipt prüfen Konsistenz einer bereits vertrauenswürdig bezogenen
Distribution. Sie sind keine digitale Signatur und beweisen allein keine Herkunft
eines von einem Angreifer vollständig ersetzten Pakets. Runtime-Binary und gesamte
Distribution müssen aus einem vertrauenswürdigen Build/Artefaktspeicher stammen.
