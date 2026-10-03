# Konfiguration

## Namen

Service-, Action- und Instanznamen: 1–63 Zeichen, erster Buchstabe `a-z`, danach
`a-z`, Ziffern oder Bindestrich, kein Bindestrich am Ende. Aufrufe verwenden
`<service>.<action>`, Permissions `<service>:<scope>`. Hauptversionen 1–9999.
Höchstens 128 Actions und 32 Abhängigkeiten pro Service.

Registry- und Advertise-URLs sind `http`/`https`-Origins mit Host und optional Port;
kein Benutzer/Passwort, Query, Fragment oder Basispfad. Gateway-Templates beginnen mit
`/`, nicht mit `/_hoori`, und bestehen aus unreservierten Literalen oder `{name}`.

## Java-Service-Konfiguration

Alles wird einmal beim Start gelesen. Leere, gepaddete oder ungültige Werte sind
Fehler, kein stiller Fallback. `Environment` ist als Einzelschlüssel-Lookup injizierbar;
damit lassen sich Konfigurationen ohne Prozess-Environment testen.

| Variable | Default | Bedeutung / Grenze |
|---|---:|---|
| `HOORI_BIND_ADDRESS` | `0.0.0.0` | Numerische IPv4-Bindeadresse; lokaler Launcher setzt `127.0.0.1` |
| `HOORI_PORT` | 8080 | 1–65535; ein Override ändert nicht automatisch Client-Destinationsports |
| `HOORI_BODY_BYTES` | 65536 | 1024–16777216; HTTP-Body-Limit und JSON-Ausgabelimit |
| `HOORI_SERVER_CONNECTIONS` | 32 | 1–512 zugelassene Serververbindungen |
| `HOORI_CLIENT_CONNECTIONS` | 16 | 1–512 Verbindungen im Datenpool; zusätzlich höchstens eine Control-Verbindung |
| `HOORI_CLIENT_PER_ORIGIN` | min(8, Gesamtlimit) | 1 bis Gesamtlimit |
| `HOORI_CLIENT_PENDING_ACQUIRES` | 0 | 0–4096 SDK-Wartende im Datenpool; normalerweise bei null lassen, um keine zweite Queue zu bilden. Control hat immer null Wartende |
| `HOORI_INCOMING_CALLS` | min(16, Serververbindungen) | 1 bis Serverlimit; alle Fachrequests vor DTO-Decoding/Handler |
| `HOORI_INCOMING_PENDING_CALLS` | 0 | 0 bis Serverlimit minus Incoming-Calls; wartende Fachrequests; zählen als aktive Roots |
| `HOORI_OUTGOING_CALLS` | Client-Per-Origin-Limit | 1 bis Datenverbindungen; ein globales Permit für Encoding, RPC und Ergebnis-Decoding |
| `HOORI_OUTGOING_PENDING_CALLS` | Outgoing-Calls | 0–512 Wartende vor Encoding; null bedeutet Fail-fast |
| `HOORI_REQUEST_TIMEOUT_MS` | 10000 | 1–600000; Deadline eines eingehenden HTTP-Exchanges |
| `HOORI_CLIENT_TIMEOUT_MS` | 2000 | 1–600000; Call-Grenze vor Admission/Encoding, zusätzlich vom laufenden Parent-Budget begrenzt |
| `HOORI_WORK_TIMEOUT_MS` | Client-Timeout | 1–600000; gemeinsames Budget der Request-/Service-Operation, zusätzlich durch laufende HTTP-/Wire-Frist begrenzt |
| `HOORI_CONTROL_TIMEOUT_MS` | min(1000, TTL/2) | 1 bis min(10000, TTL/2); Registry-Exchange, unabhängig vom Daten-Timeout |
| `HOORI_CLIENT_IDLE_MS` | 5000 | 1–600000; Aufbewahrung ungenutzter Poolverbindungen |
| `HOORI_SHUTDOWN_GRACE_MS` | 10000 | 0–600000; Grace ab Stop, danach Cancellation und tatsächlichen Scope-Drain abwarten |
| `HOORI_REGISTRY_URL` | `http://registry:8080` | Origin der Registry |
| `HOORI_ADVERTISE_URL` | `http://$HOSTNAME:<port>` | Unter dieser Adresse erreichen andere Instanzen diese; ohne `HOSTNAME` der Service-Name |
| `HOORI_INSTANCE_ID` | `<name>-<zufällig>` | Registry-Schlüssel dieser Instanz |
| `HOORI_HEARTBEAT_MS` | 2000 | 100–60000; Lease-/Katalogperiode mit ±10 % Jitter; bei Fehlern begrenztes Backoff |
| `HOORI_REGISTRY_TTL_MS` | 3 × Heartbeat | Ablauf einer Registrierung und Dauer von `complete=false`; Broker begrenzen die erfolgreiche Periode vor Jitter auf TTL/2. Zwischen Registry und Anbietern passend konfigurieren |
| `HOORI_CATALOG_MAX_AGE_MS` | 30000 | Größer als Heartbeat; so lange bleibt ein Katalog ohne erfolgreiche Aktualisierung gültig |

Weitere feste Bootstrap-Limits: 64 Header, 16 KiB Headerbytes, 100 Requests je
Verbindung; JSON-Tiefe 64, Stringlänge 16384 UTF-16-Codeunits und Zahlentokenlänge
128. Diese Grenzen stammen aus der bewusst kleinen Policy des Bootstraps.
Ein stark vergrößertes Routing-Katalog-Metrikdokument kann mehr Bodybudget benötigen;
`/metrics` unter der realen Routenzahl prüfen. Nicht jeden Grenzwert pauschal erhöhen.

Gateway-Snapshots erlauben zusätzlich maximal 256 distinct Routen und 64 KiB
Routenmetadaten. Ein größeres Bodybudget hebt diese Grenzen nicht auf. Überlauf
verwirft das ganze Update; der alte Snapshot bleibt nur bis zu seiner Frischegrenze.
Interne Restbudgets und die relative Wire-Grenze stehen in
[architecture.md](architecture.md).

## Launcher und Docker

| Variable | Bedeutung |
|---|---|
| `HOORI_MAIN_CLASS` | Erforderlich für Entrypoint; Main-Klasse in Slash-Notation (`hoori/micro/Registry`, `hoori/micro/Gateway` oder Anwendung) |
| `HOORI_GATEWAY_PERMISSIONS` | Nur `Gateway.main`: kommagetrennte Permissions, die jedem Aufrufer gewährt werden; leer = nichts veröffentlicht. Keine Authentifizierung |
| `HOORI_ENGINE` | `mixed` als Default; zum Qualifizieren auch `interpreter` verwenden |
| `HOORI_OUTBOUND` | `none` oder `http`; letztere erteilt Connect- und Hostresolution-Capabilities, auch für HTTPS |
| `HOORI_TLS_CA_FILE` | Optionales lesbares PEM-CA-Bundle für den Hoori-Client; kein Abschalten der Verifikation |
| `HOORI_HOME` / `HOORI_APP_LIB` | Runtime-/JAR-Pfade für den lokalen Launcher; Docker nutzt `/opt/hoori` und `/opt/app/lib` |
| `HOORI_RUNTIME_BASE` | Compose-Buildargument; Default ist der amd64-Digest aus `docker/Dockerfile`, mit Debian-Paketsnapshot vom 30.09.2026. Andere Debian-Basis erneut qualifizieren |
| `HOORI_MAX_HEAP_BYTES` | Entrypoint: 33554432 als logisches Guest-Heap-Limit; begrenzt nicht den gesamten Prozess-RSS |
| `HOORI_DEMO_PORT` | Loopback-Hostport der Demo; 8080 normal, 18080 im Smoke-Test |
| `HOORI_DEMO_CLIENT_TIMEOUT_MS` | Nur Compose-Demo: 30000, um Cold-Compilation getrennt zu qualifizieren |
| `HOORI_DEMO_REQUEST_TIMEOUT_MS` | Nur Compose-Demo: 60000; ersetzt nicht den Framework-Default |
| `HOORI_DEMO_RECOMMEND` | Nur Demo: `1` simuliert einen Recipes-Release mit zusätzlicher Action `recommend` |

Die Compose-Datei leitet bewusst nur ihre deklarierten Variablen in Container.
Ein `export HOORI_PORT=...` auf dem Host landet nicht automatisch im Container.
Zusätzliche Overrides in `environment`/einer Compose-Override-Datei ergänzen.
Der mitgelieferte Healthcheck verwendet den festen Container-Port 8080; bei einem
abweichenden Container-Port Healthcheck und `HOORI_REGISTRY_URL` mitändern. Die
Default-Advertise-URL nutzt die Container-ID als Hostnamen, die Docker im
gemeinsamen Netz auflöst; damit funktionieren auch mehrere Replikate.

`stop_grace_period` ist in der Demo 15 Sekunden, die Framework-Grace 10 Sekunden.
Deregistrierung läuft unabhängig von der fachlichen Grace. Ihr abschließender
Join nach dem Scope-/HTTP-Drain benötigt höchstens drei Control-Timeouts.
Nach Grace kann verpflichtendes Cleanup länger dauern; nicht kooperierende Arbeit
hat keine garantierte Abschlusszeit. Den äußeren Prozess-Timeout bewusst wählen;
ein erzwungener Kill ist kein erfolgreicher Drain.
Die Healthchecks sind lokale HTTP-Probes, keine garantierte Request-Verteilung und
kein automatischer Neustartmechanismus bei jedem ungesunden Zustand.

## Weitere Betriebsregeln

Das separat gebaute [Datenbeispiel](../examples/local-data/README.md) benötigt
`HOORI_DB_URL` (PostgreSQL-JDBC-URL ohne Queryparameter), `HOORI_DB_USER` und
`HOORI_DB_PASSWORD`; `HOORI_DB_SSLMODE` ist standardmäßig `require`. `disable`
ist nur für die lokale Wegwerf-Datenbank vorgesehen. Der Beispiel-Launcher setzt
Loopback, Port 8084, passende Advertise-URL und die lokale Registry auf 8090,
sofern diese Werte nicht ausdrücklich gesetzt sind. Connect-/I/O-/Cancel-Bounds
stehen zentral in `Database`, nicht an jedem Fachaufruf. Die normale Compose-
Datei startet keinen Datenbankdienst und erhält diese Variablen nicht.

Pro Prozess ein `Microservice` besitzen und schließen; keine neuen Clients je
Aufruf. Die festen Pool-Metriken `hoori_micro_pool_{active_connections,idle_connections,pending_acquires,rejected_acquires_total}`
tragen ausschließlich `pool="data"` oder `pool="control"`. Aktive Verbindungen zählen
auch reservierte DNS-/Connect-/TLS-Slots. Ziele stammen ausschließlich aus dem
Registry-Katalog, nie aus Request-Werten. HTTPS-Origins erfordern passende CA-Konfiguration und Netzwerkfreigaben.
Ein DNS-Name allein ist kein Service-Zertifikat oder Berechtigungsnachweis.

Die Admission-Metriken `hoori_micro_calls_{active,pending,rejected_total,expired_total}`
haben nur `direction="incoming"`/`"outgoing"`. Normale lokale Routen, Invoke und
Gateway teilen die eingehende Fachgrenze. Health und feste Control-Routen liegen
außerhalb, teilen aber weiter HTTP-/Carrier-Kapazität. Das SDK hat den begrenzten Raw-Body vor Admission bereits
gelesen. Sättigung und Stop weisen Wartende sicher mit 503 ab; es gibt keine Retries.
Bereits zugelassene Handler dürfen beim Drain sofort weitere Calls starten, sofern
ein Slot frei ist; neue Wartende und Hintergrundcalls werden dann abgewiesen.

`/metrics` erfasst zusätzlich Guest-/committed Heap, RSS, Allokationen, GC, Tasks
und offene Handles unter festen Namen ohne Request-Labels. RSS enthält native/JIT-
Daten; Containerspeicher einschließlich Dateicache wird separat über cgroups
gemessen. `hoori_micro_operations_{active,overdue,completed_total}`,
`hoori_micro_operation_drain_nanos_total` und
`hoori_micro_operation_failures_total{reason="…"}` verwenden neun feste Gründe:
`business`, `capacity`, `stopping`, `cancelled`, `deadline`, `io_timeout`, `upstream`,
`interrupted`, `internal`. Die Drain-Zeit umfasst Abschluss nach Rückkehr des
Framework-Bodys, nicht Response-I/O. Ein intern als Deadline klassifizierter Fehler
kann bei abgelaufenem Transportbudget keine HTTP-Antwort mehr senden.

Für lokale Entwicklung `run-local.sh` nutzen: es setzt Loopback und startet trotzdem
die echte HooriVM. `java -jar ...` ist kein unterstützter Networking-Modus.
