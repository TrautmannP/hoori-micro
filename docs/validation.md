# Validierung

Stand: **5. Oktober 2026**. Die funktionale Abnahme der drei
Framework-Kostenoptimierungen läuft gegen die saubere Headless-Release-Distribution
`83d2b8fc83ffee6ed7c748409ff7b4802d8a341b`, Guest Base `0.4.0`,
SDKs `0.1.0`, Build-JDK `21.0.12.1`. Der Lock prüft Original-POMs, Receipt und
SHA256s. Acht Runtime-SDKs plus sechs gepinnte Avaje-/Jakarta-JARs stehen auf dem
HTTP-Klassenpfad; Processor und optionale DB-JARs gehören nicht dazu.

**Bestanden:** 38 JUnit-Tests (25 Framework, 13 Processor), 117 portable Core-Assertions,
5 Formatter-Fixtures und 24 Python-Tests. Die zusätzlichen 23 OpenAPI-Assertions
laufen auf dem Host und nativ. Alle nativen Gates der folgenden Tabelle sind in
**Interpreter und Mixed** bestanden. Die [Abnahme-Receipt](validation/performance.json)
ordnet App-/Runtime-Hashes, Checks und Rohbeleg-Hashes dieser Abnahme zu.

| Gate | Gegenstand |
|---|---|
| `scripts/test-core.sh` | Portable Core-/Formatter-Invarianten |
| `python3 -m unittest discover -s scripts/tests -v` | Distributionsprüfung, Klassenpfad, Build-/Stage-Isolation |
| `scripts/build.sh <Distribution>` | Sauberer Maven-Build, Formatprüfung und JUnit |
| `scripts/test-hoori-core.sh` | Core, Admission, Tasks, Kapazitäten, Ressourcenbesitz und OpenAPI-Hash-/Konfliktregeln |
| `python3 scripts/test_http.py` | CRUD direkt/Client/Gateway, 15 öffentliche Operationen, Vertrags-Hashes, Manifest, Gruppen und lokale Doku |
| `python3 scripts/test_admission.py` | Admission vor Encoding, Queue-Budget und Recovery |
| `python3 scripts/test_budgets.py` | Budget-Weitergabe, serielle Hops und begrenzte Gateway-Snapshots |
| `python3 scripts/test_tasks.py` | Request-Kontext, Fehlerpriorität, Child-/Cleanup-Grenzen und Kapazität |
| `python3 scripts/test_control.py` | Registry-Erneuerung, Timeout-Recovery und getrennter Control-Pool |
| `python3 scripts/smoke.py` | Docker, sieben OpenAPI-Publikationsstände je Engine, fehlendes Artefakt, Rolling Updates, TTL/Epochen, Sättigung und SIGTERM |

Die neue Admission-Regression belegt freie Permits bei ausgeschöpfter
Cancellation-Callback-Kapazität und vollständige Queue-Bereinigung nach gescheiterter
Registrierung. Die [fünf Performance-Runden](benchmarks.md#framework-optimierungen-5-oktober-2026)
und 24 abschließenden HTTP-Vergleichsläufe verwenden denselben Runtime-Pin.
Drei isolierte Kostenverbesserungen bleiben; FIFO und Release-Yield wurden
verworfen. Ein HTTP-Kapazitätsgewinn oder bestandenes Gateway-p99-Ziel ist damit
nicht nachgewiesen. Hoori-Folgearbeit: [#231](https://github.com/TrautmannP/hoori/issues/231)
und [#232](https://github.com/TrautmannP/hoori/issues/232).

Native Gates laufen mit `HOORI_ENGINE=mixed` und `HOORI_ENGINE=interpreter`.
Rohbelege liegen unter `.cache/`; die kompakte Receipt ist versioniert.
Registrierung, Katalogabruf, Lease und Deregistrierung verwenden dieselben
unversionierten HTTP-Routen. Der Smoke prüft sie ohne Versions-Handshake;
Katalogfilter, Frische, Größenlimits und Wiederanmeldung bleiben geprüft.

Die Build-Negativfälle prüfen unter anderem fehlende/falsche Routen, DTO-Schemas,
Constraints, erforderliche Parameter und inkompatible Baselines. Native Prüfungen
belegen verlustfreie Zahlen, Referenzauflösung, per Operation stabile Hashes und
das Zurückhalten widersprüchlicher Verträge. Im Docker-Test macht ein tatsächlich
fehlendes Vertragsartefakt die Doku unverfügbar, während Fachaufrufe weiterlaufen.
Nach Wiederherstellung, Rolling Update und Registry-Neustart stimmen Dokument
und Manifest bei gleicher Publikations-ID wieder überein.

Nicht erneut gelaufen: `test_application_build.py`, `test_clients.py`,
`test_mvc.py --gc-stress`, `test_composition.py --facade`, `test_data.py`,
`test-task-runtime.sh`, historische Last-/Performance-Matrizen und CI.
Die lokale HTML-Referenz wurde über echtes HTTP geprüft, ohne visuelle Browserabnahme.

Die vorherige [OpenAPI-/Registry-Abnahme](validation/openapi.json) bleibt ihrem
Code- und JAR-Stand zugeordnet. Frühere unabhängige App-, GC-Stress-, Task-Fassaden-
und PostgreSQL-Läufe stehen im Git-Stand `97fe5b3`. Die vorherige [MVC-Abnahme](validation/mvc-http.json), der
[erste MVC-Schnitt](validation/mvc-application.json), historische
[DTO-/Tasks-Nachweise](validation/history-pre-mvc.md) und [Benchmarkdaten](benchmarks.md)
bleiben ihren jeweiligen Code-/Runtime-Ständen zugeordnet. Die OpenAPI-Abnahme
gilt für das [dokumentierte JSON-Profil](openapi.md), nicht für vollständiges
OpenAPI oder JSON Schema. Sie ist lokal und funktional: keine CI-/Produktions-/Performance-Freigabe,
keine vollständige Spring-/Jakarta-/JDK-Kompatibilität. #8, #10 und #11 behalten
ihre eigenständigen offenen Anforderungen.
