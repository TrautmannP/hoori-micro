# Validierung und offene Abnahme

Stand: **30. September 2026**. Baseline siehe `hoori.lock.json`.

## Tatsächlich ausgeführt

Runtime: saubere Headless-Release-Distribution, lokal aus dem gepinnten Commit gebaut
(`x86_64-unknown-linux-gnu`, `dirty=false`). Toolchain: Temurin 21.0.6, Maven 3.9.16,
Docker 29.8.1, Compose v5.5.1, Basis `debian:trixie-slim` (Tag, kein Digest).

| Prüfung | Ergebnis | Aussagegrenze |
|---|---|---|
| Spotless-Check (auch in `mvn verify`) | **24 Java-Dateien geprüft** | Palantir 2.100.0 plus JDK-Syntaxschritt in Spotless 3.10.3; nur Host-JDK. Fehlende Leerzeilen abgewiesen, `apply` korrigiert, erneuter Check erfolgreich |
| `scripts/test-core.sh` (HotSpot) | **96 Assertions und 5 Formatter-Fixtures bestanden** | Namen, Origins, Konfiguration; Formatter mit Kommentaren/Literalen, `else if`, Guards, Switch, Idempotenz und ungültigem Input; kein Netzwerk |
| Python `unittest` | **14 Tests bestanden** | 12 Distributionsprüfungen; Benchmark-Zählung, begrenzte offene Last und keine Generator-Retries. Lokaler Python-Peer, keine native Transport-Abnahme |
| `scripts/build.sh` inkl. `mvn clean verify` | **13 JUnit-Tests bestanden** | Zusätzlich: Snapshot-/Byte-Wiederverwendung bei Leases, monotone Revisionen, transaktionales Byte-/Eintragslimit, kleine Broker-Leases und Neuanmeldung, Frische ohne Versionsrückschritt. HotSpot mit Transport-Seam |
| `scripts/test-hoori-core.sh`, mixed und interpreter | **96 Assertions bestanden** | Echte Guest-Ausführung der portablen Checks |
| `scripts/smoke.py`, mixed und interpreter | **6 Phasen bestanden** | Einschließlich nativer Lease-/Protokoll-/Byte-Grenzen; siehe unten |

Der Smoke-Test startet Registry, Recipes, Shopping und Gateway als Container und prüft:
Veröffentlichung über das Gateway, Aufruf über zwei Action-Hops, fachliche 404/400,
Request-ID über alle Hops ohne Authorization-Weitergabe, nicht öffentlichen
Invoke-Endpunkt, exakten Registry-Katalog; Neuerstellung von Recipes mit zusätzlicher
Action, die ohne Neustart von Shopping/Gateway (gleiche Container-IDs) veröffentlicht
wird; Aufrufe bei gestoppter Registry und vollständige Wiederanmeldung nach deren
Neustart; Anbieter-Ausfall mit sicherem 502 und Erholung; SIGTERM-Drain eines laufenden
Aufrufs mit Exit-Code 0.

Discovery-Protokoll 2 wurde zusätzlich im echten Registry-Container geprüft:
legacy PUT/GET, unveränderte leere Leases/bedingte Abrufe (204 und gleiche Revision),
abweichende Protokollversion (426), zwei einzeln passende Registrierungen mit zu
großem Gesamtkatalog (zweite mit 413 abgewiesen und nicht sichtbar), DELETE und
anschließend unbekannte Lease (404). Snapshot-/Byte-Identität und verworfene
Versionsrückschritte sind zusätzlich per JUnit belegt. Registrar-Timeout-Recovery
und Datenpool-Isolation bleiben die offene Arbeit aus #4.

Dabei gefunden und behoben: Hooris Guest-Classlib hat weder `String.repeat` noch
`Long.toHexString`, `Double.isInfinite` oder `Math.floorMod`. Die bisherigen
Core-Checks liefen deshalb nie auf Hoori; `hoori.lock.json` fehlte im Repository.

## Nicht ausgeführt

- Mehrere Replikate eines Service in Docker (Auswahl pro Action nur per JUnit belegt);
- Rolling Update mit gleichzeitig alten und neuen Instanzen unter Last;
- verweigerte DNS-/Connect-Capabilities, HTTPS, erzwungene IP-Wechsel und die
  kombinierte Admission-/Budget-Abnahme aus #10;
- Guest-Ausführung der JUnit-Vertragstests (HotSpot; Guest-Abdeckung nur über Smoke);

A–D-Lastkontrollen liefen in Mixed und Interpreter mit je drei frischen Prozessen
pro Variante; eine größere C/D-Katalogkontrolle lief separat. Zahlen, Runtime- und
Artefaktidentität sowie Akzeptanzverletzungen stehen in [benchmarks.md](benchmarks.md).
Die normale Recovery folgt jeweils einer einzelnen Lastspitze; wiederholte
Rolling-/Recovery-Zyklen aus #10 sind damit nicht abgenommen. Die alte SDK-Baseline
kann Pending-Acquires noch nicht messen; das ist in den Ergebnissen `null`.

## Reproduzieren

```bash
./scripts/build.sh /pfad/zur/passenden/headless/distribution
./scripts/test-hoori-core.sh
python3 scripts/smoke.py

HOORI_ENGINE=interpreter ./scripts/test-hoori-core.sh
HOORI_ENGINE=interpreter python3 scripts/smoke.py
```

`build.sh` führt Maven gegen den importierten echten SDK-Stand aus. Ein inkompatibler
oder anderer Hoori-Stand wird bereits vor dem Import abgewiesen. Der Dockerfile-
Build prüft die tatsächliche Runtime auf Ausführbarkeit mit der gewählten Basis.

Der Smoke-Test verwendet ein eigenes Compose-Projekt;
fehlende Voraussetzungen oder gescheiterte Checks liefern Fehler statt eines JVM-
Fallbacks. Standardport 18080 muss verfügbar sein, sonst `HOORI_DEMO_PORT` setzen.

Auch der Smoke-Test ist keine Produktions-, Sicherheits- oder Lastfreigabe. Zusätzliche
Abnahmepunkte stehen unter F1 in `roadmap.md`.

## Berichterstattung

Bei späterer Abnahme mindestens Runtime-Receipt, Java-/Maven-Version,
Container-Basis/Digest, Engine-Modus, Testausgaben und relevante Fehler festhalten.
Nicht ausgeführte Schritte ausdrücklich offen lassen. Im aktuellen Git-Baum liegt
keine CI-Konfiguration; die hier genannten Prüfungen wurden lokal ausgeführt.

Es gibt eine gemessene Ausgangsbasis, weiterhin **keine gemessenen Gewinne einer
Framework-Optimierung**. Kandidaten werden mit denselben Einstellungen verglichen.
