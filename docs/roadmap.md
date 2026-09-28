# Roadmap: Framework zuerst, Dahemm schrittweise

Die Hoori-MS0–MS5-Runtime-Roadmap bleibt abgeschlossen und unabhängig. Dieses
Repository führt eine eigene kleine Roadmap. Die Dahemm-Migration ist weder MS6
noch bereits mit diesen zwei Demos umgesetzt.

## F0 — Dieser Bootstrap: implementiert, native Abnahme offen

Vorhanden: eigenständige Maven-Bibliothek, expliziter Service-Lifecycle, benannte
HTTP-/JSON-Aufrufe, Docker-DNS-Konvention, Konfigurationsvalidierung, HTTP-Metriken,
Request-ID-Kontext, Pool-Ownership, Shutdown-Ordering, zwei Demo-Services,
Runtime-Provenienzprüfung und ausführbare Prüf-Scripte.

**Exit-Kriterium:** alle folgenden F1-Checks grün. Bis dahin als experimentellen
Bootstrap behandeln, nicht als produktionsreifes Framework.

## F1 — Echte Runtime- und Betriebsabnahme

- Passende, saubere Hoori-Distribution bauen; `scripts/build.sh` mit echten SDK-JARs
  ausführen. JUnit-Berichte und Runtime-/Toolchain-Identität aufbewahren.
- `test-hoori-core.sh` und `smoke.py` in Interpreter und Mixed ausführen. Fehler
  an der passenden Schicht beheben, nicht auf HotSpot-Netzwerk umschalten.
- Zusätzlich reale DNS-IP-Wechsel, verweigerte DNS-/Connect-Capabilities,
  verifiziertes HTTPS/ungültige Zertifikate, Überlast und abgelaufene Grace testen.
- Release-Baseline mit nacktem REST-Service vergleichen: Warmup, Latenzen, Durchsatz,
  RSS/Heap, Idle-/Last-/Recovery-Verhalten. Keine ungemessenen Optimierungen behaupten.

Die mitgelieferte CI prüft nur portable Teile. Eine geschützte Integration-Pipeline
benötigt autorisierten Zugriff auf die private, gepinnte Hoori-Quelle oder auf eine
vertrauenswürdige Runtime-Distribution. Keine Tokens in Docker-Layers, keine Secrets
an nicht vertrauenswürdige Pull-Request-Jobs durchreichen.

## F2 — Dahemm-Verträge und Sicherheitsgrundlage

**Vor der ersten echten Fachmigration** das aktuelle Dahemm-Backend separat prüfen:
Endpunkte, Datenmodell, Authentifizierung, Haushalts-/Mandantenberechtigungen,
Transaktionen und bestehende Client-Verträge inventarisieren. Diese Bestandsaufnahme
ist in diesem Bootstrap ausdrücklich nicht erfolgt.

Eine kleine sinnvolle erste Grenzziehung muss aus tatsächlicher Fachlogik entstehen,
nicht aus „eine Tabelle = ein Microservice“. Rezepte und Einkaufen in der Demo sind
Kommunikationsbeispiele, keine freigegebene Zielarchitektur für sämtliche Dahemm-Daten.

Festzulegen und zu testen:

- Versionierte APIs, Status-/Fehlerverträge und getrennte Wire-DTOs. Bestehende
  Android-API anfangs am bisherigen Backend/Rand stabil halten.
- Service-Identität, Benutzer-/Haushaltskontext und Autorisierung beim tatsächlichen
  Datenbesitzer; keine ungeprüften `X-User`-/`X-Household`-Header als Vertrauensbasis.
- PostgreSQL-/JDBC-Laufzeitabnahme auf dem benötigten Hoori-Stand: Treiber,
  Migrationen, Timeouts, Transaktionen, Pooling und Ressourcen unter Fehlern.

Die VM-/JDBC-Unterstützung darf nicht allein aus „Java-kompatibel“ oder einem grünen
HTTP-Test abgeleitet werden. Datenbank-Pools und Client-Pools sind unterschiedliche
Ressourcen. Keine eigene ORM- oder allgemeine Repository-Abstraktion ohne Bedarf.

## F3 — Erster rückschaltbarer vertikaler Schnitt

Empfehlung: zuerst einen **lesenden Rezept-Use-Case**, sofern die Bestandsaufnahme
diese Grenze bestätigt. Das bisherige Dahemm-Backend kann zunächst seine externe
API behalten und diesen einzelnen Aufruf an den neuen Service delegieren. Dazu
keinen generischen Gateway-Proxy entwickeln; ein konkreter Adapter reicht.

Akzeptanz: gleiche fachliche Ergebnisse und Berechtigungen, Contract-Tests zwischen
altem Rand und neuem Service, nachvollziehbare Request-Korrelation, definierte
Ausfallantwort und ein schneller Rückschaltpfad. Ein rein lesender Schattenvergleich
ist möglich, solange Datenzugriff und Datenschutz passend abgesichert sind.
Keine unkontrollierten Schreib-Doppelaufrufe zum „Vergleichen“.

Erst nach erfolgreicher Abnahme einen schreibenden Use-Case verschieben. Für jeden
Datensatz muss zu jedem Zeitpunkt klar sein, welcher Service Schreibhoheit besitzt.
Eine bestehende PostgreSQL-Instanz kann organisatorisch mehrere Dienste bedienen;
getrennte Datenhoheit/Zugriffsrechte und keine serviceübergreifenden Tabellenzugriffe
bleiben trotzdem notwendig. Separate physische Datenbanken sind nicht automatisch
für den allerersten Schritt erforderlich.

## F4 — Zuverlässige asynchrone Prozesse, nur bei Bedarf

Wenn beispielsweise ein Wochenplan Zutaten für Einkaufslisten auslöst, zuerst die
Konsistenzanforderung festlegen. Ein transparenter synchroner Aufruf kann reichen.
Soll ein Vorgang einen Service-Ausfall überstehen, sind persistente Nachrichten oder
Jobs nötig, nicht ein In-Memory-EventEmitter.

Dann separat entwerfen und testen: transaktionale Outbox, dauerhafter Broker/Transport,
versionierte Events, idempotente Consumer, Wiederholungsgrenzen und Umgang mit
nicht verarbeitbaren Nachrichten. Keine „exactly once“-Behauptung und keine
verteilte ACID-Transaktion als implizites Framework-Versprechen.

## F5 — Skalierung erst mit Betriebsdaten

Mehrere Instanzen, faire/gesundheitsbewusste Lastverteilung, Multi-Host-Betrieb,
End-to-End-Deadline-Budgets, Circuit Breaker und verteiltes Tracing sind mögliche
Folgeschritte. Sie benötigen eigene Nachweise. DNS und Keep-alive allein liefern
keine faire Instanzverteilung; eine überlebende Poolverbindung kann an derselben
Instanz bleiben.

Die aktuelle Request-ID hilft bei Korrelation, ersetzt aber keine Trace-Spans.
Bestehende HTTP-Metriken bleiben begrenzt; neue Labelwerte dürfen nicht aus
Benutzer-IDs, URLs oder beliebigen Request-Parametern entstehen.

## Bewusste Nicht-Ziele

Keine eigene Service-Registry, Service-Mesh-Implementierung, universelle Gateway-
Engine, Reflection-DI, automatische Controller-Erkennung, ORM, Eventbus und
Build-Tool-Neuentwicklung im Bootstrap. Eine Erweiterung benötigt einen konkreten
Dahemm-Use-Case oder ein gemessenes Defizit sowie einen kleinen reproduzierbaren Test.
