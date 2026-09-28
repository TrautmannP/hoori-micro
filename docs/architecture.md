# Architektur und Entscheidungen

## 1. Ein dünnes Framework oberhalb der Runtime

```text
Dahemm-Controller / explizite Anwendungsdienste
                    ↓
hoori-micro: Service-Lifecycle, Namenskonvention, Client-Helfer
                    ↓
hoori-rest-api: Router, Middleware, explizite JSON-Codecs
                    ↓
hoori-http-api: HTTP, Pooling, Bounds, Kontext, Metriken, Drain
                    ↓
Hoori Guest Base / resumierbare Tasks / Host-Netzwerk
```

Abhängigkeiten zeigen nur nach unten. Dieses Repository verändert weder Hoori-
Quellcode noch seine Roadmap. Server und Client sind die echten Hoori-Implementierungen;
Test-Seams ersetzen keine produktive Transportkomponente.

Explizite Constructor Injection genügt zunächst. Ein Controller ist ein gewöhnliches
Objekt, dessen Methoden am vorhandenen Router registriert werden. Keine
Annotationssuche, keine Laufzeit-Proxies und keine automatische Dependency Injection.
Das hält Startpfad und Abhängigkeiten sichtbar und vermeidet zusätzliche Runtime-
Kompatibilitätsanforderungen.

## 2. Discovery: Docker-DNS statt selbst gebauter Registry

V1 legt diese Konvention fest:

- Ein logischer Name entspricht einem Compose-Service oder expliziten Network Alias.
- Alle Dienste verwenden intern Port 8080, sofern kein Origin-Override gesetzt wird.
- Ein Aufrufer deklariert seine Abhängigkeiten einmal beim Start.

Beispiel: `Microservice.create("shopping", "recipes")` erlaubt im ServiceClient den
Namen `recipes`; daraus wird `http://recipes:8080`. Erst Hooris URI-basierter
HTTP-Client löst den Host auf. Das Framework hält keine IP-Adressen fest und
betreibt keine Heartbeats, Container-Auflistung oder Selbstregistrierung.

Das beantwortet „wo ist recipes?“, nicht „welche Services existieren überhaupt?“,
„welche API bieten sie?“ oder „wem darf ich vertrauen?“. Eine Service-Katalog-UI,
automatische API-Vertragsfindung und instanzbasierte Lastverteilung sind nicht
Teil der Discovery.

**Grenzen:** Compose-Bridge-Netze sind kein automatisches Multi-Host-Netz. Zwei
Compose-Projekte sehen sich nur über ein bewusst gemeinsam angeschlossenes Netz.
Für mehrere Hosts wird später ein Orchestrator mit stabilem Service-Endpunkt oder
ein qualifizierter Load Balancer benötigt. `ServiceDirectory` kann dessen DNS-Namen
über das bestehende Origin-Override verwenden; der API-Vertrag muss nicht wechseln.

Docker dokumentiert stabile Service-Namen bei austauschbaren Container-IPs. Ein
existierender TCP-Kanal kann trotzdem abbrechen. Hoori verwendet nach dem Verwerfen
einer defekten Verbindung für neue Verbindungen wieder seine DNS-Auflösung. V1
verspricht weder sofortige Umschaltung noch einen erfolgreichen ersten Aufruf nach
einem Neustart. Der Smoke-Test prüft Erholung nach Container-Neuerstellung, aber
keinen erzwungenen IP-Wechsel oder garantierte TTL-Auswertung.

## 3. Kommunikation und Verträge

Synchrones Request/Response verwendet HTTP/1.1 und vollständige, begrenzte JSON-
Bodies. Typisierte Helfer verwenden `JsonCodec<T>`; es gibt keine Reflection-
Serialisierung und keinen beliebigen Objektgraphen über das Netzwerk.

Öffentliche/zwischen Services verwendete Ressourcenpfade beginnen in der Demo mit
`/v1`. Das Framework führt keine versteckte zweite RPC-Sprache ein. Die Demo teilt
nur einen kleinen DTO/Codec; daraus soll kein gemeinsames Modul mit sämtlichen
Dahemm-Domain- oder Datenbankklassen entstehen.

`ServiceClient.getJson` und `postJson` verlangen 2xx und einen nicht-null JSON-Body.
Akzeptiert wird `application/json` ohne Parameter oder nur mit `charset=utf-8`.
`application/problem+json`, andere `+json`-Typen, 204 ohne Body und fachliche
Nicht-2xx-Antworten benötigen explizite Behandlung über `exchange` beziehungsweise
`ServiceCallException.upstreamStatus()`. Der Raw-Aufruf liefert HTTP-Status und Body
unverändert zur bewussten Auswertung; er ist kein automatischer Reverse Proxy.

Eingehende Header werden nie pauschal weitergereicht. Bei `request.raw()` als
Kontext übernimmt Hoori die validierte Request-ID. Benutzer-/Service-Identitäten,
Cookies und Mandanteninformationen benötigen später einen gesonderten,
verifizierbaren Sicherheitsvertrag. Ein Request-ID ist keine Identität.

## 4. Ressourcen und Ausfallverhalten

Ein `Microservice` besitzt genau einen Hoori-HTTP-Client-Pool. Dadurch werden nicht
pro Request Clients, Idle-Reaper oder Pools angelegt. Die Gesamt- und Origin-Limits
sowie Body- und Timeout-Limits werden beim Start geprüft. Das Service-Verzeichnis
hat maximal 32 explizite Einträge und wächst nicht durch eingehende Request-Werte.

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

1. Konfiguration/Abhängigkeiten validieren, Client erzeugen, Routen registrieren.
2. Router einfrieren, Listener binden, Service starten.
3. Readiness beschreibt lokale Aufnahmefähigkeit; Liveness den lokalen Server.
4. Bei SIGTERM Readiness ausschalten und Aufnahme neuer Requests stoppen.
5. Bereits gestartete Requests innerhalb der Grace-Period abarbeiten.
6. Erst danach den eigenen ausgehenden Client schließen und Ressourcen freigeben.

Wichtig: `HttpServer.run()` kehrt zurück, sobald die Aufnahme endet. Es wartet
nicht selbst auf alle Handler. Deshalb löst der Signal-Watcher nur `stop()` aus;
der `run()`-Owner führt `shutdown()` aus und schließt erst danach den Pool.
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

Nur `shopping` veröffentlicht einen Loopback-Host-Port. `recipes` hängt am internen
Backend-Netz. Container laufen ohne Root, ohne Linux-Capabilities, mit Read-only-
Root-Dateisystem und ohne Docker-Socket. Der Launcher erteilt der Recipe-Demo keine
Connect-/DNS-Capability; Shopping benötigt diese für seine ausgehenden Aufrufe.

Diese VM-Netzwerk-Capabilities sind grob, keine Zielhost-Allowlist. Das deklarierte
Service-Verzeichnis beschränkt den Framework-Client, aber ersetzt keine
Netzwerkpolicy: Anwendungscode könnte direkt den HTTP-SDK verwenden. Ebenso sind
HTTP im internen Netz und DNS-Namen keine gegenseitige Authentifizierung.

Vor echter Dahemm-Nutzung fehlen ausdrücklich: TLS am externen Rand, verifizierte
Service-/Benutzeridentität, Mandantenautorisierung in jedem betroffenen Service,
Secret-Management und die fachliche Behandlung von Datenzugriffen. Der Hoori-Client
kann bereits verifiziertes ausgehendes HTTPS; dieses Framework fügt keinen
inbound-TLS-Listener hinzu. Debug-/Demo-Routen dürfen nicht produktiv exponiert werden.

## 7. Performance-Ziel, noch kein Benchmark-Ergebnis

Wenig zusätzliche Schichten, ein begrenzter Pool und kein zusätzlicher Registry-
Prozess sind Designentscheidungen. Daraus wird **kein gemessener Performancegewinn**
abgeleitet. Vor einer Behauptung müssen Startzeit, Warmup, RSS/Heap, Requests/s,
p50/p95/p99, Fehlerquote und Ressourcen nach längeren Phasen gemessen werden.

Vergleich: nackter `hoori-rest`-Service gegen denselben Handler mit `hoori-micro`,
auf gleicher VM-Revision, mit identischen Limits, Release-Build und separatem
Lastgenerator. Danach einen Service-Hop dazunehmen. Kalte und warme Messungen sowie
Einzelservice- und Gesamtsystem-RSS getrennt berichten. Eine Aufteilung in Container
kann trotz leichter Einzelprozesse den Gesamtverbrauch erhöhen.

Quellgrundlagen: [Baseline und Quellen](source-baseline.md).
