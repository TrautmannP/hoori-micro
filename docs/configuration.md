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
| `HOORI_CLIENT_CONNECTIONS` | 16 | 1–512 Verbindungen im gesamten Client-Pool |
| `HOORI_CLIENT_PER_ORIGIN` | min(8, Gesamtlimit) | 1 bis Gesamtlimit |
| `HOORI_REQUEST_TIMEOUT_MS` | 10000 | 1–600000; Deadline eines eingehenden HTTP-Exchanges |
| `HOORI_CLIENT_TIMEOUT_MS` | 2000 | 1–600000; gesamter ausgehender Exchange inklusive Pool-Warten |
| `HOORI_CLIENT_IDLE_MS` | 5000 | 1–600000; Aufbewahrung ungenutzter Poolverbindungen |
| `HOORI_SHUTDOWN_GRACE_MS` | 10000 | 0–600000; Drain, danach Abbruch verbleibender Arbeit |
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

## Launcher und Docker

| Variable | Bedeutung |
|---|---|
| `HOORI_MAIN_CLASS` | Erforderlich für Entrypoint; Main-Klasse in Slash-Notation (`hoori/micro/Registry`, `hoori/micro/Gateway` oder Anwendung) |
| `HOORI_GATEWAY_PERMISSIONS` | Nur `Gateway.main`: kommagetrennte Permissions, die jedem Aufrufer gewährt werden; leer = nichts veröffentlicht. Keine Authentifizierung |
| `HOORI_ENGINE` | `mixed` als Default; zum Qualifizieren auch `interpreter` verwenden |
| `HOORI_OUTBOUND` | `none` oder `http`; letztere erteilt Connect- und Hostresolution-Capabilities, auch für HTTPS |
| `HOORI_TLS_CA_FILE` | Optionales lesbares PEM-CA-Bundle für den Hoori-Client; kein Abschalten der Verifikation |
| `HOORI_HOME` / `HOORI_APP_LIB` | Runtime-/JAR-Pfade für den lokalen Launcher; Docker nutzt `/opt/hoori` und `/opt/app/lib` |
| `HOORI_RUNTIME_BASE` | Compose-Buildargument, Default `debian:trixie-slim`; native Kompatibilität selbst qualifizieren |
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
Bei längerer Grace den äußeren Container-Timeout mit Sicherheitsabstand erhöhen.
Die Healthchecks sind lokale HTTP-Probes, keine garantierte Request-Verteilung und
kein automatischer Neustartmechanismus bei jedem ungesunden Zustand.

## Weitere Betriebsregeln

Pro Prozess ein `Microservice` besitzen und schließen; keine neuen Clients je
Aufruf. Ziele stammen ausschließlich aus dem Registry-Katalog, nie aus Request-Werten. HTTPS-Origins erfordern passende CA-Konfiguration und Netzwerkfreigaben.
Ein DNS-Name allein ist kein Service-Zertifikat oder Berechtigungsnachweis.

Für lokale Entwicklung `run-local.sh` nutzen: es setzt Loopback und startet trotzdem
die echte HooriVM. `java -jar ...` ist kein unterstützter Networking-Modus.
