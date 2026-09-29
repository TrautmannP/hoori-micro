# Lokale Vergleichsbenchmarks

`scripts/benchmark.py` verwendet die verifizierte Headless-Release-Distribution und
den echten HTTP-/REST-Transport. `BenchmarkMain` liegt ausschließlich in den
Testklassen: statische Ziele und Snapshots sind keine Framework-API und landen
nicht in den ausgelieferten Service-JARs.

| Variante | Pfad |
|---|---|
| A | Shopping → direkte REST-Echo-Route von Recipes |
| B | Shopping → echter Broker mit festem Testsnapshot → Action-Dispatcher |
| C | Shopping → Produktionsbroker/Discovery → Action-Dispatcher |
| D | dynamisches Gateway → Shopping-Action → Recipes-Action |

Alle verwenden dieselbe Echo-Fachlogik, JSON-Codecs, Payloads, Runtime, Engine und
Transportlimits. A/B isolieren Transport bzw. Broker; C/D verwenden den tatsächlichen
Microservice-Lifecycle. A/B/C halten Registry und unbenutztes Gateway idle; ihr
Verbrauch gehört weiterhin zur ausgewiesenen Gesamtsumme. Die vier Rollen erhalten
je 0,5 CPU, 256 MiB Containerspeicher und 32 MiB logisches Guest-Heap-Limit:
zusammen 2 CPU und 1 GiB. Das erzeugt keine CPU-Parallelität im einzelnen Guest.
Pools: vier Verbindungen insgesamt/pro Origin, Server: 32, Client-Timeout: 2 s,
Request-Timeout: 10 s. Sicherheits- und Containergrenzen bleiben aktiv; zusätzliche
Benchmark-Ports liegen nur auf Loopback. Keine Credentials, Redirects oder Retries.

Nach `scripts/build.sh`:

```bash
python3 scripts/benchmark.py --output .cache/baseline-mixed.json
python3 scripts/benchmark.py --engine interpreter --output .cache/baseline-interpreter.json
# Größerer Katalog: zusätzliche, unaufgerufene Registrierungen, keine zusätzlichen CPUs.
python3 scripts/benchmark.py --catalog-instances 32 --output .cache/catalog-32.json
# Getrennte Instrumentierung mit hoori stats; keine vergleichbare Zeitmessung.
python3 scripts/benchmark.py --profile --repeats 1 --output .cache/profile.json
```

Das Skript baut die vorbereiteten Images vor der Messung. Jede Variante/Wiederholung
startet frische Prozesse in einem eigenen Compose-Projekt; die Reihenfolge ist mit
`--seed` reproduzierbar gemischt. Vorab gespeicherte Akzeptanzgrenzen: p99 ≤ 1000 ms,
höchstens 1 % Fehler einschließlich nicht gestarteter Ankünfte. Die Überlastphase
soll diese Grenze auch überschreiten dürfen: schnelle Fehler sind kein Gewinn.
Messdateien werden nicht überschrieben, abgeschlossene Phasen bleiben bei Fehlern
erhalten; nur das eigene Compose-Projekt wird entfernt.

Nach Warmup folgen geschlossene kleine Last, offene große Payloads (8192 Zeichen),
Dauerlast, 250-ms-Abhängigkeit, kurze Überlast, Idle und normale Recovery. Offene
Last hat eine vorgegebene Ankunftsrate und keine Generator-Warteschlange: belegte
Worker zählen als `not_started`. `offered`, `started`, `successful`, `failed` und
Statuscodes bleiben getrennt. Eine 200-Antwort mit falschem Echo gilt als Fehler.
Offene Latenzen beginnen beim geplanten Ankunftszeitpunkt; Generator-Verzug und
Generator-CPU werden ausgewiesen. Durchsatz verwendet die tatsächliche Laufzeit
einschließlich noch laufender Antworten. Den Generator vor höheren Raten anhand
dieser Werte auf Sättigung prüfen.

Pro Rolle und insgesamt werden CPU-Zeit, Guest-Allokationen, GC, Tasks, offene
HTTP-Verbindungen und Service-Handles erfasst. Runtime-Snapshots liegen außerhalb
der Zeitmessung; `--profile` nimmt zusätzlich Snapshots während der Last. Ihre
Kosten und Hintergrundarbeit sind in den Zählerdifferenzen
enthalten. Sie sind keine isolierte Allokation eines einzelnen Handlers. Die
Verbindungsprobe zählt ihre eigene Verbindung/Anfrage mit. Die alte SDK-Baseline
hat keine Pool-Statistik: `http_pool_pending_acquires: null` bezeichnet fehlende
Messbarkeit, nicht null Wartende. Diese Sicht wird mit dem qualifizierten SDK-Pin
aus #8 ergänzt.

Guest-Heap ist logische Belegung; committed Heap ist dessen physische backing
storage. RSS enthält auch JIT, native Provider und VM-Metadaten. Cgroup
`memory.current` umfasst Prozesse, Kernel und Dateicache; `memory.stat` weist u. a.
`anon`, `file`, `kernel`, `sock` und `slab` aus. Dateicache wird nicht abgezogen.
Die regelmäßig gelesene Spitze ist eine **beobachtete**, keine garantierte Spitze.
Imagegröße ist Dockers unkomprimierte Imagegröße, kein RSS-Maß. Startup misst
Compose-Start bis lokale Healthchecks; Discovery-Konvergenz wird zusätzlich vor
der Last verlangt. Container-/Image-IDs, tatsächliche Limits, Runtime-Receipt,
Commit, Arbeitsbaumstatus und Artefakthashes stehen in der Ergebnisdatei.
RSS-Summen können gemeinsam genutzte Seiten mehrfach zählen.

Vergleiche nur gleiche Einstellungen und Messmodi, berichte Median und Spannweite
der Wiederholungen. Ein Kandidat muss die Streuung übersteigen und die vorab gesetzten
Grenzen einhalten. SDK-Upgrades zuerst ohne Framework-Optimierung messen.
Diese lokalen Läufe sind keine Produktions-/Sicherheitsfreigabe. Echte parallele
Replikate und wiederholte Rolling-/Recovery-Zyklen werden mit #10 abgenommen.

## Ausgangsmessung, 29. September 2026

[Mixed-Rohdaten](benchmarks/baseline-550d608f-mixed.json.gz): drei frische Läufe je
Variante, Warmup 10 s, sechs Lastphasen je 6 s und Idle 3 s; sonst die obigen
Defaults. Runtime `550d608f`, bestehender Framework-Stand `abb40cf` plus Test-Fixture;
die genauen Artefakthashes stehen im komprimierten JSON.

| Variante | erfolgreiche/s, geschlossene Last: Median [min–max] | p99-Median, ms | Shopping-RSS nach Idle, Median MiB |
|---|---:|---:|---:|
| A | 116,19 [114,63–118,66] | 294,69 | 181,22 |
| B | 110,27 [104,66–112,54] | 413,80 | 182,89 |
| C | 108,92 [108,71–111,42] | 512,44 | 187,67 |
| D | 94,53 [92,74–95,23] | 1724,55 | 186,74 |

Das Gateway verfehlt hier die vorab gesetzte p99-Grenze. Auch bei offener Normal-/
langsamer Last treten einzelne Ausreißer auf; die kurze Überlastphase enthält
Timeouts und nicht gestartete Ankünfte. Alle anschließenden Recovery-Phasen liefern
je 24 korrekte Antworten ohne Fehler. Daraus folgt keine allgemeine Recovery- oder
Performancefreigabe; die Rohdaten enthalten sämtliche Phasen und Abweisungen.

[Interpreter-Rohdaten](benchmarks/baseline-550d608f-interpreter.json.gz), mit denselben
Einstellungen und ebenfalls drei frischen Läufen je Variante:

| Variante | erfolgreiche/s: Median [min–max] | p99-Median, ms | Shopping-RSS nach Idle, Median MiB |
|---|---:|---:|---:|
| A | 96,63 [95,13–99,63] | 300,19 | 24,06 |
| B | 90,33 [85,58–91,55] | 773,28 | 24,92 |
| C | 88,85 [87,78–90,81] | 711,10 | 25,86 |
| D | 82,48 [77,87–85,15] | 1983,64 | 26,08 |

Der deutlich andere RSS-Bedarf ist eine Engine-Beobachtung auf diesem Runtime-Pin,
kein gemessener Gewinn einer Framework-Änderung. Auch hier liefern alle normalen
Recovery-Phasen 24 korrekte Antworten ohne Fehler.
[32 zusätzliche Registrierungen](benchmarks/catalog-32-550d608f-mixed.json.gz)
wurden als separate Mixed-Kontrolle für C/D geprüft (ein frischer Lauf, Warmup 6 s,
Lastphasen 4 s, Idle 2 s); beide Recovery-Phasen liefern 16 korrekte Antworten.
Dieser kurze Lauf belegt die ausführbare größere Katalogkontrolle, keine
statistisch belastbare Verbesserung gegenüber der kleinen Baseline.

[Getrennte Instrumentierung](benchmarks/profile-550d608f-mixed.json.gz) für C mit
32 zusätzlichen Registrierungen (Warmup 5 s, Phasen 3 s, Idle 2 s) enthält Live-
Snapshots und vollständige `hoori stats`-Ausgaben. Shopping zeigt dabei in den
Lastproben bis zu 13 aktive Guest-Tasks, in der normalen Recovery bis zu sechs;
die Profiling-Latenzen sind wegen zusätzlicher Probes kein Zeitvergleich.
