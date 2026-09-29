# Architektur und Entscheidungen

## 1. Ein dünnes Framework oberhalb der Runtime

```text
Dahemm-Actions / explizite Anwendungsdienste
                    ↓
hoori-micro: Service-Definition, Broker, Registry, Gateway, Lifecycle
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

## 2. Actions, Registry und Broker

Aufrufer adressieren eine **fachliche Action** (`recipes.get`) mit der per
`dependsOn("recipes", 1)` deklarierten Hauptversion, nie eine Route oder Adresse.

- **Service-Definition:** Aus `Service.named(..).action(..)` entstehen der lokale
  Dispatcher hinter `POST /_hoori/invoke` und der veröffentlichte Katalogeintrag.
  Eine neue Action braucht keine neue Route; der Router bleibt nach `freeze()` fix.
- **Registry** (`hoori.micro.Registry`): In-Memory-Map Instanz-ID → {Service,
  Hauptversion, Advertise-URL, Actions}, begrenzt auf 256 Instanzen, jeder Eintrag mit
  TTL. `PUT /v1/instances/{id}` registriert bzw. verlängert und antwortet mit dem
  Katalog; `DELETE` deregistriert; `GET /v1/catalog` liest.
- **Heartbeat:** Nach Live und solange Ready sendet jede Instanz mit Actions ihre
  vollständige Registrierung (Default alle 2 s, TTL 6 s). Deshalb registriert sie sich
  nach einem Registry-Neustart automatisch neu. Reine Aufrufer holen nur den Katalog.
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

## 3. Wire-Protokoll, Gateway und Verträge

Interner Aufruf: `POST /_hoori/invoke`, Header `X-Hoori-Action: recipes.get` und
`X-Hoori-Version: 1`, Body = JSON-Parameter der Action. Name und Version stehen in
Headern statt in einem JSON-Umschlag, damit der Eingabe-Codec den Body direkt und
ohne zweites Parsen liest. Antwort: 200 mit JSON-Ergebnis oder ein Fehlerstatus.
Fachliche Fehler wirft eine Action als `RequestException(status, meldung)`.

Typisierte Verträge (`Action<I, O>`) enthalten nur Name und Codecs. Generische Aufrufe
verwenden `Map` und `JsonTree` (Map, List, String, Long, Double, Boolean, null).
Ergebnisse müssen 2xx mit `application/json` (optional `charset=utf-8`) sein; sonst
`ServiceCallException` mit Status, aber ohne Upstream-Body.

**Gateway** (`hoori.micro.Gateway`): Eine Middleware, die für sonst unbekannte Pfade
die Routen aus dem Katalog baut. Veröffentlicht wird nur, was der Anbieter mit
`http(method, template)` **und** `requirePermission("<service>:<scope>")` markiert
und was die Gateway-Policy gewährt. Die Permission ist auf den eigenen Service-Namen
begrenzt. Konflikte (gleiche Methode, überlappende Templates gleicher Spezifität,
verschiedene Actions) werden im Service beim Start abgewiesen und im Gateway für alle
Beteiligten zurückgehalten. Pfadparameter werden als JSON-Strings übergeben und mit
einem optionalen JSON-Objekt-Body zusammengeführt; Query-Parameter nicht.
Client-Fehler des Anbieters behalten ihren Status, nie ihren Body.

Die Demo-Policy (`HOORI_GATEWAY_PERMISSIONS`) gewährt feste Permissions an jeden
Aufrufer. Sie ist keine Authentifizierung. Eine deklarierte Abhängigkeit ist keine
Berechtigung. Eingehende Header werden nie weitergereicht; nur Hooris validierte
Request-ID läuft über alle Hops.

## 4. Ressourcen und Ausfallverhalten

Ein `Microservice` besitzt genau einen Hoori-HTTP-Client-Pool für Actions und
Heartbeats. Dadurch werden nicht pro Request Clients oder Pools angelegt. Limits
werden beim Start geprüft. Katalog (256 Instanzen × 128 Actions), Abhängigkeiten (32)
und Gateway-Routen wachsen nie durch Request-Werte. Der Katalog muss in
`HOORI_BODY_BYTES` passen; größere Installationen müssen das Limit anheben.

Timeouts sind **pro HTTP-Exchange**. Der bestehende Hoori-Client umfasst Pool-Warten,
DNS, Connect, TLS, Schreiben und Response-Lesen mit einer monotonen Deadline. Das
ist noch kein transitive Request-Budget über mehrere Service-Hops. Serielles Fan-out
kann mehrere einzelne Budgets verbrauchen; Aufrufer dürfen dies nicht als globale
Deadline interpretieren. Ein entsprechendes Budget gehört in eine separat
qualifizierte Erweiterung der vorhandenen APIs, nicht in einen unzuverlässigen
Header mit neu gestarteter Uhr pro Hop.

**Keine automatischen Retries, auch nicht für POST.** Nach einem Verbindungsfehler
kann die Gegenseite bereits geschrieben haben. Ein erneuter Schreibaufruf wäre ohne
Idempotenzvertrag potentiell ein doppelter Geschäftsprozess. Redirects werden
ebenfalls nicht automatisch verfolgt. Ausgehende Fehler werden sicher nach außen
übersetzt; fachliche 404 können Controller explizit abbilden.

Der Bootstrap ist kein Circuit-Breaker-Framework. Abweisungsstrategien,
Retry-Budgets, Idempotency Keys und begrenztes Fan-out folgen nur bei einem
konkreten Use-Case und mit passenden Tests. Insbesondere begrenzt ein Pool nicht
jede denkbare, vom Anwendungscode selbst erzeugte Menge wartender Hintergrundtasks.

## 5. Lifecycle und Nebenläufigkeit

1. Service-Definition und Konfiguration validieren, Client erzeugen, Routen registrieren.
2. Router einfrieren, Listener binden, Service starten.
3. Sobald live und ready: registrieren; Heartbeats nur solange ready.
4. Bei SIGTERM Aufnahme neuer Requests stoppen und parallel deregistrieren.
5. Bereits gestartete Requests innerhalb der Grace-Period abarbeiten.
6. Erst danach den ausgehenden Client schließen; eine noch hängende Deregistrierung
   bricht damit ab, die TTL übernimmt.

Wichtig: `HttpServer.run()` kehrt zurück, sobald die Aufnahme endet. Es wartet
nicht selbst auf alle Handler. Deshalb lösen Signal-Watcher und Heartbeat-Thread nur
`stop()` bzw. die Deregistrierung aus; der `run()`-Owner führt `shutdown()` aus und
schließt erst danach den Pool.
`close()` ist ein ausdrücklicher Sofortabbruch; während des Betriebs für geordnetes
Beenden `stop()` verwenden.

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

Nur das Gateway veröffentlicht einen Loopback-Host-Port. Registry, Recipes und
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

## 7. Performance-Ziel, noch kein Benchmark-Ergebnis

Wenig zusätzliche Schichten, ein begrenzter Pool und eine Registry außerhalb des
Request-Pfads sind Designentscheidungen. Daraus wird **kein gemessener Performancegewinn**
abgeleitet. Vor einer Behauptung müssen Startzeit, Warmup, RSS/Heap, Requests/s,
p50/p95/p99, Fehlerquote und Ressourcen nach längeren Phasen gemessen werden.

Vergleich: nackter `hoori-rest`-Service gegen denselben Handler mit `hoori-micro`,
auf gleicher VM-Revision, mit identischen Limits, Release-Build und separatem
Lastgenerator. Danach einen Action-Hop und das Gateway dazunehmen. Kalte und warme Messungen sowie
Einzelservice- und Gesamtsystem-RSS getrennt berichten. Eine Aufteilung in Container
kann trotz leichter Einzelprozesse den Gesamtverbrauch erhöhen.

Quellgrundlagen: [Baseline und Quellen](source-baseline.md).
