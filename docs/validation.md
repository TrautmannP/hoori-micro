# Validierung

Stand: **4. Oktober 2026**. Die OpenAPI-Abnahme läuft gegen die saubere Headless-Release-
Distribution `83d2b8fc83ffee6ed7c748409ff7b4802d8a341b`, Guest Base `0.4.0`,
SDKs `0.1.0`, Build-JDK `21.0.12.1`. Der Lock prüft Original-POMs, Receipt und
SHA256s. Acht Runtime-SDKs plus sechs gepinnte Avaje-/Jakarta-JARs stehen auf dem
HTTP-Klassenpfad; Processor und optionale DB-JARs gehören nicht dazu.

**Bestanden:** 38 JUnit-Tests (25 Framework, 13 Processor), 117 portable Core-Assertions,
5 Formatter-Fixtures und 24 Python-Tests. Die zusätzlichen 23 OpenAPI-Assertions
laufen auf dem Host und nativ. Alle nativen Gates der folgenden Tabelle sind in
**Interpreter und Mixed** bestanden. Die [OpenAPI-Receipt](validation/openapi.json)
ordnet App-/Runtime-Hashes, Checks und Rohbeleg-Hashes dieser Abnahme zu.

| Gate | Gegenstand |
|---|---|
| `scripts/test-core.sh` | Portable Core-/Formatter-Invarianten |
| `python3 -m unittest discover -s scripts/tests -v` | Distributionsprüfung, Klassenpfad, Build-/Stage-Isolation |
| `scripts/build.sh <Distribution>` | Sauberer Maven-Build, Formatprüfung und JUnit |
| `python3 scripts/test_application_build.py` | Eigenständig gebauter Starter, importiertes Modul ohne Quellen |
| `scripts/test-hoori-core.sh` | Core, Admission, Tasks, Kapazitäten, Ressourcenbesitz und OpenAPI-Hash-/Konfliktregeln |
| `python3 scripts/test_mvc.py --gc-stress` | MVC/DTO/Validation mit GC-Stress, Fehlerpriorität und Scope-Drain |
| `python3 scripts/test_http.py` | CRUD direkt/Client/Gateway, 15 öffentliche Operationen, Vertrags-Hashes, Manifest, Gruppen und lokale Doku |
| `python3 scripts/test_composition.py --facade` | Komposition, Task-Fassade, Batch/Bulk, Deadline und Recovery |
| `python3 scripts/test_data.py` | Normale DataApplication plus echtes PostgreSQL, Commit/Rollback/UNKNOWN/Cancel/Drain |
| `python3 scripts/smoke.py` | Docker, sieben OpenAPI-Publikationsstände je Engine, fehlendes Artefakt, Rolling Updates, TTL/Epochen, Sättigung und SIGTERM |

Native Gates laufen mit `HOORI_ENGINE=mixed` und `HOORI_ENGINE=interpreter`;
der unabhängige App-Build führt beide selbst aus. Vor optionalen Gates
`optional_example.py build task-facade` bzw. `build local-data` ausführen.
Rohbelege liegen unter `.cache/`; die kompakte Receipt ist versioniert.
Der unabhängige Starter baut die OpenAPI-Anwendung außerhalb des Checkouts.
Die Quellen wurden vor dem nativen Start entfernt; das Modulbeispiel lief
zusätzlich ohne verfügbares Build-JDK.

Die Datenabnahme bestätigt COMMITTED/UNKNOWN/ROLLED_BACK anhand von Tabelleninhalt,
Fault-Peer und sicheren Logmarkern. Beide normalen optionalen Application-Mains
wurden zusätzlich zu den Fault-/Task-Fixtures gestartet und über HTTP geprüft.
Keine Host-JVM ersetzt eine native Transportprüfung.

Die Build-Negativfälle prüfen unter anderem fehlende/falsche Routen, DTO-Schemas,
Constraints, erforderliche Parameter und inkompatible Baselines. Native Prüfungen
belegen verlustfreie Zahlen, Referenzauflösung, per Operation stabile Hashes und
das Zurückhalten widersprüchlicher Verträge. Im Docker-Test macht ein tatsächlich
fehlendes Vertragsartefakt die Doku unverfügbar, während Fachaufrufe weiterlaufen.
Nach Wiederherstellung, Rolling Update und Registry-Neustart stimmen Dokument
und Manifest bei gleicher Publikations-ID wieder überein.

Nicht erneut gelaufen: `test_clients.py`, die separaten Transport-Suites
`test_admission.py`, `test_budgets.py`, `test_tasks.py`, `test_control.py`,
`test-task-runtime.sh`, historische Last-/Performance-Matrizen und CI.
Die lokale HTML-Referenz wurde über echtes HTTP geprüft, ohne visuelle Browserabnahme.

Die vorherige [MVC-Abnahme](validation/mvc-http.json), der
[erste MVC-Schnitt](validation/mvc-application.json), historische
[DTO-/Tasks-Nachweise](validation/history-pre-mvc.md) und [Benchmarkdaten](benchmarks.md)
bleiben ihren jeweiligen Code-/Runtime-Ständen zugeordnet. Die OpenAPI-Abnahme
gilt für das [dokumentierte JSON-Profil](openapi.md), nicht für vollständiges
OpenAPI oder JSON Schema. Sie ist lokal und funktional: keine CI-/Produktions-/Performance-Freigabe,
keine vollständige Spring-/Jakarta-/JDK-Kompatibilität. #8, #10 und #11 behalten
ihre eigenständigen offenen Anforderungen.
