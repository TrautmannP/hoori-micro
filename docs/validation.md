# Validierung und offene Abnahme

Stand: **3. Oktober 2026**. Tasks-v2-Buildintegration (#19) verwendet den sauberen
Headless-Release `7d7245a`, Guest Base `0.4.0` und SDKs `0.1.0`. Installation und
Start verwenden die vier HTTP-/REST-/Concurrent-SDKs aus `runtimeSdks` im Lock.
Original-POMs, Receipt, Prüfsummen und die Concurrent-Runtime-Bindung werden
geprüft; der Maven-Cache ist nach der vollständigen Distributionsidentität getrennt.
Die Distribution bleibt unverändert, optionale SDKs/Processor sind keine
HTTP-Laufzeitabhängigkeiten. Die neue Request-/Broker-Semantik ist unten separat belegt.

## Tasks-v2-Grundlage (#19)

- `scripts/build.sh`: Maven/Spotless und **22 JUnit-Tests** bestanden.
- `scripts/test-core.sh`: **116 Assertions und 5 Formatter-Fixtures**;
  Python `unittest`: **20 Tests**, einschließlich POM-Mischung, Runtime-Bindung,
  Cache-Isolation und unverändertem Staging.
- `scripts/test-task-runtime.sh`: unabhängiger SDK-Consumer mit echten
  RequestScopes und parallelen HttpTasks in **Interpreter/Mixed** bestanden.
  Kompiliert ohne Micro-Klassen; ausgeführt mit `PATH`/`JAVA_HOME=/nonexistent`
  und genau den fünf Runtime-JARs aus dem Lock, ohne Annotationen/Processor/DB.
- `scripts/test-hoori-core.sh` und `scripts/smoke.py`: **beide Engines bestanden**.
  Die Images starten Registry, Gateway und Fachservices; echte DNS-/HTTP-Calls,
  Sättigung, Rolling/Katalogwechsel, Recovery und SIGTERM-Drain bestehen.
  Rohdaten: [Mixed](benchmarks/issue-19-smoke-mixed.json.gz),
  [Interpreter](benchmarks/issue-19-smoke-interpreter.json.gz).
- Receipt-SHA256: `8c6d4340bc272b9d73f18972585ae55a8f364df8d77e993a1e9752e32c585e23`.
  Das Image enthält die vollständige originale Distribution mit 13 SDKs plus
  Guest Base; geladen werden Guest Base, HTTP, REST, Concurrent, Concurrent HTTP.
  Kein JDK/Maven im Image, kein zusätzliches Runtime-/VM-Paketformat.

## Verwalteter HTTP-Kern (#20–#22, #24–#25)

- Maven/Spotless: **23 JUnit-Tests**, einschließlich unveränderter Fehlerursachen
  und COMMITTED/ROLLED_BACK/UNKNOWN-Klassifikation. Portable Checks:
  **117 Assertions, 5 Formatter-Fixtures, 20 Python-Tests**.
- `test-hoori-core.sh`: Core, Admission und TaskChecks auf echter HooriVM;
  Cancellation-vor-Registrierung, Cancel/Permit-Rennen, 256 Registrierungszyklen,
  Fail-fast-Fehlerpriorität und wiederverwendbare Specs/Service-Kontexte.
- `test_admission.py`, `test_budgets.py`, `test_control.py`: **Interpreter/Mixed
  bestanden**. Weiterhin echte Hoori-Pools, sinkende serielle Budgets, validierte
  Wire-Werte, getrennte Control-Recovery und Admission vor Codecs.
- `test_tasks.py`: **Interpreter/Mixed bestanden**. Keep-alive und überlappende
  Requests behalten ihre Korrelation; keine Credentials/Raw-Requests in Kindern.
  Vorab erstellte Specs lesen den aktuellen Kontext und starten vorher keinen Codec.
  Kontrollierte Pool-/Body-Waits brechen einzeln ab; gleichzeitige und spätere
  Calls auf demselben Client funktionieren. Beide Parallel-Calls starten vor
  Freigabe der Antworten. Fail-fast erhält den Fachfehler; settled verschluckt
  keine globale Deadline. Batch-Reihenfolge/Parallelitätsgrenze, Child-Kapazität,
  Body-/Child-/Encoder-/Cleanup-Fehler und sichere Antworttexte sind geprüft.
- Ein kontrolliertes Finally-Gate hält Root und Incoming-Permit belegt, obwohl
  der lokale Operationsbody bereits zurückgegeben hat. Health bleibt erreichbar;
  `TaskDiagnostics` zeigt READ/DRAIN. Antwort und Ressourcenfreigabe folgen erst
  nach Öffnen des Gates. Danach sind Roots/Diagnosereferenzen/Waiter/Permits leer.
- SIGTERM: Erfolg innerhalb Grace; langsamer Fan-out wird nach 400 ms Grace
  lokal abgebrochen, Kind-Finally und Ressourcen drainieren vor Client-Close.
  Verzögerte Registry verlängert diese Frist nicht. Separat: Root bereits beendet,
  4-MiB-Antwort am langsamen Empfänger noch offen; Transport behält seine Rest-Grace
  und meldet anschließend ehrlich `roots_drained=true drained=false`.
  Handler-Stop, mehrfaches Close, 32 Start-/Stop-Interleavings und Fehler am bereits
  belegten Listener bestehen.
- Fünf Last-/Fehler-/Deadline-Zyklen mit explizitem GC und Idle im selben Prozess:
  keine retained Request-/Body-WeakReferences, keine aktiven Roots/Permits/Waiter,
  leere Diagnoseliste; beobachtete Tasks/Handles kehren auf begrenzte Werte zurück.
  Das ist gezielte Micro-Ownership-Evidenz, keine konstante RSS-Zusage oder
  erneute vollständige Upstream-Timer-/VM-/DNS-/TLS-Abnahme.
- Negativfall: nicht kooperierender Code bleibt über Grace aktiv. Der Harness
  beendet ihn extern; dieser Lauf beweist **keinen Drain**, und das Log behauptet
  auch keinen. Ein Socket-Abbruch beweist keinen Rollback eines Remote-Writes.

Rohdaten einschließlich Fixture-/Framework-Hashes und GC-Samples:
[Mixed](benchmarks/tasks-core-mixed.json.gz),
[Interpreter](benchmarks/tasks-core-interpreter.json.gz).
Zusätzlich ist der vollständige Docker-Smoke in beiden Engines bestanden:
[Mixed](benchmarks/tasks-core-smoke-mixed.json.gz),
[Interpreter](benchmarks/tasks-core-smoke-interpreter.json.gz), einschließlich
Rolling-/Katalogwechsel, Sättigung, Registry-Ausfall und SIGTERM-Drain.

Die Zwei-Provider-Demo (#23), optionale Fassaden/DB (#26/#27) und integrierte
Kostenkontrolle (#28) folgen separat. #8/#10/#11 bleiben unabhängige Arbeit.

## Bisherige Fachabnahme

Die nachfolgende bisherige Fachabnahme gehört zum Satz `d8906e6`, sauberer Headless-
Release für `x86_64-unknown-linux-gnu`; VM, Guest Base und sämtliche SDK-JARs aus
derselben Distribution. Sie ersetzt keine erneute Tasks-v2-Abnahme. Temurin 21.0.6,
Maven 3.9.16, Docker 29.8.1, Compose v5.5.1. Basisimage: amd64-Digest aus
`docker/Dockerfile`, signierte Debian-Paketquellen vom 30.09.2026.

## Tatsächlich ausgeführt

| Prüfung | Ergebnis | Aussagegrenze |
|---|---|---|
| `scripts/build.sh` / `mvn clean verify`, Spotless | **22 JUnit-Tests bestanden** | Reale SDK-JARs; JUnit auf HotSpot mit Transport-Seam, keine native Netzwerkfreigabe daraus |
| `scripts/test-core.sh` | **116 Assertions, 5 Formatter-Fixtures bestanden** | Host-JDK; Namen, Konfiguration und Bounds |
| Python `unittest` | **14 Tests bestanden** | Distributionsprüfung, Benchmark-Zählung und begrenzte offene Last |
| `scripts/test-hoori-core.sh`, beide Engines | **bestanden** | Echte Guest-Core-/Admission-Prüfung einschließlich Interrupt, Deadline, Stop/Close |
| `scripts/test_control.py`, beide Engines | **bestanden** | Getrennter Control-Pool; wiederholte Timeouts, Wiederanmeldung und begrenzte Deregistrierung |
| `scripts/test_admission.py`, beide Engines | **bestanden** | Admission vor DTO/Encoding, zwei Lastspitzen, Ablauf ohne Wire-Arbeit, Stop/Drain und leere Gates/Pools |
| `scripts/test_budgets.py`, beide Engines | **bestanden** | Echte SDK-Pool-Probe plus drei native Guest-Prozesse; relative Budgets und vorbereitete Gateway-Snapshots |
| `scripts/smoke.py`, beide Engines | **bestanden** | Reale Images, drei Lastspitzen, Rolling/Katalogwechsel und Ausfälle unter fortlaufenden Fachcalls, Ressourcenmessung, Cleanup |
| `scripts/benchmark.py`, beide Engines | **24 A–D-Läufe vollständig; Gateway-p99 verfehlt** | Drei frische Prozesse je Variante/Engine, gleicher Satz und Limits; Runtime-/SDK-Kontrolle separat |
| D mit 64 wechselnden öffentlichen Routen / getrenntes Profiling | **vollständig** | Größenkontrolle, natürliche GC und leere Handles/abgeschlossene Tasks bei Exit; keine Performancefreigabe |

## Budgets und Gateway (#6/#7)

Nach 800 ms kontrolliertem Pool-Warten sinkt der 2000-ms-Wire-Wert unmittelbar vor
Request-Schreiben; wiederverwendete und neue Verbindung werden getrennt geprüft.
Gateway → Shopping → Recipes und zwei serielle Recipe-Aufrufe teilen dieselbe
Deadline. Hintergrundkontexte mit kürzerem Budget funktionieren; abgelaufene
Kontexte starten keinen weiteren Child-Call. Native Prüfungen bestätigen 400 für
malformed/doppelte/negative/übergroße interne Werte und 504 für null/abgelaufene
Budgets. Externe Budget-/Action-/Versionsheader verlängern die Gateway-Policy nicht;
Request-ID bleibt erhalten, Authorization wird nicht weitergereicht.

Cancellation gibt den eigenen SDK-Slot und das Permit frei; andere Calls desselben
Clients funktionieren weiter. Zwei Sättigungszyklen liefern gezählte 200/504 und
anschließend normale Antworten ohne Prozessneustart. Am Schluss sind alle Gates
und Pools leer, alle drei Prozesse enden mit Exit 0 und `drained=true`.

Routen werden vor Veröffentlichung gemeinsam mit Katalog/Revision vorbereitet;
Requests benutzen diesen Snapshot auch für die Auswahl. Native Checks prüfen
Route ändern/entfernen, verweigerte Permission, widersprüchliche Rolling-Metadaten,
null/ungültige Pfadparameter sowie Count-/Metadatenüberlauf. Jeweils gültige
128/100 zusätzliche Routen werden zuerst angenommen; erst die Erweiterung auf
über 256 Routen bzw. 64 KiB Metadaten scheitert. Kein partielles Update; der alte
Snapshot altert aus und konvergiert nach Entfernung des Übermaßes ohne Neustart.
JUnit bestätigt zusätzlich unveränderte Array-Identität und atomare Revisionen.

## Gemeinsame Image-/Lastabnahme (#8/#10)

Smoke verwendet pro Rolle 0,5 CPU, 256 MiB Containerspeicher und 32 MiB logischen
Guest-Heap. Alte/neue Recipe-Instanzen teilen während des Rolling-Checks die
bisherigen 0,5 CPU. Individuelle Advertise-Adressen und Actions werden geprüft;
Shopping/Gateway behalten Container-ID, Image-ID und Startzeit. Drei
Sättigungszyklen über eine Registry-TTL zeigen begrenzte aktive/wartende Calls,
null SDK-Pending und lebende Control-Leases. Nach jeweils 5,5 s Ruhe werden
Heap/committed Heap, RSS, cgroup inklusive Dateicache, Tasks und Handles erfasst.

Unter fortlaufenden GETs werden eine neue Action veröffentlicht, Registry-Timeouts
per Pause ausgelöst, Registry-Ausfall bis zum Katalogablauf und eine neue Epoche
geprüft. SIGKILL eines Providers entfernt seine Registrierung erst per TTL;
anschließende Neuerstellung und zweimalige Routenentfernung/-veröffentlichung
konvergieren ohne Consumer-/Gateway-Neustart. Fehler sind separat gezählt,
insbesondere 502 während Providerwechseln und 404 nach Katalogablauf. Es gibt keine
Business-Retries und keine Zusage unterbrechungsfreier Rolling Updates.
SIGTERM bei pausierter Registry drainiert den zugelassenen langsamen Call (200),
weist den Wartenden ab (503) und endet mit `drained=true`, Exit 0.

Image-Inventar, native ELF-Abhängigkeiten, Zertifikate/Gast-Lizenz und fehlendes
Host-JDK/Maven/Rust wurden im tatsächlichen Image geprüft. `curl` bleibt für Health
und Diagnose; 50 Probes sind separat gemessen. Runtime-/SDK-Prüfsummen, feste
Basis/Paketquellen und reproduzierbare Maven-JARs erhalten die Upgrade-Grenze.
Messwerte und Rohdaten stehen unter [benchmarks.md](benchmarks.md).
Bei der offenen Normallast, langsamen Calls und Recovery liefern A–D je Engine
384/384 korrekte Antworten. Geschlossene Gateway-Last verfehlt dagegen in allen
sechs Läufen die 1000-ms-p99-Grenze, teils auch die 1-%-Fehlergrenze.

## Grenzen und nicht ausgeführte Prüfungen

- Relative Übertragung misst Sende-/Transit-/Empfänger-Parsing-Zeit nicht exakt;
  Remote-Arbeit wird durch einen Caller-Abbruch nicht automatisch unterbrochen.
- HTTPS mit eigener Wire-Header-Probe, ungültige Zertifikate, verweigerte DNS-/
  Connect-Capabilities, erzwungene DNS/IP-Wechsel und abgelaufene Shutdown-Grace
  sind in diesem Consumer nicht erneut geprüft. Docker-Smoke nutzt echte DNS-Auflösung.
  DNS-/Connect-/TLS-Verzögerungen wurden nicht gezielt injiziert; die Wire-Probe
  verzögert den Pool und prüft Wiederverwendung sowie einen neuen Connect.
- JUnit läuft auf HotSpot; native Aussagen stammen ausschließlich aus Guest-/
  HTTP-/Docker-Prüfungen. Keine Dahemm-Migration oder Produktions-/Securityfreigabe.
- Kurze wiederholte Lastzyklen zeigen Ressourcen innerhalb der festen Grenzen;
  ohne erzwungenen GC beweisen Heap/RSS-Snapshots keine langfristige lebende
  Retention. Mixed-RSS steigt deutlich; eine isolierte Messung nativen Speichers
  einschließlich JIT liegt nicht vor.
- Healthcheck-Kosten sind für das aktuelle Image gemessen; ein direkter
  Vorher-/Nachher-Vergleich derselben Probes fehlt noch für die Abnahme von #8.
- Buildzeit-Generator, Action-/Routing-Indizes und Raw-Body-Copy-Optimierung sind
  mangels belegtem Nutzen zurückgestellt. Kein zusätzlicher Runtime-Mechanismus.

## Reproduzieren

```bash
./scripts/build.sh /pfad/zur/gepinnten/headless/distribution
./scripts/test-core.sh
python3 -m unittest discover -s scripts/tests -v
# Alle folgenden Befehle jeweils auch mit HOORI_ENGINE=interpreter ausführen:
./scripts/test-hoori-core.sh
python3 scripts/test_control.py
python3 scripts/test_admission.py
python3 scripts/test_budgets.py
python3 scripts/smoke.py
```

Smoke schreibt seine begrenzten Ergebnisse nach `.cache/hoori-micro-check-<pid>.json`
und entfernt ausschließlich sein eigenes Compose-Projekt. Für die vergleichbare
A–D-Messung und getrennte Profilierung siehe [benchmarks.md](benchmarks.md).
