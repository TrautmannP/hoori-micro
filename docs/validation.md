# Validierung und offene Abnahme

Stand: **2. Oktober 2026**. Qualifizierter Satz: `d8906e6`, sauberer Headless-
Release für `x86_64-unknown-linux-gnu`; VM, Guest Base und sämtliche SDK-JARs aus
derselben Distribution, vollständiger Pin in `hoori.lock.json`. Temurin 21.0.6,
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
