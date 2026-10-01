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
# Wechselnde öffentliche Routen; Aufbau und Konfliktprüfung im echten Gateway:
python3 scripts/benchmark.py --variants D --public-routes 64 --repeats 1 --output .cache/routes-64.json
# Getrennte Instrumentierung mit hoori stats; keine vergleichbare Zeitmessung.
python3 scripts/benchmark.py --profile --repeats 1 --output .cache/profile.json
# Ruhiger Kontrollverkehr vor Überlast, mit nachgewiesen gleicher Instanzmenge:
python3 scripts/benchmark.py --variants C --stable-catalog --idle 12 --output .cache/control-idle.json
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
Messbarkeit, nicht null Wartende. Seit der #4-Kontrolle erfasst die Fixture beide
Pools und ihre Summe; frühe Rohdaten behalten die damaligen `null`-Werte.

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
Replikate und wiederholte Rolling-/Recovery-Zyklen sind separat unter
[validation.md](validation.md) und am Ende dieses Dokuments erfasst. Die folgenden
Abschnitte sind datierte Messstände mit jeweils eigenen Pins und Einstellungen.

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

## Discovery-Protokoll 2, 30. September 2026

[Gemessener C/D-Kandidat](benchmarks/issue-2-550d608f-mixed.json.gz): unveränderter
Runtime-/SDK-Pin, gleiche Mixed-Einstellungen wie die Ausgangsmessung, drei frische
Läufe je Variante. Erfolgreiche/s bei geschlossener Last: C **103,22 [92,67–104,20]**,
D **85,17 [69,84–95,72]**; p99-Median **599,62/1901,18 ms**. Der beobachtete
Gesamtdurchsatz ist niedriger; das Gateway verfehlt weiterhin die Grenze.
Die sechs Recovery-Phasen liefern je 24 korrekte Antworten ohne Fehler.
Die wechselnde Fremdregistrierung verhindert viele unverändert-Antworten; zusätzliche
Versionsfelder erhöhen dann Antwortbytes. Das ist **keine Performancefreigabe**.

Für pure Erneuerungen gibt es zusätzlich je einen frischen C-Lauf mit
`--stable-catalog --seconds 2 --warmup 5 --idle 12 --repeats 1`, einmal mit
`--catalog-instances 32`. Der ruhige Abschnitt liegt nach Warmup und vor Überlast;
vor/nach ihm müssen exakt dieselben 3/35 Instanzen vollständig registriert sein.
Damit kann der bekannte Registrar-Timeout aus #4 keine vermeintliche Einsparung
vortäuschen. Rohdaten: [alt, klein](benchmarks/issue-2-control-old-small.json.gz),
[neu, klein](benchmarks/issue-2-control-new-small.json.gz),
[alt, groß](benchmarks/issue-2-control-old-large.json.gz),
[neu, groß](benchmarks/issue-2-control-new-large.json.gz).

| Registry, ruhiger Abschnitt | klein: alt → neu | 32 zusätzliche Instanzen: alt → neu |
|---|---:|---:|
| HTTP-Requests in rund 12 s, inklusive Probes/Healthchecks | 22 → 22 | 153 → 214 |
| CPU-ms / HTTP-Request | 10,88 → 9,43 | 16,05 → 3,04 |
| Guest-Allokation KiB / HTTP-Request | 15,41 → 12,42 | 38,88 → 14,93 |
| empfangene / gesendete Service-Bytes | 5782/22996 → 4648/19832 | 39188/611071 → 54016/845593 |
| beobachteter Guest-Heap nach Abschnitt, MiB | 0,68 → 0,58 | 0,66 → 1,36 |
| Registry-RSS nach Abschnitt, MiB | 54,32 → 53,99 | 56,70 → 57,89 |

Der externe Generator meldet seine zusätzlichen Anbieter weiterhin per Legacy-PUT
seriell an und wartet erst danach 2 s. Die schnellere Registry verarbeitet im großen
Katalog deshalb mehr davon; absolute Bytes sind dort kein Vergleich bei gleicher
Ankunftsrate. CPU/Allokationen sind zusätzlich auf tatsächlich gezählte HTTP-Requests
bezogen. Die beiden echten Framework-Anbieter verwenden kleine Leases. Einmalige
Läufe sind beschreibende Kontrollen, keine statistische Gewinnfreigabe. Der Heap
enthält auch noch nicht gesammelten Garbage; dies ist keine Live-Retentionmessung.
Ein RSS-Gewinn ist nicht belegt. Genau ein serialisierter Registry-Snapshot wird
gehalten; die JUnit-Prüfung belegt dessen Objekt-/Byte-Wiederverwendung bei Leases.

## Abhängigkeitssichten, 30. September 2026

Je ein frischer C-Lauf vor/nach #3 mit denselben stabilen Einstellungen wie oben
(`seconds=2`, `warmup=5`, `idle=12`), klein und mit 32 zusätzlichen Services.
Ausgangspunkt `24c2a8b`; Runtime/SDK weiterhin `550d608f`. Beide Stände verwenden
dieselbe erweiterte Test-Fixture: `/bench/runtime` zählt zusätzlich tatsächlich
gehaltene Broker-Instanzen/Actions. Fixture- und Generator-Hashes stimmen in allen
vier Dateien überein; Registry-Instanzmengen bleiben vor/nach dem ruhigen Abschnitt
exakt 3/35. Rohdaten: [alt, klein](benchmarks/issue-3-control-old-small.json.gz),
[neu, klein](benchmarks/issue-3-control-new-small.json.gz),
[alt, groß](benchmarks/issue-3-control-old-large.json.gz),
[neu, groß](benchmarks/issue-3-control-new-large.json.gz).

| Nach ruhigem Abschnitt | klein: alt → neu | 32 zusätzliche Services: alt → neu |
|---|---:|---:|
| Shopping: gehaltene Instanzen / Actions | 3/3 → 1/1 | 35/35 → 1/1 |
| Recipes ohne Abhängigkeiten: Instanzen / Actions | 3/3 → 0/0 | 35/35 → 0/0 |
| Shopping: beobachteter Guest-Heap, MiB | 0,571 → 0,562 | 0,622 → 0,561 |
| Shopping: RSS, MiB | 59,734 → 56,438 | 60,586 → 56,711 |
| Shopping: empfangene / gesendete Service-Bytes | 1618/12604 → 1870/13143 | 1630/12611 → 1882/13147 |
| Registry: CPU-ms / HTTP-Request | 9,363 → 9,889 | 2,868 → 3,054 |

Die Katalogbegrenzung ist direkt nachgewiesen; einmalige RSS-/Heap-Beobachtungen
belegen keinen allgemeinen RAM-Gewinn, der Heap enthält Garbage. Stabile Kataloge
liefern bereits seit #2 überwiegend 204; die zusätzlichen Sicht-Header erhöhen hier
Bytes und Registry-Kosten. Erfolgreiche/s bei geschlossener Last klein
108,35 → 112,45, groß 105,54 → 109,71; die neuen Läufe enthalten jeweils einen
Fehler (unter 1 %). Alle vier Recovery-Phasen liefern je acht korrekte Antworten.
Diese kurzen Kontrollen sind keine statistische Durchsatz-/Performancefreigabe.

[Isolierte native Lookup-Kontrolle](benchmarks/issue-3-lookup-550d608f.json.gz): ein
Prozess je Engine, ein Warmup-Paar und fünf Paare mit je 100000 Auswahlen,
abwechselnd `echo`/nicht angebotene Action. Median [min–max] je Auswahl: Mixed
bei 1/35 Instanzen **1,75 [1,70–1,78] / 2,22 [2,20–2,24] µs**, Interpreter
**4,51 [4,38–4,51] / 39,95 [39,55–40,39] µs**. Diese CPU-Kontrolle läuft außerhalb
Docker und misst keine Transportlatenz. Beim nun tatsächlich gehaltenen einzelnen
Eintrag ist ein zusätzlicher Suchindex nicht begründet; der lineare Scan bleibt.

```bash
runtime=$PWD/.docker-context/runtime
cp=$PWD/framework/target/classes:$PWD/framework/target/test-classes
for jar in "$runtime"/lib/*.jar; do cp="$cp:$jar"; done
"$runtime/bin/hoori" run --engine mixed --allow-environment-read \
  --class-path "$cp" --arg lookup hoori/micro/BenchmarkMain
# Dasselbe mit --engine interpreter.
```

## Qualifizierter SDK-Pin, 30. September 2026

Der vollständige saubere Satz VM/Guest Base/SDK wechselt von `550d608f` auf
`3254301`, ohne Framework-Optimierung. [Kleiner](benchmarks/issue-8-sdk-3254301-small.json.gz)
und [großer](benchmarks/issue-8-sdk-3254301-large.json.gz) C-Kontrolllauf verwenden
dieselbe kompilierte Fixture und alle Last-/Ressourceneinstellungen der #3-Kontrollen
oben. Der Generator setzt fehlende Pool-Proben weiterhin auf `null`; die Änderung
verändert diese Messungen nicht. Alle Artefakt-/Runtime-Identitäten sind aufgezeichnet.

| Beobachtung, je ein frischer Mixed-Lauf | klein: alter → neuer SDK | 32 zusätzliche Services: alter → neuer SDK |
|---|---:|---:|
| erfolgreiche/s, geschlossene Last | 112,45 → 119,76 | 109,71 → 116,38 |
| p99, ms | 550,29 → 571,05 | 507,90 → 189,64 |
| Shopping-Heap nach ruhigem Abschnitt, MiB | 0,562 → 0,562 | 0,561 → 0,563 |
| Shopping-RSS nach ruhigem Abschnitt, MiB | 56,438 → 56,930 | 56,711 → 57,559 |

Die geschlossene Phase enthält weiterhin je einen Fehler (<1 %), beide neuen
Recovery-Phasen je acht korrekte Antworten ohne Fehler. Shopping hält einen
Katalogeintrag, Recipes keinen; vollständige Registry-Mengen bleiben 3/35.
Einmalige Läufe sind eine separate SDK-Kontrolle, keine statistische Gewinnfreigabe.
Pending-Zähler sind in dieser Fixture **noch nicht erfasst**, obwohl der neue SDK
sie anbietet. Pool-Isolation/Admission/Budget-Wire-Protokoll folgen separat in #4–#6;
Basisimage-Digest und weitere Image-Abnahme aus #8 bleiben offen.

## Control-Isolation, 30. September 2026

Framework `43c0b6e` vor #4 und neuer Stand verwenden denselben sauberen SDK-Satz
`3254301` sowie dieselbe kompilierte Fixture (`78692ef128e2…`) und denselben Generator.
Der alte Stand erhielt ausschließlich Messzugriffe für seinen vorhandenen Pool;
sein C-Transport, Lifecycle und seine SDK-Pending-Policy bleiben unverändert.
Die Patches stehen in den Rohdaten. Je ein frischer Mixed-C-Lauf mit denselben
Limits und 2 s Last/5 s Warmup/12 s Ruhe wie oben, keine parallel laufenden eigenen
Tests während der Messung. Rohdaten: [alt klein](benchmarks/issue-4-control-old-small.json.gz),
[neu klein](benchmarks/issue-4-control-new-small.json.gz),
[alt mit 32 zusätzlichen Services](benchmarks/issue-4-control-old-large.json.gz),
[neu mit 32 zusätzlichen Services](benchmarks/issue-4-control-new-large.json.gz).

| Beobachtung | klein: alt → neu | 32 zusätzliche Services: alt → neu |
|---|---:|---:|
| Control-Ruhe: CPU gesamt, ms | 537,13 → 552,50 | 956,68 → 940,75 |
| RSS aller vier Rollen nach Ruhe, MiB | 218,238 → 218,961 | 223,945 → 224,754 |
| Task-Snapshots aller Rollen nach Ruhe | 20 → 19 | 20 → 19 |
| aktive + idle ausgehende Poolverbindungen nach Ruhe | 2 → 2 | 2 → 2 |
| erfolgreiche/s, geschlossene Last | 117,33 → 118,57 | 117,65 → 113,92 |
| p99, ms | 410,25 → 293,10 | 552,05 → 607,12 |
| Recovery-p99, ms | 10,78 → 10,64 | 15,19 → 10,12 |

Shopping/Recipes halten nach Ruhe je eine Registry-Verbindung: vorher im gemeinsamen,
jetzt im separaten Control-Pool. Datenverbindungen sind nach 12 s Ruhe abgelaufen;
Control-Pending ist immer null. Shopping hat einen zusätzlichen Reaper-Task, Registry
und das in C inaktive Gateway je einen Registrar weniger. Task-Zahlen sind Snapshots
während Metrikprobes, keine gemessenen Spitzen. Das Verbindungsbudget steigt pro
Discovery-Service von vier gemeinsamen auf vier Daten- plus eine Control-Verbindung;
es bleiben dieselben Gesamt-CPU-/RAM-Limits. Alte Pending-Policy: SDK-Default 64;
neu: ausdrücklich vier für Daten, null für Control. Raw-Proben erfassen nun diese
Zähler, einschließlich Ablehnungen, statt `null` für ungemessene Werte.

Alle vier Recovery-Phasen liefern acht korrekte Antworten ohne Fehler. Die kleine
geschlossene Phase enthält alt/neu je einen Fehler (<1 %), die große keinen.
Die höheren RSS-Werte und kleinen CPU-/Durchsatzunterschiede sind Einzellauf-
Beobachtungen, **kein Performancegewinn oder Retention-/Lastfreigabe**.

[Native Fault-Rohdaten](benchmarks/issue-4-control-faults.json.gz) enthalten beide
Engines, Runtime-/Fixture-/Framework-Identitäten und den kontrollierten Peer-Verlauf.
Auf gleichem SDK beendet der alte Framework-Stand nach einem 300-ms-Timeout den
Registrar und meldet weiter Ready. Neu erholen sich ein einzelner und zwei
aufeinanderfolgende Timeouts; nach unbekannter Lease folgt volle Registrierung,
etwa 700/677 ms (Mixed/Interpreter) nach Beginn der letzten verzögerten Lease.
SIGTERM während einer langsamen erneuten Registrierung wartet auf ein 150 ms
zurückgehaltenes DELETE, beendet mit Exit 0/`drained=true` und leeren Pools.
Der damalige #4-Docker-Smoke prüfte zusätzlich echte Registry-Leases bei Daten-Sättigung
über eine TTL und den Drain aktiver plus wartender Datenaufrufe. Mit #5 werden vor
Encoding Wartende beim Stop abgewiesen. CPU-unkooperative Handler
bleiben Hooris Single-Carrier-Grenze; Pool-Trennung schafft keine Präemption.

## Admission, 30. September 2026

Vorher: `3667c144`, nachher: derselbe Stand mit der Admission-Änderung; beide auf
dem sauberen SDK-Satz `3254301`. Je drei frische Mixed-C/D-Läufe, gleicher Generator,
8 Worker, pro Rolle 0,5 CPU/256 MiB und 32 MiB Guest-Heap, Pool 4, 5 s Warmup,
2 s je Phase und 3 s Ruhe. Keine eigenen Tests/Builds während der Lastphasen.
Vorher warteten bis zu vier Calls im SDK; nachher vor Encoding in der Framework-Queue
(4 aktive/4 wartende Calls, SDK-Pending 0). Die neue Fixture ergänzt Admission-Zähler;
Business-Pfade und Generator bleiben gleich, Fixture-Hashes unterscheiden sich.
Rohdaten mit Receipt, Hashes und Quellpatch:
[vorher](benchmarks/issue-5-before-3254301-mixed.json.gz),
[nachher](benchmarks/issue-5-after-3254301-mixed.json.gz).

| Median, vorher → nachher | C: direkter Broker | D: zusätzlich Gateway |
|---|---:|---:|
| erfolgreiche/s, geschlossene Last | 121,34 → 122,70 | 109,51 → 102,68 |
| geschlossene p99, ms | 502,08 → 591,19 | 1628,68 → 903,44 |
| System-CPU pro Erfolg, ms | 7,191 → 7,183 | 11,833 → 13,261 |
| System-Allokation pro Erfolg, KiB | 21,472 → 21,599 | 33,735 → 34,162 |
| offene Normallast (4/s): p99, ms | 33,20 → 14,89 | 21,90 → 22,26 |
| Recovery-p99, ms | 10,34 → 10,74 | 16,07 → 16,43 |
| Guest-Heap aller Rollen nach Ruhe, MiB | 4,548 → 4,852 | 2,827 → 2,734 |
| RSS aller Rollen nach Ruhe, MiB (Bereich) | 247,469–367,441 → 250,859–372,633 | 381,070–381,336 → 388,051–389,488 |
| cgroup-Speicher nach Ruhe, MiB (Bereich) | 205,535–325,742 → 209,297–331,832 | 338,828–339,781 → 346,910–352,496 |

Die geschlossene C-Phase hat vorher/nachher insgesamt je einen Fehler; D zwei/fünf.
Je Variante/Stand liefern Normallast, langsame Calls und Recovery jeweils 24 korrekte
Antworten ohne Fehler. Die Burst-Phasen haben jeweils 102 Erfolge, sechs Timeout-
Fehler und 276 vom begrenzten Generator nicht gestartete Ankünfte; diese sind keine
erfolgreiche Arbeit oder Server-Abweisungen. Nachher zeigen die Snapshot-Zähler nach
Ruhe null Framework-/SDK-Wartende; vorher gab es keine Framework-Zähler.
Task-Snapshots sind vorher/nachher gleich.

Unter geschlossener Gateway-Last kostet Admission hier rund 6,2 % Durchsatz und
12,1 % mehr System-CPU pro Erfolg. Die häufige Permit-Prüfung nimmt keine weitere
Queue-Sperre; Acquire/Release behalten ihre notwendige Synchronisation. Die offene
Normallast bleibt bei 4/s ohne Fehler. Das ist eine Kostenkontrolle, kein
Performancegewinn oder allgemeine Freigabe. Registry-RSS streut in beiden Ständen
zwischen rund 63 und 185 MiB; Heap/RSS/cgroup sind Snapshots ohne erzwungenen GC,
keine Messung lebender Retention. Die kurze Ruhe beweist keinen langfristigen Plateauwert.

[Native Admission-Rohdaten](benchmarks/issue-5-admission-native.json.gz) belegen in
beiden Engines separat: begrenzte Calls/Wartende, Abweisung vor DTO-Read bzw.
Encoding/HTTP-Exchange, zwei Lastspitzen ohne Neustart, Budgetverbrauch beim Encoding/
Warten und Stop mit Abweisung Wartender, Drain zugelassener Arbeit und leeren Gates/Pools.
Health und Discovery bleiben erreichbar. Der SDK hat den begrenzten Raw-Body vor
eingehender Admission bereits gelesen; Wire-Restbudgets über Service-Hops fehlten
zu diesem Messzeitpunkt noch (aktueller Stand unten).

## Budgets, Gateway und Image, 2. Oktober 2026

Der neue saubere Runtime-/Guest-/SDK-Satz `d8906e6` wurde zuerst mit unverändertem
Framework-JAR (`1da7aa9f…`), identischer Fixture und identischem Generator gemessen:
[vorher, `3254301`](benchmarks/issue-11-sdk-before-3254301.json.gz),
[nachher, `d8906e6`](benchmarks/issue-11-sdk-after-d8906e6.json.gz). Je ein frischer
Mixed-C/D-Lauf, 5 s Warmup, 2 s je Phase und 3 s Ruhe, sonst die obigen Limits.
Dies kontrolliert den gesamten Runtime-/SDK-Wechsel, keine einzelne SDK-Methode.

| Einzellauf, vorher → nachher | C | D |
|---|---:|---:|
| erfolgreiche/s | 119,31 → 92,37 | 102,00 → 74,33 |
| geschlossene p99, ms | 704,82 → 865,90 | 1423,40 → 2059,70 |
| System-CPU ms/Erfolg | 7,80 → 10,10 | 13,21 → 18,05 |
| System-Allokation KiB/Erfolg | 21,69 → 22,45 | 34,04 → 35,63 |
| System-RSS nach Ruhe, MiB | 256,26 → 259,15 | 274,29 → 274,83 |
| cgroup-Speicher nach Ruhe, MiB | 220,07 → 216,33 | 230,95 → 232,36 |

Der Wechsel kostet in diesen Kontrollen Durchsatz und CPU; Einzelläufe beweisen
keine allgemeine Regression oder Verbesserung. Startup liegt jeweils bei rund
11,5 s. Die unkomprimierte Shopping-Imagegröße steigt von 112066111 auf
112115254 Bytes; Imagebytes sind kein RAM-Maß.

Der Framework-Kandidat basiert auf `99590f2` plus dem
[ausführbaren Quellpatch](benchmarks/issue-11-source.patch.gz). JAR (`6d8fcd9d…`),
Fixture (`85b5615c…`), Generator, Receipt und Container-Identitäten stehen in den
Rohdaten: [Mixed](benchmarks/issue-11-final-mixed.json.gz),
[Interpreter](benchmarks/issue-11-final-interpreter.json.gz). Je drei frische Läufe
A–D mit denselben kurzen Einstellungen wie die SDK-Kontrolle, wechselnde Reihenfolge,
keine eigenen Builds/Tests während der Last. CPU und Allokation enthalten alle
vier Rollen, Hintergrundarbeit, Probes und Fehler; der Nenner zählt nur Erfolge.

| Engine / Variante | erfolgreiche/s: Median [min–max] | p99: Median [min–max], ms | CPU ms/Erfolg | Allokation KiB/Erfolg |
|---|---:|---:|---:|---:|
| Mixed A | 103,14 [102,84–114,39] | 300,71 [289,74–444,48] | 9,451 | 21,327 |
| Mixed B | 83,40 [78,11–101,49] | 647,41 [620,38–781,05] | 11,361 | 22,596 |
| Mixed C | 97,70 [96,62–99,33] | 801,06 [393,74–1128,20] | 9,917 | 22,814 |
| Mixed D | 78,22 [65,52–83,59] | 2031,00 [1696,24–2061,77] | 17,475 | 35,812 |
| Interpreter A | 108,43 [104,45–120,37] | 188,43 [158,64–203,12] | 7,584 | 21,047 |
| Interpreter B | 96,85 [93,23–103,76] | 295,59 [202,06–486,53] | 8,676 | 22,057 |
| Interpreter C | 99,17 [97,96–107,57] | 530,26 [255,61–817,84] | 8,908 | 22,718 |
| Interpreter D | 90,32 [84,74–90,83] | 2021,59 [2016,38–2050,97] | 14,025 | 35,497 |

D verfehlt in allen sechs Läufen die p99-Grenze; einzelne Läufe auch die 1-%-Fehlergrenze.
Mixed-C überschreitet einmal 1000 ms. Offene große/kleine Last, langsame Calls und
Recovery liefern dagegen je Engine insgesamt **384/384 korrekte Antworten**,
p99 höchstens **315,59/334,19 ms** (Mixed/Interpreter). Die zwölf Burst-Phasen je
Engine zählen 1536 Ankünfte: **409/408 Erfolge**, **23/24 Fehler**, jeweils **1104
nicht gestartete Ankünfte**. Letztere sind begrenzte Generator-Abweisungen, keine
Server-Erfolge. Generator-CPU bleibt pro Phase unter 69 ms, p99-Startverzug unter
3,24 ms. Die kurze Phase und Mixed-Streuung begründen keinen Performancegewinn
gegen die einmalige SDK-Kontrolle; B/C sind kein belastbarer Nachweis eines
positiven Discovery-Effekts. Erfolgreiche/min sowie p50/p95 und rollenweise CPU/GC/
Tasks/Verbindungen stehen vollständig im JSON.

| Nach letzter Ruhe, alle Rollen | Guest-Heap / committed: Median MiB | RSS-Bereich MiB | cgroup-Bereich MiB |
|---|---:|---:|---:|
| Mixed A | 4,15 / 7,96 | 238,91–240,50 | 196,71–198,24 |
| Mixed B | 3,57 / 7,21 | 245,82–247,40 | 203,43–204,83 |
| Mixed C | 3,37 / 7,51 | 260,37–262,66 | 218,53–220,75 |
| Mixed D | 2,96 / 7,49 | 280,61–400,43 | 238,39–358,59 |
| Interpreter A | 4,35 / 6,94 | 96,13–96,44 | 72,32–77,55 |
| Interpreter B | 4,17 / 6,77 | 100,09–100,19 | 76,04–80,83 |
| Interpreter C | 3,45 / 5,90 | 103,95–104,46 | 79,23–81,19 |
| Interpreter D | 3,59 / 6,39 | 108,49–108,98 | 83,95–84,12 |

SDK-Wartende sind nach Ruhe überall null; vorhandene Framework-Gates ebenfalls.
Startup liegt bei 11,49–11,71 s, mit einem Mixed-A-Ausreißer von 23,99 s.
Der Registry-RSS erklärt einen großen Teil der Mixed-D-Streuung; ohne erzwungenen
GC oder getrennte Messung nativen Speichers ist das keine lebende Retentionmessung.

### Wechselnde öffentliche Routen und native Grenzen

[D mit 64 zusätzlichen öffentlichen Routen](benchmarks/issue-11-routes64-mixed.json.gz)
ändert deren Pfade alle 2 s: ein frischer uninstrumentierter Mixed-Lauf mit denselben
Einstellungen. Geschlossen **81,95 Erfolge/s**, p99 **1311,43 ms**, System-CPU
**16,157 ms/Erfolg**, Allokation **36,747 KiB/Erfolg**; Normallast und Recovery je
8/8 korrekt, keine Fehler. Der Gateway hält nach Ruhe drei Instanzen/66 Actions,
0,70 MiB beobachteten Guest-Heap und null Wartende. Das ist eine Größenkontrolle,
kein Gewinn gegenüber den drei kleinen D-Läufen.

[Getrenntes `--profile`](benchmarks/issue-11-routes64-profile.json.gz), gleicher D64-Aufbau:
Live-Samples zeigen maximal 14 Gateway-Tasks und 1,96 MiB dessen logischen Heap.
Die vollständigen `hoori stats` enden pro Rolle mit null offenen Handles,
`tasks_created == tasks_completed`, einem Carrier und null OOM; GC wird natürlich
ausgelöst. Die instrumentierten Latenzen sind kein Zeitvergleich. Das Profil
isoliert keine dominierende Parameter-Kopie oder Lookup-Kosten; Raw-Body-Copy und
zusätzliche Indizes bleiben zurückgestellt.

[Native Budget-/Gateway-Prüfung beider Engines](benchmarks/issue-11-budgets-native.json.gz)
belegt die späte SDK-Abtastung nach Pool-Warten, neue/wiederverwendete Verbindungen,
serielle Budgets, Header-/Policy-Grenzen, Cancellation/Recovery und atomare Count-/
Metadatenüberläufe. Semantik und nicht erneut geprüfte Transportfälle stehen in
[validation.md](validation.md). Der kleine Vertrag-Overload und named Publication
verwenden weiterhin dieselben Codecs; ein optionaler Generator bleibt mangels
belegtem Nutzen zurückgestellt.

### Image und gemeinsame Last-/Recovery-Abnahme

[Image-Inventar](benchmarks/issue-11-image-inventory.json.gz): fester amd64-Digest,
signierte Debian-Paketquellen vom 30.09.2026, geprüfte ELF-Abhängigkeiten, Zertifikate,
Guest-Lizenz und Prüfsummen. Kein Host-JDK/Maven/Rust im Laufzeit-Image. Runtime-Satz
17146444 Bytes, vorbereiteter Kontext 17441998 Bytes; Docker-Images unkomprimiert
112109785–112120366 Bytes (**106,92–106,93 MiB**). Kein gemessener Größen-/RAM-Gewinn
und keine Zusage bitidentischer Docker-Layer. Vollständige verifizierte Distribution
und `curl` bleiben erhalten. Je 50 Health-Probes benötigen pro Probe **10,33/6,78 ms
cgroup-CPU**, **20,33/13,30 ms Wandzeit** (Mixed/Interpreter); das enthält Server,
`curl` und Hintergrundarbeit, keinen isolierten `curl`-Kostenvergleich.

[Mixed-Smoke](benchmarks/issue-11-smoke-mixed.json.gz) und
[Interpreter-Smoke](benchmarks/issue-11-smoke-interpreter.json.gz) verwenden die realen
Service-Images mit denselben CPU-/RAM-Grenzen. Alte/neue Provider teilen während
Rolling zusammen 0,5 CPU; Consumers und Gateway behalten Image-/Container-/
Prozessidentität. Drei Lastspitzen über Registry-TTL, Registry-Timeout/-Ablauf,
Provider-SIGKILL/TTL und zwei Routenwechsel konvergieren ohne Consumer-Neustart.
SIGTERM mit aktiver/wartender Arbeit und pausierter Registry liefert 200/503,
`drained=true`, Exit 0 und leere Gates/Pools.

| Nach Rolling-Recovery / 5,5 s Ruhe | Mixed RSS MiB | Interpreter RSS MiB |
|---|---:|---:|
| registry | 180,20 | 23,27 |
| recipes | 58,62 | 24,76 |
| shopping | 194,82 | 38,12 |
| gateway | 192,19 | 36,12 |

Alle fünf Ressourcen-Snapshots je Engine bleiben innerhalb der festen Grenzen,
nach Idle sind SDK-/Framework-Wartende null, Tasks/Handles konvergieren.
Fortlaufende bestehende Calls zählen beim Rolling **32/97 Erfolge und 41/31 502**,
beim Provider-Crash **38/57 Erfolge und 152/141 502** (Mixed/Interpreter). Nach
Katalogablauf werden zusätzlich 404 gezählt. Diese Abnahme belegt begrenzte Recovery,
keine unterbrechungsfreie Verfügbarkeit oder langfristige lebende Retention.

```bash
# Jeweils ohne weitere eigene Last/Builds; --engine interpreter getrennt wiederholen:
python3 scripts/benchmark.py --variants ABCD --repeats 3 --seconds 2 --warmup 5 --idle 3 --output .cache/current-mixed.json
python3 scripts/benchmark.py --variants D --public-routes 64 --repeats 1 --seconds 2 --warmup 5 --idle 3 --output .cache/routes64.json
python3 scripts/benchmark.py --variants D --public-routes 64 --profile --repeats 1 --seconds 2 --warmup 5 --idle 3 --output .cache/routes64-profile.json
```
