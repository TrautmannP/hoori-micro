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
Messbarkeit, nicht null Wartende. Der qualifizierte SDK-Pin aus #8 enthält die API;
die bisherige Vergleichsfixture lässt den noch nicht erfassten Zähler explizit `null`.
Beide Pools werden mit #4 sichtbar gemacht.

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
