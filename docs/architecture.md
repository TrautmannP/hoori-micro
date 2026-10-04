# Architektur

Hoori Micro ist ein eigenständiger Consumer der originalen Hoori-SDKs. Die
öffentliche Anwendungsschicht verwendet MVC-Controller, Fachservices, Repositories
und typisierte HTTP-Clients. Ein endlicher, beim Build erzeugter Konstruktorgraph
ersetzt technische Registrierung im Anwendungscode.

## Build und Bootstrap

`@MicroApplication(name, version)` definiert genau eine App pro Maven-Modul.
Komponenten unter ihrem Basispaket tragen `@RestController`,
`@RestControllerAdvice`, `@Service`, `@Repository`, `@Configuration` oder
`@ServiceClient`. Öffentliche konkrete Komponenten besitzen genau einen
öffentlichen Konstruktor. `@Bean`-Methoden einer Configuration liefern
Infrastruktur. Factory-Parameter werden wie Konstruktorparameter aufgelöst.
`Environment`, `Microservice` und die Startargumente stehen als Infrastruktur bereit.

Der Graph prüft fehlende/mehrdeutige Abhängigkeiten, Zyklen, Sichtbarkeit und
Doppeldeklarationen. Maximal 128 Komponenten/Beans und 16 eigene schließbare Ressourcen; keine Scans zur Laufzeit,
Scopesprache, reflektiven Feldinjektionen oder allgemeinen Proxies. Ein Bibliotheks-
modul exportiert Komponenten mit `@MicroModule`; die App benennt es in
`@MicroApplication(imports = {...})`. Metadaten bleiben im JAR, Quellen sind zur
Laufzeit unnötig. Imports sind ausdrücklich; fremde JARs werden nicht durchsucht.

`Micro.run(App.class, args)` lädt genau den erzeugten Einstieg und führt direkte
Konstruktoraufrufe aus. Controller/Advice und DTO-/Validation-Adapter stammen aus
Hooris MVC-Processor, Clients nutzen dessen gemeinsames HTTP-/DTO-Modell. Avaje
ist der Provider des Starters; der HTTP-Core hängt nur von der neutralen SPI ab.
Die ursprünglichen Processor laufen ausschließlich beim Build.

Singleton-Ressourcen mit `AutoCloseable` gehören standardmäßig der App. Alias-
Instanzen werden identisch nur einmal geschlossen, in umgekehrter Erzeugungsfolge.
`@Bean(owned = false)` überträgt keinen Besitz. Ein Bootstrapfehler räumt bereits
erzeugte Ressourcen auf, bevor Listener, Readiness oder Registrierung beginnen.

## HTTP und DTOs

Mappings unter `hoori.rest.mvc` definieren Methode/Template. `@PathVariable`,
`@RequestParam`, `@RequestHeader` und `@RequestBody` binden getrennte Eingaben.
Der SDK-Router besitzt allein Template-Grammatik, UTF-8-Decoding, Konfliktprüfung
und 404/405-Verhalten. Controller delegieren an Fachservices; diese kennen keine
Transport-Registrierung. DTOs sind öffentliche Records. Unterstützt sind die
begrenzten SDK-Typen, verschachtelte Records und Listen; keine beliebigen JDK-Typen.

`@Valid` und Jakarta-Constraints erzeugen begrenzte Validation vor der Fachmethode.
Fehlende Pflichtfelder, falsche Typen, doppelte JSON-Felder, nachfolgende Tokens
und Größenüberschreitungen werden abgewiesen. Null und fachliche Constraints
bleiben getrennte Regeln. Unterstützt sind `@NotNull`, `@NotBlank`, `@Size`,
`@Positive`, `@Min`, `@Max`, verschachteltes `@Valid` und Listenelemente.
Nicht unterstützte Profile scheitern beim Build. `new Dto(...)` validiert nicht.

DTO-/Listenantworten liefern JSON, `void` liefert 204. `@ResponseStatus` bzw.
`HttpResult<T>` setzen Status und erlaubte Metadaten. `@RestControllerAdvice`
bindet erwartete Fachfehler an einen begrenzten öffentlichen `Problem`-Code.
Unbekannte Fehler bleiben 500; öffentliche Antworten enthalten keine Exceptions,
Provider-Meldungen oder Eingabewerte.

## Request-Grenze, Kontext und Ressourcen

Jede Fachanfrage läuft durch dieselbe Incoming-Admission und
`RequestScopes`-/`Executions`-Grenze. Der HTTP-SDK hat den begrenzten Raw-Body
bereits gelesen; DTO-Binding, Validation und Facharbeit beginnen erst nach Admission.
Health, Metrics und feste Control-Routen liegen außerhalb dieser Fachgrenze.
Keine zweite Request-Root im MVC-Adapter und kein ThreadLocal-Kontext.

`TaskContext` hält Korrelation, Ursprung und Restbudget. Der mutable Raw-Request
bleibt beim HTTP-Owner. Ein wiederverwendeter Client liest den aktuellen Kontext
bei jedem Aufruf; Aufrufe außerhalb einer Micro-Grenze scheitern vor Encoding und
Netzwerk. Explizite Startup-/Wartungsarbeit verwendet `app.runTask(...)`.

Outgoing-Admission umfasst Encoding, tatsächlichen Exchange und Decoding. Ein
Datenpool je App, eine gesonderte Control-Verbindung ohne zusätzliche Warteschlange.
Keine Clients pro Request, automatischen Write-Replays, Redirects oder
Credential-Weitergabe. `X-Hoori-Budget-Ms` entsteht im originalen Before-Write-Hook,
also nach Pool-/DNS-/Connect-/TLS-Wartezeit. Es ist ein relatives Restbudget,
keine globale Echtzeitfrist und kein Remote-Cancellation-Protokoll.

Fehlerpriorität: Admission, Cancellation, Deadline und verpflichtendes Cleanup
haben Vorrang vor Fach-Advice. Die Antwort wird erst nach lokalem Child-/Ressourcen-
Drain freigegeben. Hoori arbeitet kooperativ; nicht kooperierende CPU-Arbeit kann
nicht beliebig präemptiert werden.

## Discovery und Gateway

Der Katalog enthält Service, Major-Version, Instanz-ID, geprüften Origin und
höchstens 128 HTTP-Endpunkte pro Instanz. Jeder Endpunkt besteht aus Methode,
Template, consumes/produces und optionaler Permission. Sein stabiler Schlüssel
ist der HTTP-Vertrag, unabhängig von Java-Methodennamen und Requestdaten.

Registry-Protokoll **3** unter `/v2/instances/{id}`, `/v2/instances/{id}/lease`
und `/v2/catalog`; Katalog-/Instanzaufrufe benötigen `X-Hoori-Catalog-Protocol: 3`.
Alte Protokolle werden zurückgewiesen. TTL, Epochen, Revisionen und die
`complete`-Markierung begrenzen Wiederanmeldung und Neustart. Bei unveränderter
Epoche/Revision/Sicht bestätigen Lease und Katalogabruf mit 204. Filter sind
`none`, `public` oder `services=name:version,...` mit höchstens 32 Dependencies.
Consumer erhalten nur ihre Services und keine Gateway-Permissions. Der Registry-
Gesamtkatalog und seine Antworten bleiben begrenzt, auch vor gefilterter Ausgabe.

Auf dem Request-Pfad wird ausschließlich ein lokales unveränderliches Snapshot
verwendet. Clientauswahl erfolgt nach Service, Version und exaktem Endpunktvertrag.
Interne `X-Hoori-Endpoint`-/`X-Hoori-Version`-Header werden am Provider geprüft;
Mismatch führt zu 421 ohne Retry. Externe Gateway-Aufrufer dürfen diese Header
nicht bestimmen. Leere/abgelaufene Sichten bieten keine falsche Ersatzinstanz an.

`@GatewayRoute(permission="service:scope")` veröffentlicht eine gemappte Methode.
Fehlende Annotation bedeutet keine Gateway-Publikation; leere/fremde Permissions
und Annotationen ohne Controller-Mapping sind Buildfehler. Die Policy wird pro
Request geprüft. Die Demo-Policy gewährt konfigurierte Permissions jedem Aufrufer.

Gateway und Provider verwenden denselben SDK-Router. Methode, Raw-Pfad/Query und
begrenzter Body bleiben getrennt und unverändert. Das Gateway reicht nur `Accept`
und `Content-Type` als Requestheader weiter; weitere gebundene Header sind für
interne Controller/Clients möglich, aber nicht für publizierte Methoden.
Erfolgsstatus und sichere Metadaten bleiben erhalten; absolute/unsichere Location,
Credentials und Hop-by-hop-Header gelangen nicht nach außen. Bekannte begrenzte
Validation-/Problem-Fehler werden geprüft und neu serialisiert. Ein Fehler eines
inneren Remote-Aufrufs wird nicht als Validation des äußeren Inputs dargestellt.

Der Registrar bereitet Routing und Katalog gemeinsam vor: maximal 256 verschiedene
öffentliche Routen und 64 KiB Metadaten. Konflikte halten beide Definitionen zurück;
überlaufende Updates verlängern die alte Sicht nicht. Unveränderte Bestätigungen
verwenden das eingefrorene Routing weiter. Zusätzliche Provider-Endpunkte können
dynamisch erscheinen, ohne Gateway oder unbeteiligte Clients neu zu bauen.

## Parallele Fachlogik und optionale Daten

Normale Clientmethoden sind synchron. `Tasks.task(() -> client.read(...))` erzeugt
einen lazy `TaskSpec`; `parallel`/`map`/`settled` wählen ausdrücklich Parallelität,
Fehlerpolicy und Reihenfolge. Der originale `@GenerateTasks`-Processor kann die
Task-Fassade eines Client-Interfaces erzeugen; der App-Graph injiziert sie.

`@TaskScoped`-/`@Transactional`-Dekoration gilt ausschließlich für unterstützte
öffentliche Interfaces und deren injizierte Delegates. Es muss genau eine
Implementierung geben; Transaktionen benötigen genau einen stabilen Manager.
Konkrete/self-invoked Methoden erhalten keine automatische Interception. Die
originalen SDK-Processor prüfen den Annotationsvertrag.

Im optionalen Datenbeispiel bereitet der Fachservice Remote-Reads **vor** der
lokalen Transaktion vor. Ein dekorierter Writer schreibt Daten und Outbox gemeinsam;
das Repository enthält nur SQL. Handles sind ownergebunden, Kinder erhalten keine
geerbten Handles. Physischer Commit liegt vor Response-Encoding. `UNKNOWN` und
`COMMITTED` bleiben erhalten und werden weder als Rollback noch als Retry behandelt.
DB-/Treiber-/Jdbi-JARs gehören allein zum optionalen Runtime-Klassenpfad.

## Shutdown und Grenzen

Stop setzt Unready und schließt Admission; Deregistrierung läuft unabhängig.
Admittierte Requests dürfen vorhandene Slots weiter nutzen. Wartende/neue Arbeit
wird abgewiesen, nach Grace wird Cancellation ausgelöst. Erst nach tatsächlichem
Request-/Child-/HTTP-Drain schließen App-Ressourcen und beide Clients.
`HttpServer.run()` allein beweist keinen Drain; erzwungener Prozesskill ebenso wenig.

Registry und interne Endpunkte sind in dieser Demo unauthentifiziert. Keine
allgemeine Proxy-Engine, ORM, Runtime-DI/AOP, Eventbroker, Spring-Kompatibilität oder
Dahemm-Migration. Sicherheits- und Betriebsfolgen stehen in [security.md](security.md)
und [roadmap.md](roadmap.md).
