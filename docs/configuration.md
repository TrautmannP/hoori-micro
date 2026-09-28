# Konfiguration

## Namenskonvention

`Microservice.create("shopping", "recipes")` setzt den eigenen Defaultnamen und
meldet die Abhängigkeit `recipes` an. Gültig sind 1–63 Zeichen, erster Buchstabe
`a-z`, anschließend `a-z`, Ziffern oder Bindestrich; kein Bindestrich am Ende.
Keine Unterstriche, Großbuchstaben, Punkte oder frei vom Request gelieferte URLs.

Der Defaultorigin ist `http://<name>:8080`. `shopping-list` verwendet für ein
Override den Schlüssel `HOORI_SERVICE_SHOPPING_LIST_URL`. Maximal 32 Abhängigkeiten;
unbekannte oder doppelte Namen werden abgewiesen. Andere Ports/Hosts erfordern
kein neues Interface:

```bash
HOORI_SERVICE_RECIPES_URL=https://recipes.internal:8443
```

Origins sind `http` oder `https` mit Host und optional Port; Schema in Kleinbuchstaben.
Kein Benutzer/Passwort, Query, Fragment oder Basispfad. Ein abschließendes `/` ist
erlaubt. Request-Pfade müssen mit genau einem `/` beginnen; relative URLs,
Hostwechsel über `//...`, Fragmente und unkodierte Steuerzeichen sind verboten.
Parameter vor dem Zusammensetzen geeignet URI-kodieren; die Helfer kodieren keine
beliebigen Benutzerwerte automatisch. Die Beispiel-IDs werden zuerst als Zahlen
validiert. Die Discovery führt keine DNS-Prüfung während des Bootstraps aus.

## Java-Service-Konfiguration

Alles wird einmal beim Start gelesen. Leere, gepaddete oder ungültige Werte sind
Fehler, kein stiller Fallback. `Environment` ist als Einzelschlüssel-Lookup injizierbar;
damit lassen sich Konfigurationen ohne Prozess-Environment testen.

| Variable | Default | Bedeutung / Grenze |
|---|---:|---|
| `HOORI_SERVICE_NAME` | Name aus `create` | Log-/Konfigurationsname; benennt den Docker-Service nicht um |
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

Weitere feste Bootstrap-Limits: 64 Header, 16 KiB Headerbytes, 100 Requests je
Verbindung; JSON-Tiefe 64, Stringlänge 16384 UTF-16-Codeunits und Zahlentokenlänge
128. Diese Grenzen stammen aus der bewusst kleinen Policy des Bootstraps.
Ein stark vergrößertes Routing-Katalog-Metrikdokument kann mehr Bodybudget benötigen;
`/metrics` unter der realen Routenzahl prüfen. Nicht jeden Grenzwert pauschal erhöhen.

## Launcher und Docker

| Variable | Bedeutung |
|---|---|
| `HOORI_MAIN_CLASS` | Erforderlich für Entrypoint; Main-Klasse in Slash-Notation |
| `HOORI_ENGINE` | `mixed` als Default; zum Qualifizieren auch `interpreter` verwenden |
| `HOORI_OUTBOUND` | `none` oder `http`; letztere erteilt Connect- und Hostresolution-Capabilities, auch für HTTPS |
| `HOORI_TLS_CA_FILE` | Optionales lesbares PEM-CA-Bundle für den Hoori-Client; kein Abschalten der Verifikation |
| `HOORI_HOME` / `HOORI_APP_LIB` | Runtime-/JAR-Pfade für den lokalen Launcher; Docker nutzt `/opt/hoori` und `/opt/app/lib` |
| `HOORI_RUNTIME_BASE` | Compose-Buildargument, Default `debian:trixie-slim`; native Kompatibilität selbst qualifizieren |
| `HOORI_DEMO_PORT` | Loopback-Hostport der Demo; 8080 normal, 18080 im Smoke-Test |
| `HOORI_DEMO_CLIENT_TIMEOUT_MS` | Nur Compose-Demo: 30000, um Cold-Compilation getrennt zu qualifizieren |
| `HOORI_DEMO_REQUEST_TIMEOUT_MS` | Nur Compose-Demo: 60000; ersetzt nicht den Framework-Default |

Die Compose-Datei leitet bewusst nur ihre deklarierten Variablen in Container.
Ein `export HOORI_PORT=...` auf dem Host landet nicht automatisch im Container.
Zusätzliche Overrides in `environment`/einer Compose-Override-Datei ergänzen.
Der mitgelieferte Healthcheck verwendet den festen Container-Port 8080; bei einem
abweichenden Container-Port müssen Healthcheck und Aufrufer-Origin mitgeändert werden.

`stop_grace_period` ist in der Demo 15 Sekunden, die Framework-Grace 10 Sekunden.
Bei längerer Grace den äußeren Container-Timeout mit Sicherheitsabstand erhöhen.
Die Healthchecks sind lokale HTTP-Probes, keine garantierte Request-Verteilung und
kein automatischer Neustartmechanismus bei jedem ungesunden Zustand.

## Weitere Betriebsregeln

Pro Prozess ein Service-Objekt besitzen und schließen; keine neuen Clients je
Controlleraufruf. Authentifizierte Requests dürfen keine Ziel-URL aus Benutzereingaben
übernehmen. HTTPS-Origins erfordern passende CA-Konfiguration und Netzwerkfreigaben.
Ein DNS-Name allein ist kein Service-Zertifikat oder Berechtigungsnachweis.

Für lokale Entwicklung `run-local.sh` nutzen: es setzt Loopback und startet trotzdem
die echte HooriVM. `java -jar ...` ist kein unterstützter Networking-Modus.
