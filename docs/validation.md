# Validierung

Stand: **4. Oktober 2026**. Die MVC-Abnahme läuft gegen die saubere Headless-Release-
Distribution `83d2b8fc83ffee6ed7c748409ff7b4802d8a341b`, Guest Base `0.4.0`,
SDKs `0.1.0`, Build-JDK `21.0.12.1`. Der Lock prüft Original-POMs, Receipt und
SHA256s. Acht Runtime-SDKs plus sechs gepinnte Avaje-/Jakarta-JARs stehen auf dem
HTTP-Klassenpfad; Processor und optionale DB-JARs gehören nicht dazu.

**Bestanden:** 35 JUnit-Tests (24 Framework, 11 Processor), 117 portable Core-Assertions,
5 Formatter-Fixtures und 24 Python-Tests. Alle nativen Gates der folgenden Tabelle
sind in **Interpreter und Mixed** bestanden, einschließlich beider optionaler Apps,
echtem PostgreSQL und Docker-Smoke. Die [Abschluss-Receipt](validation/mvc-http.json)
enthält Runtime-/Guest-/SDK-/Provider-/App-Hashes, genaue Checks und Rohbeleg-Hashes.
Der [erste MVC-Schnitt](validation/mvc-application.json) bleibt separat nachvollziehbar.

| Gate | Gegenstand |
|---|---|
| `scripts/test-core.sh` | Portable Core-/Formatter-Invarianten |
| `python3 -m unittest discover -s scripts/tests -v` | Distributionsprüfung, Klassenpfad, Build-/Stage-Isolation |
| `scripts/build.sh <Distribution>` | Sauberer Maven-Build, Formatprüfung und JUnit |
| `python3 scripts/test_application_build.py` | Eigenständig gebauter Starter, importiertes Modul ohne Quellen |
| `python3 scripts/test_clients.py` | Separater nativer Consumer/Provider, Encoding, Responsefilter, verlorene Schreibantwort ohne Replay |
| `scripts/test-hoori-core.sh` | Core, Admission, Tasks, Kapazitäten und Ressourcenbesitz |
| `python3 scripts/test_mvc.py --gc-stress` | MVC/DTO/Validation mit GC-Stress, Fehlerpriorität und Scope-Drain |
| `python3 scripts/test_http.py` | Normale erzeugte Apps, CRUD direkt/Client/Gateway, Medien-/Feldfehler und Kontext |
| `python3 scripts/test_composition.py --facade` | Komposition, Task-Fassade, Batch/Bulk, Deadline und Recovery |
| `python3 scripts/test_data.py` | Normale DataApplication plus echtes PostgreSQL, Commit/Rollback/UNKNOWN/Cancel/Drain |
| `python3 scripts/test_admission.py` | Admission vor Encoding, Queue-Budget und Recovery |
| `python3 scripts/test_budgets.py` | Before-Write-Budget, serielle Hops und begrenzte Gateway-Snapshots |
| `python3 scripts/test_tasks.py` | Korrelation, Fehlerpriorität, Child-/Cleanup-Grenzen und Kapazität |
| `python3 scripts/test_control.py` | Getrennter Control-Pool, Timeout-Recovery und Stop |
| `python3 scripts/smoke.py` | Echte Docker-Apps, zusätzliche Provider-Version, TTL/Epochen, Sättigung und SIGTERM |

Native Gates laufen mit `HOORI_ENGINE=mixed` und `HOORI_ENGINE=interpreter`;
die unabhängigen Consumer-Skripte führen beide selbst aus. Vor optionalen Gates
`optional_example.py build task-facade` bzw. `build local-data` ausführen.
Rohbelege liegen unter `.cache/`; die kompakte Abschluss-Receipt ist versioniert.
Die Quellen der unabhängig gebauten Apps wurden vor dem nativen Start entfernt;
das Modulbeispiel lief zusätzlich ohne verfügbares Build-JDK. Die POST-Verlustprobe
bestätigt jeweils genau einen Schreibversuch durch Client und Gateway.

Die Datenabnahme bestätigt COMMITTED/UNKNOWN/ROLLED_BACK anhand von Tabelleninhalt,
Fault-Peer und sicheren Logmarkern. Die MVC-Fehlergrenze nutzt jetzt dieselbe
Protokollierung wie der Router (#44). Beide normalen optionalen Application-Mains
wurden zusätzlich zu den Fault-/Task-Fixtures gestartet und über HTTP geprüft.
Keine Host-JVM ersetzt eine native Transportprüfung.

Nicht erneut gelaufen: die unveränderte SDK-isolierte `test-task-runtime.sh`-Suite,
alle historischen Last-/Performance-Matrizen und CI. Der kurze A/B/C/D-Lauf des
umgestellten Benchmark-Consumers in Mixed ist ausschließlich ein Funktionstest.

Historische [DTO-/Tasks-Nachweise](validation/history-pre-mvc.md) und
[Benchmarkdaten](benchmarks.md) bleiben ihren alten Pins zugeordnet. Die MVC-
Abnahme ist lokal und funktional: keine CI-/Produktions-/Performance-Freigabe,
keine vollständige Spring-/Jakarta-/JDK-Kompatibilität. #8, #10 und #11 behalten
ihre eigenständigen offenen Anforderungen.
