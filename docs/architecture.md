# Architektur und Entscheidungen

## 1. Ein dünnes Framework oberhalb der Runtime

```text
Dahemm-Actions / explizite Anwendungsdienste
                    ↓
hoori-micro: Service-Definition, Broker, Registry, Gateway, Lifecycle
                    ↓
hoori-concurrent-api / concurrent-http: Tasks, RequestScopes, HttpTasks
                    ↓
hoori-rest-api: Router, Middleware, explizite JSON-Codecs
                    ↓
hoori-http-api: HTTP, Pooling, Bounds, Kontext, Metriken, Drain
                    ↓
Hoori Guest Base / resumierbare Tasks / Host-Netzwerk
```

Abhängigkeiten zeigen nur nach unten. Server und Client sind die echten Hoori-
Implementierungen. Keine Annotationssuche, Laufzeit-Proxies oder automatische DI:
Actions sind Lambdas an einer expliziten `Service`-Definition.

Das optionale [Fassadenbeispiel](../examples/task-facade/README.md) verwendet den
upstream Buildzeit-Processor. Generierte Task-/Scoped-Delegates sind normale
Anwendungsklassen und werden ausdrücklich konstruiert. Der HTTP-Reaktor und sein
Runtime-Classpath bleiben ohne Annotationen/Processor.

Der optionale [Daten-Consumer](../examples/local-data/README.md) bezieht Transaction,
JDBC und Jdbi ebenfalls als originale SDKs. Ein beim Start konstruierter Manager
besitzt pro lokaler REQUIRED-Operation einen exklusiven Handle. Remote-Vorbereitung
liegt vor dieser kurzen Datensatz-/Outbox-Transaktion; der physische Commit liegt
vor der Antwort. Kinder teilen keine Handles, bestätigte Child-Commits sind unabhängig.
UNKNOWN wird nicht wiederholt oder als Rollback dargestellt. Der HTTP-Kern erhält
keine Datenbankabhängigkeit und keinen automatischen Request-Transaktionsrahmen.

## 2. Actions, Registry und Broker

Aufrufer adressieren eine **fachliche Action** (`recipes.get`) mit der per
`dependsOn("recipes", 1)` deklarierten Hauptversion, nie eine Route oder Adresse.

- **Service-Definition:** Aus `Service.named(..).action(..)` entstehen der lokale
  Dispatcher hinter `POST /_hoori/invoke` und der veröffentlichte Katalogeintrag.
  Eine neue Action braucht keine neue Route; der Router bleibt nach `freeze()` fix.
- **Registry** (`hoori.micro.Registry`): In-Memory-Map Instanz-ID → {Service,
  Hauptversion, Advertise-URL, Actions}, begrenzt auf 256 Instanzen, jeder Eintrag mit
  TTL. `PUT /v1/instances/{id}` registriert Metadaten, ein leerer
  `POST /v1/instances/{id}/lease` verlängert die Lease; `DELETE` deregistriert;
  `GET /v1/catalog` liest. Eine unbekannte Lease (404) erlaubt eine volle Neuanmeldung.
- **Heartbeat:** Nach Live und solange Ready erfolgt einmal die volle Registrierung,
  danach die kleine Lease-Erneuerung (Default 2 s mit ±10 % Jitter, TTL 6 s).
  Die erfolgreiche Periode wird auf höchstens die halbe konfigurierte TTL vor Jitter
  begrenzt; Netzwerk- und Scheduling-Zeit brauchen zusätzlich Spielraum. Fehler
  führen zu begrenztem Backoff (höchstens 66 s einschließlich Jitter). Reine Aufrufer
  holen nur den Katalog. Actions/Metadaten sind während eines Service-Laufs eingefroren;
  ein neuer Provider-Prozess registriert seinen neuen Stand.
- **Broker** (pro Service, Bibliothek): hält den zuletzt gültigen Katalog lokal,
  wählt **pro Action und Hauptversion** eine anbietende Instanz (Round Robin) und
  ruft sie direkt über den Hoori-Pool auf. Die Registry liegt nie im Request-Pfad.

Während eines Rolling Updates landet eine neue Action daher nur bei Instanzen, die
sie anbieten. Eine Instanz, die eine Action nicht (mehr) anbietet oder eine andere
Hauptversion hat, antwortet mit 421; der Broker wiederholt nicht automatisch.

**Definiertes Ausfallverhalten:**

| Situation | Verhalten |
|---|---|
| Registry nicht erreichbar | Broker nutzen ihren letzten Katalog weiter, höchstens `HOORI_CATALOG_MAX_AGE_MS` (30 s) nach der letzten erfolgreichen Aktualisierung; danach scheitern Aufrufe mit „no instance“ |
| Registry neu gestartet | Katalog ist eine TTL lang `complete=false`; Broker mit gültigem Katalog behalten ihren bis dahin |
| Instanz stoppt geordnet | Deregistrierung beim Stop; andere Broker sehen das beim nächsten Heartbeat |
| Instanz stürzt ab | Eintrag läuft nach der TTL aus; bis dahin liefert der Aufruf einen Transportfehler (502) |

Heartbeats garantieren keine Erreichbarkeit zwischen zwei Meldungen. Die Registry
ist ein einzelner Prozess ohne Persistenz oder Hochverfügbarkeit.

**Discovery-Protokoll 2:** Broker senden `X-Hoori-Catalog-Protocol: 2` und, sobald ein
Snapshot bekannt ist, `X-Hoori-Catalog-Epoch` plus `X-Hoori-Catalog-Revision`.
Jeder volle JSON-Katalog trägt dieselben `epoch`/`revision`-Werte und `complete`.
Nur Metadaten, Entfernung/Ablauf und der einmalige Vollständigkeitswechsel erhöhen
die Revision; pure Leases verändern sie nicht. Bei exakt passender Epoche/Revision
kommt 204 ohne Body, sonst der volle 200-Katalog. Beide Antworten bestätigen ihre
Identität in denselben Headern. Nur eine zum gesendeten und noch aktuellen Snapshot
passende 204-Antwort erneuert dessen Frische. Eine neue, noch unvollständige Epoche
verlängert den alten vollständigen Snapshot nicht; kleinere Revisionen derselben
Epoche, unvollständige Rückschritte und Antworten der zuletzt verlassenen Epoche
werden verworfen. Ein einziger Registrar verarbeitet Antworten seriell; keine
Delta-Historie oder unbegrenzte Epochenliste.

Upgrade-Reihenfolge: **Registry zuerst, dann Broker/Services/Gateway.** Anfragen ohne
Protokollheader bleiben kompatibel: volle PUT-/GET-Antworten mit additiven JSON-Feldern;
alte Decoder überspringen diese. Andere explizite Protokollversionen und Leases ohne
Version 2 erhalten 426. Neue Broker akzeptieren keine unversionierten alten Registry-
Antworten; ein Registry-Downgrade lässt ihren bisherigen Katalog daher ausaltern.
Die Registry hält genau einen Snapshot plus dessen Bytes. Vor Metadatenannahme muss
der Gesamtkatalog einschließlich Reserve für Revisionsziffern in `HOORI_BODY_BYTES`
passen; sonst 413 ohne Metadaten-/Lease-Änderung. 256 belegte Einträge liefern 429.
Ein bei dieser Gelegenheit fälliger TTL-Purge bleibt wirksam.

**Feste Consumer-Sichten:** `X-Hoori-Catalog-View` wird einmal aus `dependsOn()`
gebildet, z. B. `services=recipes:1`. Die Registry überträgt nur passende Instanzen
und deren Action-Namen, keine HTTP-Publikationsfelder. Anbieter ohne Abhängigkeiten
fordern `none` an. Das Gateway wählt ausdrücklich `public`: nur Actions mit Route
und Permission. Ein Gateway mit eigenen Abhängigkeiten nutzt
`public;services=recipes:1` und erhält zusätzlich deren interne Actions.
Ohne Header bleibt die Legacy-Sicht `all` erhalten. Filter sind keine Autorisierung.

Die Antwort bestätigt die Sicht im selben Header; bedingte Anfragen tragen die
bisherige Sicht in `X-Hoori-Catalog-Known-View`. Erst Epoche, Revision **und Sicht**
erlauben 204. Neue Broker verlangen den Sicht-Header, daher weiterhin Registry
zuerst aktualisieren. Die globale Revision gilt auch für gefilterte Antworten;
eine fremde Metadatenänderung kann einen kleinen vollen Abruf auslösen. Die Registry
legt keine Caches pro Consumer an, sondern filtert vor Encoding und Übertragung.

Grenzen bleiben 256 Instanzen, je 128 Actions und der Gesamt-Byte-Bound; ein Filter
enthält höchstens 32 Abhängigkeiten und 2300 Header-Zeichen. Beim Snapshotwechsel
können alter und neuer Katalog plus ein begrenzter Antwortbody kurz gleichzeitig
leben. Broker halten keine Raw-Bodies; abgelaufene Zeilen werden beim nächsten
Heartbeat/Zugriff durch kleine Versionstoken ersetzt, danach ist ein voller Abruf
nötig. Das Gateway bekommt vorbereitete Routen gemeinsam mit dem Katalog; deren
Aufbau liegt im Registrar, außerhalb des Request-Pfads.
Ziel-URIs und typisierte Action-Namen werden einmal vorbereitet. Die Auswahl bleibt
ein begrenzter linearer Scan; ein Action-Index braucht einen belegten CPU-Nutzen.

## 3. Wire-Protokoll, Gateway und Verträge

Interner Aufruf: `POST /_hoori/invoke`, Header `X-Hoori-Action: recipes.get` und
`X-Hoori-Version: 1`, Body = JSON-Parameter der Action. Name und Version stehen in
Headern statt in einem JSON-Umschlag, damit der Eingabe-Codec den Body direkt und
ohne zweites Parsen liest. Antwort: 200 mit JSON-Ergebnis oder ein Fehlerstatus.
Fachliche Fehler wirft eine Action als `RequestException(status, meldung)`.

Typisierte Verträge (`Action<I, O>`) enthalten nur Name und Codecs. Generische Aufrufe
verwenden `Map` und `JsonTree` (Map, List, String, Long, Double, Boolean, null).
Provider registrieren denselben Vertrag mit `action(contract, handler)`; fremde
Service-Namen werden beim Start abgewiesen. `http(actionName, method, path)` und
`requirePermission(actionName, permission)` binden Metadaten ausdrücklich an die
Action. Ein optionales Buildzeit-Fassadenbeispiel folgt separat; der Core benötigt
keine Annotationen oder generierten Klassen.
Ergebnisse müssen 2xx mit `application/json` (optional `charset=utf-8`) sein; sonst
`ServiceCallException` mit Status, aber ohne Upstream-Body.

**Gateway** (`hoori.micro.Gateway`): Eine Middleware, die für sonst unbekannte Pfade
den vorbereiteten Routing-Snapshot anwendet. Veröffentlicht wird nur, was der Anbieter mit
`http(method, template)` **und** `requirePermission("<service>:<scope>")` markiert
und was die Gateway-Policy gewährt. Die Permission ist auf den eigenen Service-Namen
begrenzt. Konflikte (gleiche Methode, überlappende Templates gleicher Spezifität,
verschiedene Actions) werden im Service beim Start abgewiesen und im Gateway für alle
Beteiligten zurückgehalten. Pfadparameter werden als JSON-Strings übergeben und mit
einem optionalen JSON-Objekt-Body zusammengeführt; Query-Parameter nicht.
Client-Fehler des Anbieters behalten ihren Status, nie ihren Body. Abweichende
Route/Permission derselben Action und Hauptversion während eines Rolling Updates
werden ebenfalls zurückgehalten.

Katalog, Revision und Routing-Tabelle werden als ein unveränderlicher Snapshot
veröffentlicht. Ein Gateway-Request nutzt ihn auch zur Instanzauswahl. Maximal
256 distinct Routendefinitionen und 64 KiB ihrer ASCII-Metadaten sind erlaubt.
Überlauf verwirft das ganze Update, ohne den alten Snapshot aufzufrischen; nach
dessen Frischegrenze verschwindet auch die Veröffentlichung. Der begrenzte
quadratische Aufbau gibt alle 64 Vergleiche kooperativ ab. Es gibt keine Historie;
laufende Requests können ältere Snapshots innerhalb der Admission-Grenze halten.
Parameter bleiben explizit validiertes JSON; Copy-Optimierung und Indizes brauchen
einen belegten Profilnutzen.

Die Demo-Policy (`HOORI_GATEWAY_PERMISSIONS`) gewährt feste Permissions an jeden
Aufrufer. Sie ist keine Authentifizierung. Eine deklarierte Abhängigkeit ist keine
Berechtigung. Eingehende Header werden nie weitergereicht; Hooris validierte
Request-ID läuft über alle Hops, der interne Budgetwert wird vom SDK neu erzeugt.

## 4. Ressourcen und Ausfallverhalten

Ein `Microservice` besitzt einen Hoori-Datenpool für Actions und einen Control-Pool
für Registrierung/Katalog/Deregistrierung. Control hat höchstens eine Verbindung,
null Wartende und einen eigenen kurzen Timeout. Der Datenpool begrenzt auch Pending-
Acquires ausdrücklich; `/metrics` zeigt beide Pools mit festen Labels. Ohne Actions,
Abhängigkeiten oder öffentliche Gateway-Sicht startet kein Registrar; der inaktive
Control-Client erzeugt keine Sockets/Reaper. Keine Pools pro Request oder Action.
Limits werden beim Start geprüft. Katalog (256 Instanzen × 128 Actions), Abhängigkeiten (32)
und Gateway-Routen wachsen nie durch Request-Werte. Der Katalog muss in
`HOORI_BODY_BYTES` passen; größere Installationen müssen das Limit anheben.

Alle Fachrequests – normale Route, Invoke und Gateway – laufen in einer service-
eigenen `RequestScopes`-Grenze innerhalb der Router-Fehlerbehandlung:

1. Vertrauenswürdige Routenzuordnung und relative Invoke-Budgetprüfung.
2. Request-Root, begrenzte eingehende Admission, DTO/Handler/Antwortaufbau.
3. Tatsächlicher Kind-/Ressourcenabschluss und Wiederherstellung von Kontext/Timer.
4. Öffentliche Fehlerabbildung, danach HTTP-Antworttransport.

Der SDK-Raw-Body ist davor bereits begrenzt eingelesen; das ist keine Streaming-
Admission. Genau ein eingehendes Permit bleibt bis zum verwalteten Abschluss
belegt. Root-Slots zählen auch Wartende: Incoming-Calls plus Incoming-Pending,
höchstens das Serververbindungslimit (maximal 512). Das ist keine Reservierung
beliebig vieler VM-Kinder; Task-/Timer-Kapazitätsablehnung bleibt möglich und
muss bereits gestartete Arbeit drainieren. Fachlich erwartete 4xx-Responses sind
keine Exceptions und lösen keine erfundene Rollback-Policy aus.

Health/live, Health/ready, Metriken und feste Registry-Routen sind anhand der
registrierten Methode/Route von Root und Fach-Admission ausgenommen. Ein Header
oder roher Pfad erzeugt keinen Bypass. Gemeinsame Verbindungs- und Carrier-Grenzen
können diese Endpunkte weiterhin beeinträchtigen.

Ausgehend hält jeder typisierte, generische oder Gateway-Call genau ein Permit
vor JSON-Arbeit bis nach Ergebnis-Decoding. Cancellation weckt Admission-Waiter;
`HttpTasks.exchange` bricht gezielt Pool-Wait bzw. Transport ab. Der gemeinsame
Client bleibt offen. Callback-Registrierungen, Waiter und Permits werden auf allen
Ausgängen freigegeben. Standardmäßig wartet nur die Framework-Queue, der SDK-Pool
hat null Pending-Slots. Eigene unbegrenzte Fork-Schleifen sind keine unterstützte
Kapazitätsstrategie; `Tasks.map/forEach(...).maxConcurrency(n)` begrenzt Batch-Arbeit.

`Context` hält nur den Broker. Die unveränderliche `Invocation` enthält Request-ID,
Ursprung REQUEST/SERVICE und eine ausschließlich lokale Deadline; keine Request-,
Body-, Header-, Scope- oder Ressourcenreferenz. Zwei explizite SDK-TaskContext-
Bindings: vererbte Metadaten und owner-lokaler Raw-Request. Kinder sehen Letzteren
nicht; `ownerRequest()` scheitert dort. Es gibt keinen eigenen ThreadLocal-Kontext.
Die SDK-Grenzen für Bindings gelten auch für Anwendungsbindungen.

`ctx.call` läuft direkt im aktuellen logischen Task; `ctx.task` erzeugt nur einen
wiederverwendbaren `TaskSpec`, ohne Admission, Encoding oder Netzwerk. Eingaben
werden per Referenz erfasst, nicht kopiert. Erst beim Start wird der dann aktive
Kontext verwendet; ein Parallel-Plan selbst bleibt nach SDK-Vertrag single-use.
Calls benötigen eine aktive lokale Micro-Grenze. `app.runTask(spec)` erstellt
explizite service-eigene Startup-/Wartungsarbeit und wartet auf deren Abschluss;
kein Fire-and-forget, keine Requestdaten. Innerhalb einer Request-Operation sind
normale Task-Kompositionen oder kürzere `TaskScope.named(...).within(...)`-Grenzen
zu verwenden.

Das Arbeitsbudget ist das Minimum der bereits laufenden HTTP-Frist und
`HOORI_WORK_TIMEOUT_MS`. Jeder Call bildet vor seiner ersten Phase einmal das
Minimum aus Invocation-Deadline, `Budget.current()` und `HOORI_CLIENT_TIMEOUT_MS`.
Admission, Codecs, Pool und I/O verbrauchen diesen selben Wert; serielle Calls und
Nested-Scopes können die Parent-Frist nicht verlängern.

Nur `/_hoori/invoke` akzeptiert `X-Hoori-Budget-Ms`: genau ein kanonischer
Dezimalwert von 0 bis 600000. Doppelte, malformed, negative, führend genullte oder
größere Werte ergeben 400; Budget 0 ergibt 504 vor DTO-Decoding. Fehlt das Feld,
gilt die lokale Policy. Das Gateway ignoriert externe Budget- und interne
Action-/Versionsheader und startet seine eigene Policy. Jeder Empfänger begrenzt
erneut durch seine SDK-Frist und lokale Policy.

`withRemainingMillisHeader()` schreibt ganze abgerundete Restmillisekunden erst
nach Pool-Warten, DNS, Connect und TLS, unmittelbar vor dem ersten Request-Oktett.
Sub-ms-Rest/Ablauf startet keinen Request. Die Wire-Werte sind relative Dauern,
keine vergleichbaren monotonen Zeitstempel verschiedener Prozesse. Sende-,
Transit- und Empfänger-Parsing-Zeit nach dem Messpunkt lassen sich daraus nicht
exakt abziehen; die ursprüngliche Caller-Frist bleibt lokal wirksam. Das ist kein
globales Echtzeitversprechen und kein Remote-Cancellation-RPC. CPU-Code muss
weiterhin kooperieren. Interne Deadline-Klassifikation entspricht 504,
Cancellation/Überlast/Stop 503;
entfernte 503/504 bleiben erhalten, andere Upstream-Ausfälle liefern 502.
Ein bereits abgelaufenes Transportbudget kann das Senden der Fehlerantwort
verhindern. Früher Peer-Disconnect ist während des Handlers kein verlässliches
sofortiges Cancel-Signal: Der Server liest dann nicht parallel aus dem Socket.
Abbruch schließt nur den betroffenen Exchange, nie den gemeinsamen Client.

**Keine automatischen Retries, auch nicht für POST.** Nach einem Verbindungsfehler
kann die Gegenseite bereits geschrieben haben. Ein erneuter Schreibaufruf wäre ohne
Idempotenzvertrag potentiell ein doppelter Geschäftsprozess. Redirects werden
ebenfalls nicht automatisch verfolgt. Ausgehende Fehler werden sicher nach außen
übersetzt; fachliche 404 können Controller explizit abbilden.

Der Bootstrap ist kein Circuit-Breaker-Framework. Retry-Budgets,
Idempotency Keys benötigen einen konkreten fachlichen Vertrag. Insbesondere begrenzt ein Pool nicht
jede denkbare, vom Anwendungscode selbst erzeugte Menge wartender Hintergrundtasks.

Control-Timeouts (`SocketTimeoutException`) und Transportfehler führen zum bestehenden
begrenzten Backoff/Jitter, dann Lease bzw. nach 404 voller Neuregistrierung. Nur SDK-
Cancellation oder Client-Close beendet den Registrar. Timeouts löschen keinen Interrupt;
ein gleichzeitig gesetzter Interrupt bleibt ein Stop-Signal. Übergänge werden ohne
Peer-Body/Stacktrace geloggt. Zwei Pools schaffen keine CPU-Präemption.

## 5. Lifecycle und Nebenläufigkeit

1. Service-Definition/Konfiguration prüfen, gemeinsame Clients erzeugen, Routen
   und optionale service-eigene Ressourcen mit `app.own(resource)` registrieren.
2. Router einfrieren, Listener starten, bei Ready registrieren/Leases erneuern.
3. SIGTERM oder `stop()` stoppt Aufnahme, neue Roots/Waiter und Heartbeats sofort;
   eine einzige Grace-Deadline beginnt hier. Der Registrar deregistriert unabhängig.
4. Aktive Requests dürfen innerhalb der Grace weitere unmittelbare Calls machen,
   falls ein Permit frei ist. Diese Ausnahme verlangt echte lokale Request-
   Ownership; Service-Roots und einschleusbare Header erhalten sie nicht.
5. Nach Grace aktive Roots canceln und ihren tatsächlichen Kind-/Finally-/Ressourcen-
   sowie Kontext-/Timer-Abschluss abwarten. Nicht kooperierende Arbeit bleibt aktiv.
6. HTTP-Shutdown bekommt die Restzeit derselben Grace: Response-I/O liegt nach der
   Root-Completion und darf nicht mit deren Abschluss gleichgesetzt werden.
7. Registrar begrenzt abschließen, gemeinsame Daten-/Control-Clients und dann
   registrierte Service-Ressourcen in umgekehrter Reihenfolge schließen.

`HttpServer.run()`-Rückkehr oder Null-HTTP-Zähler beweisen keinen Scope-Drain.
`close()` eines externen Owners fordert Stop an und wartet auf den tatsächlichen
Abschluss; es ist idempotent. Ein aktiver Handler darf `stop()`, nicht das eigene
`close()` aufrufen. Startfehler führen ebenfalls in denselben Cleanup-Pfad.
Eine langsame Registry verlängert die fachliche Grace nicht; ihr Abschluss kann
nach dem Daten-Drain bis zu drei Control-Timeouts zusätzlich benötigen.

**Fehler und Diagnose:** Bekannte Scope-/Subtask-/Bulk-/Operation-Wrapper werden
begrenzt nach ihrer semantischen Hauptursache klassifiziert. Fachfehler behalten
ihren bewusst öffentlichen Status/Text, Kapazität/Shutdown liefern 503, Deadline
und lokale I/O-Timeouts 504, Upstream-Fehler 502 (503/504 bleiben erhalten),
unerwartete Fehler 500. Ursprüngliche Ursachen/suppressed-Fehler bleiben unverändert;
ein späterer Sibling-Abbruch überschreibt keinen primären Fachfehler. Operation-
Transaktionsstatus bleibt intern erhalten, ohne DB-Pflichtabhängigkeit.

Feste Metriken erfassen aktive/überfällige Roots, Completion, Zeit nach Body-Ende
bis zum vollständigen Abschluss und neun feste Fehlergründe. Keine Requestwerte
als Labels. `app.taskDiagnostics()` erstellt nur auf Anforderung Snapshots von
höchstens acht bekannten Roots mit je 32 Einträgen und Tiefe vier. Die Snapshots
enthalten kopierte skalare Zustände; aktive Scope-Referenzen liegen ausschließlich
in begrenzten Slots und werden bei Completion entfernt. Die Abfrage kostet eine
begrenzte Scope-/VM-Abtastung; kein kostenloses Dauer-Sampling, kein HTTP-Debug-Endpunkt.

`ready(false)` verändert den Bereitschaftszustand, ist aber kein eigenständiges
Autorisierungs- oder Request-Abweisungssystem. Docker `depends_on: service_healthy`
koordiniert den Start, nicht den gesamten späteren Betrieb. Es gibt absichtlich
keine rekursiven Abhängigkeitsprobes, die bei einem einzelnen Ausfall die ganze
Service-Kette „unready“ machen.

Hooris Server führt resumierbare Guest-Tasks auf einem kooperativen Carrier aus.
Dieses Framework behauptet keine CPU-Parallelität oder Präemption. CPU-intensive
Arbeit muss kooperieren oder später über ausdrücklich geplante Worker ausgelagert
werden. Auch eine Shutdown-Grace ist keine Garantie gegen unkooperativen CPU-Code;
der Container-Stop-Timeout bleibt die äußere Grenze.

## 6. Sicherheitsgrenze der Demo

Nur das Gateway veröffentlicht einen Loopback-Host-Port. Registry, Recipes, Pantry und
Shopping hängen am internen Backend-Netz. Container laufen ohne Root, ohne Linux-
Capabilities, mit Read-only-Root-Dateisystem und ohne Docker-Socket. Die Registry
erhält keine Connect-/DNS-Capability; alle anderen brauchen sie für Heartbeats.

**Registry und `/_hoori/invoke` sind unauthentifiziert.** Wer das Backend-Netz
erreicht, kann Actions aufrufen, Instanzen registrieren oder fremde Registrierungen
überschreiben und damit auch Gateway-Routen umlenken. Die Vertrauensgrenze ist das
private Netz. Der geplante Ablauf (Firebase, interne Tokens, mTLS) steht in
[security.md](security.md). Vor echter Dahemm-Nutzung fehlen ausdrücklich: Service-Identität
(z. B. mTLS) für Registry und Invoke, TLS am externen Rand, verifizierte Benutzer-
identität, Mandantenautorisierung beim Datenbesitzer und Secret-Management.
Demo-Actions wie `/demo/context` und `/demo/slow` dürfen nicht produktiv
veröffentlicht werden.

## 7. Messbasis und Performance-Ziel

Die A–D-Ausgangsmessung gegen den gepinnten Release-Stand steht mit Rohdaten und
Messgrenzen unter [benchmarks.md](benchmarks.md). Warmup, Engines, feste CPU-/RAM-
Budgets, erfolgreiche Arbeit und Abweisungen bleiben getrennt. Die Ausgangsbasis
ist keine Performancefreigabe: insbesondere das Gateway verfehlt die gesetzte
p99-Grenze. Jede Optimierung benötigt denselben Vergleich und einen Nutzen oberhalb
der beobachteten Streuung. SDK-Upgrades werden als eigene Variable gemessen.

Quellgrundlagen: [Baseline und Quellen](source-baseline.md).
