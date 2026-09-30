# Validierung und offene Abnahme

Stand: **30. September 2026**. Baseline siehe `hoori.lock.json`.

## Tatsächlich ausgeführt

Runtime: saubere Headless-Release-Distribution, lokal aus dem gepinnten Commit gebaut
(`x86_64-unknown-linux-gnu`, `dirty=false`). Toolchain: Temurin 21.0.6, Maven 3.9.16,
Docker 29.8.1, Compose v5.5.1, Basis `debian:trixie-slim` (Tag, kein Digest).
Der qualifizierte Satz ist jetzt `3254301` (VM, Guest Base und alle SDK-JARs gemeinsam).
Er enthält die upstream gelieferten Pending-Acquire- und lokalen RequestBudget-APIs.
Pool-Isolation, Registrar-Recovery und Framework-Zulassung sind auf diesem Satz
geprüft; serviceübergreifende Wire-Budgets bleiben #6.

| Prüfung | Ergebnis | Aussagegrenze |
|---|---|---|
| Spotless-Check (auch in `mvn verify`) | **29 Java-Dateien geprüft** | Palantir 2.100.0 plus JDK-Syntaxschritt in Spotless 3.10.3; nur Host-JDK. Fehlende Leerzeilen abgewiesen, `apply` korrigiert, erneuter Check erfolgreich |
| `scripts/test-core.sh` (HotSpot) | **116 Assertions und 5 Formatter-Fixtures bestanden** | Namen, Origins, Konfiguration einschließlich Admission-/Pending-/Control-Bounds; kein Netzwerk |
| Python `unittest` | **14 Tests bestanden** | 12 Distributionsprüfungen; Benchmark-Zählung, begrenzte offene Last und keine Generator-Retries. Lokaler Python-Peer, keine native Transport-Abnahme |
| `scripts/build.sh` inkl. `mvn clean verify` | **19 JUnit-Tests bestanden** | Lease-/Byte-Wiederverwendung, Revisionen, Limits, Sichten/Frische, Timeout vs. Cancellation/Close, getrennte Exchanges, Ablehnung vor Encoding und Permit-Freigabe. HotSpot mit Transport-Seam |
| `scripts/test-hoori-core.sh`, mixed und interpreter | **116 Assertions und Admission-Checks bestanden** | Echte Guest-Ausführung: Bounds, Deadline, Interrupt, Stop/Close und Wakeups |
| `scripts/test_control.py`, mixed und interpreter | **bestanden** | Realer gepinnter SDK/Guest; absichtlich verzögerter Loopback-Peer, siehe unten |
| `scripts/test_admission.py`, mixed und interpreter | **bestanden** | Echte native HTTP-Requests und SDK-Exchanges; Codec-/Wire-Zähler, Bounds und Shutdown, siehe unten |
| `scripts/smoke.py`, mixed und interpreter | **7 Phasen bestanden** | Einschließlich Daten-Sättigung, nativer Sichten, Lease-/Byte-Grenzen und gleichzeitiger alter/neuer Anbieter |

Der Smoke-Test startet Registry, Recipes, Shopping und Gateway als Container und prüft:
Veröffentlichung über das Gateway, Aufruf über zwei Action-Hops, fachliche 404/400,
Request-ID über alle Hops ohne Authorization-Weitergabe, nicht öffentlichen
Invoke-Endpunkt, exakten Registry-Katalog; Neuerstellung von Recipes mit zusätzlicher
Action bei gleichzeitig weiterlaufender alter Instanz, die ohne Neustart von
Shopping/Gateway (gleiche Container-IDs) veröffentlicht wird. Acht neue Aufrufe
erreichen die neue Instanz; direkte Advertise-URLs liefern dort 200 und bei der alten
Instanz 421. Nach deren Stop wird die Auswahlkonvergenz geprüft; Aufrufe bei
gestoppter Registry und vollständige Wiederanmeldung nach deren
Neustart; Anbieter-Ausfall mit sicherem 502 und Erholung. Shopping hat im Test eine
Datenverbindung, ein ausgehendes Permit und zwei Framework-Wartende vor Encoding:
Drei kooperativ wartende Aufrufe halten die Lease länger als eine TTL frisch,
bei Control ≤1 Verbindung/null Wartenden.
SDK-Pending bleibt null. SIGTERM drainiert den zugelassenen Datenaufruf und weist
den Wartenden mit 503 ab; Exit-Code 0.

Discovery-Protokoll 2 wurde zusätzlich im echten Registry-Container geprüft:
legacy PUT/GET, unveränderte leere Leases/bedingte Abrufe (204 und gleiche Revision),
abweichende Protokollversion (426), zwei einzeln passende Registrierungen mit zu
großem Gesamtkatalog (zweite mit 413 abgewiesen und nicht sichtbar), DELETE und
anschließend unbekannte Lease (404). Consumer bekommen nur deklarierte Services
und Action-Namen; `none` bleibt leer, `public` enthält nur publizierte Actions,
Sichtwechsel erzwingen 200 und passende Bestätigungen erlauben 204. Snapshot-/Byte-
Identität, verworfene Versionsrückschritte und falsche Sichten sowie Freigabe
abgelaufener Broker-Zeilen sind zusätzlich per JUnit belegt.

Der Control-Fault-Check reproduziert auf dem alten Framework/gleichen SDK: Ein
Timeout beendet den Registrar, DELETE folgt und Ready bleibt 200. Der neue Stand
übersteht einen einzelnen und zwei aufeinanderfolgende Timeouts, meldet sich nach
404 erneut vollständig an und setzt Leases ohne Prozessneustart fort. SIGTERM
während einer weiteren langsamen vollen Registrierung wartet auf ein verzögertes
DELETE; danach `drained=true`, Exit 0 und aktive/idle/Pending-Zähler beider Pools null.
Das ist ein kontrollierter Peer-Check; die Registry-/Action-Integration läuft separat
im Docker-Smoke. Rohdaten und Kostenvergleich stehen unter [benchmarks.md](benchmarks.md).

Der Admission-Check hält eine Action vor dem Abschluss: Ein weiterer ungültiger
Raw-Body erhält 503 ohne DTO-Read, danach liefern ungültige/korrekte Eingaben 400/200.
Ein globaler ausgehender Slot plus ein Wartender begrenzt typisierte, generische,
zweite Abhängigkeit und Gateway-Calls. Abweisungen lassen Codec-/Wire-Zähler gleich;
zwei Lastspitzen erholen sich ohne Prozessneustart. Langsames Encoding verbraucht
das Budget, ein Wartender läuft ohne Encoding/Exchange ab. SIGTERM weist Wartende
ab, drainiert die zugelassene Antwort und endet mit beiden Gates/Pools leer und Exit 0.
Thread-Interrupt und Sofort-Close sind zusätzlich in den Guest-Admission-Checks geprüft.

Dabei gefunden und behoben: Hooris Guest-Classlib hat weder `String.repeat` noch
`Long.toHexString`, `Double.isInfinite` oder `Math.floorMod`. Die bisherigen
Core-Checks liefen deshalb nie auf Hoori; `hoori.lock.json` fehlte im Repository.

## Nicht ausgeführt

- Rolling Update mit gleichzeitig alten und neuen Instanzen unter Last;
- verweigerte DNS-/Connect-Capabilities, HTTPS, erzwungene IP-Wechsel und die
  kombinierte Admission-/Budget-Abnahme aus #10;
- Guest-Ausführung der JUnit-Vertragstests (HotSpot; Guest-Abdeckung nur über Smoke);

A–D-Lastkontrollen liefen in Mixed und Interpreter mit je drei frischen Prozessen
pro Variante; eine größere C/D-Katalogkontrolle lief separat. Zahlen, Runtime- und
Artefaktidentität sowie Akzeptanzverletzungen stehen in [benchmarks.md](benchmarks.md).
Die normale Recovery folgt jeweils einer einzelnen Lastspitze; wiederholte
Rolling-/Recovery-Zyklen aus #10 sind damit nicht abgenommen. Die alten A–D-Läufe
haben keine Pending-Acquire-Messung; das ist in den Ergebnissen `null`. Auch die
unveränderte Pin-Vergleichsfixture erfasst diesen Zähler noch nicht. Auf dem neuen
SDK ist die API vorhanden; die aktuelle Fixture erfasst beide Pools sowie
laufende/wartende Framework-Calls.

## Reproduzieren

```bash
./scripts/build.sh /pfad/zur/passenden/headless/distribution
./scripts/test-hoori-core.sh
python3 scripts/test_control.py
python3 scripts/test_admission.py
python3 scripts/smoke.py

HOORI_ENGINE=interpreter ./scripts/test-hoori-core.sh
HOORI_ENGINE=interpreter python3 scripts/test_control.py
HOORI_ENGINE=interpreter python3 scripts/test_admission.py
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

Es gibt eine gemessene Ausgangsbasis und gezielte Vergleichskontrollen, weiterhin
**keine allgemeine Performancefreigabe**. Messgrenzen siehe `benchmarks.md`.
