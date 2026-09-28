# Hoori Micro

Ein eigenständiger Microservice-Bootstrap auf **HooriVM**, `hoori-rest-api` und
`hoori-http-api`. Arbeitsname: **Hoori Micro**, Maven-Artefakt: `dev.hoori:hoori-micro`.
Kein Fork der VM, kein Servlet-/Spring-Adapter und keine zweite HTTP-Implementierung.

**Status: Bootstrap, noch nicht auf HooriVM/Docker abgenommen.** Die reinen
Konfigurations-/Discovery-Prüfungen und die Distributionsprüfungen wurden ausgeführt.
Die vollständigen Maven-, Guest- und Container-Prüfungen sind vorbereitet, aber noch
nicht ausgeführt. Siehe [Validierung](docs/validation.md).

## Kommunikation ohne Adressverwaltung

```text
Host: 127.0.0.1:8080
          │
          ▼
  shopping:8080 ── HTTP/1.1 + JSON ──► recipes:8080
          │          Docker-DNS              │
          └──────── privates Backend-Netz ────┘
```

Ein Service deklariert seinen Namen und seine Abhängigkeiten:

```java
try (Microservice app = Microservice.create("shopping", "recipes")) {
    // Routen und explizite Controller-Abhängigkeiten registrieren.
    app.run();
}
```

Der logische Name `recipes` wird standardmäßig zu `http://recipes:8080`. Docker
Compose stellt den DNS-Namen im gemeinsamen Netzwerk bereit. Es gibt keine
festen Container-IPs, Registrierungsschleifen, Registry-Datenbank oder
Docker-Socket-Anbindung. Ein Service benötigt keine veröffentlichten Host-Ports,
um von einem anderen Container im gemeinsamen Netzwerk erreichbar zu sein.

**Automatisch heißt namensbasiert, nicht „jeder entdeckt und vertraut jedem“.**
Die Compose-Service-Namen müssen den Abhängigkeitsnamen entsprechen. Der Port
8080 ist eine Konvention. DNS findet einen Host, aber weder dessen API-Verträge
noch seine Berechtigungen. Andere Hosts/Ports werden ausschließlich bei Bedarf
overridden, beispielsweise außerhalb von Docker:

```bash
export HOORI_SERVICE_RECIPES_URL=http://127.0.0.1:8081
```

Details und Grenzen: [Architektur](docs/architecture.md),
[Konfiguration](docs/configuration.md).

## Was enthalten ist

| Bereich | Implementiert |
|---|---|
| Service-Lifecycle | Expliziter Bootstrap, Konfiguration, lokale Live-/Ready-Zustände, Signal-Polling und geordneter Shutdown |
| Kommunikation | Deklarierte Service-Namen, validierte URL-Overrides, ein gemeinsam genutzter Hoori-Client-Pool pro Service |
| Aufrufe | Raw-HTTP sowie typisierte JSON-GET/POST-Helfer mit expliziten Codecs |
| Fehler und Kontext | Sichere öffentliche Fehler, explizite Request-ID-Weitergabe, keine automatischen Retries/Redirects oder Credential-Weitergabe |
| Betrieb | Hoori-HTTP-Metriken, begrenzte Bodies/Verbindungen/Timeouts, Docker Compose und nicht privilegierte Container |
| Beispiele | Zwei zustandslose, rein lesende Demo-Services mit typisiertem Aufruf, Kontext- und Drain-Prüfrouten |
| Build | Maven-Multi-Modul, revisionsgebundene Runtime-Distribution, isoliertes Maven-Repository, Docker-Staging |
| Prüfungen | Portable Checks, JUnit-Vertragstests, Distributionsprüfung und echte Hoori-/Docker-Abnahme-Scripte |

Nicht enthalten: produktive Authentifizierung, Mandantenmodell, Datenbankzugriff,
Broker/Events, Circuit Breaker, serviceübergreifendes Deadline-Budget, automatische
Lastverteilung, API-Gateway, DI-Container oder bereits migrierte Dahemm-Fachlogik.

## Schnellstart

### 1. Passende Hoori-Distribution bauen

Der Bootstrap ist an Hoori-Commit
`550d608f7885d73233c4941f185afcabf6f9b5e8` gebunden. Das verhindert, dass unterschiedliche
Quellstände trotz unveränderter SDK-Version `0.1.0` vermischt werden.
`hoori.lock.json` enthält diese Baseline.

Benötigt werden zum Bauen JDK 21, Maven 3.9.x, Python 3.10+ und die Rust-/Linux-
Build-Voraussetzungen des Hoori-Repositories. Zum Containerbetrieb werden Docker
Engine und Compose v2 benötigt. Im fertigen Laufzeit-Container ist kein JDK nötig.

Die folgenden Befehle **im vorhandenen Hoori-Checkout** ausführen. Ein separates
Worktree vermeidet Änderungen am eigenen Arbeitsstand:

```bash
# Einen noch nicht vorhandenen Zielpfad wählen.
git worktree add --detach ../hoori-micro-runtime 550d608f7885d73233c4941f185afcabf6f9b5e8
cd ../hoori-micro-runtime

export JAVA_HOME=/pfad/zum/jdk-21
export HOORI_JAVA21_HOME="$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/build-distribution.sh --release

# Pfad für Schritt 2 merken:
target_triple=$(rustc -vV | sed -n 's/^host: //p')
printf '%s\n' "$PWD/target/distributions/$target_triple/headless/release"
```

Die Distribution muss **clean**, headless und zur Zielarchitektur passen.
`SYSTEM.txt` beschreibt native Abhängigkeiten. Keine beliebigen Runtime-/SDK-JARs
zusammenkopieren. Eine bereits vorhandene passende Distribution kann direkt
verwendet werden; ein Neubau ist dann nicht nötig.

### 2. Dieses Repository bauen und starten

```bash
# Im entpackten hoori-micro-Verzeichnis:
export JAVA_HOME=/pfad/zum/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/build.sh /absoluter/pfad/zur/headless/release-distribution

docker compose up --build --wait
curl -fsS http://127.0.0.1:8080/demo/meal/1
# Erwartet: {"id":1,"title":"Kartoffelsuppe"}

curl -fsS http://127.0.0.1:8080/health/ready
curl -fsS http://127.0.0.1:8080/metrics
docker compose down
```

`build.sh` überprüft die Runtime-Prüfsummen und Revision, installiert ihre drei
benötigten JARs in `.cache/m2`, führt `mvn clean verify` aus und erzeugt
`.docker-context/`. Dieser Build-Kontext enthält nur Runtime, Anwendungs-JARs und
Docker-Dateien, keinen privaten Checkout und keine GitHub-Zugangsdaten.

Das Dockerfile verwendet standardmäßig `debian:trixie-slim`. Das ist ein
**Entwicklungsdefault**, keine Zusage für jede lokal gebaute ELF-Datei. Bei anderer
Architektur/glibc oder weiteren Anforderungen eine kompatible Debian-/Ubuntu-Basis
über `HOORI_RUNTIME_BASE` wählen. Der Image-Build führt das tatsächliche Binary
mit `build-info` aus und scheitert bei fehlenden nativen Bibliotheken. Vor Produktion
Basisimage per Digest festlegen und das resultierende Image separat prüfen.

### 3. Integration wirklich abnehmen

```bash
./scripts/test-core.sh
python3 -m unittest discover -s scripts/tests -v
./scripts/test-hoori-core.sh
python3 scripts/smoke.py
```

Der Smoke-Test verwendet ein eigenes Compose-Projekt und standardmäßig Port 18080.
Er prüft Aufrufe, JSON, Kontext, Ausfall/Wiederanlauf, Container-Neuerstellung und
den SIGTERM-Drain eines laufenden ausgehenden Aufrufs. Die wiederholten GETs im Test
sind neue Probeaufrufe, keine versteckte Retry-Funktion im Framework.

### Ohne Docker entwickeln

Nach erfolgreichem `build.sh` in zwei Terminals auf derselben Linux-Maschine:

```bash
HOORI_PORT=8081 ./scripts/run-local.sh recipes
```

```bash
HOORI_SERVICE_RECIPES_URL=http://127.0.0.1:8081 ./scripts/run-local.sh shopping
```

Der lokale Launcher bindet standardmäßig an Loopback. Auch lokal startet er Hoori,
nicht HotSpot. Für die künstlich drei Sekunden lange `/demo/slow`-Route muss der
Client-Timeout über drei Sekunden liegen; Compose verwendet dafür längere Demo-
Timeouts. Cold-JIT-Qualifikation und spätere Produktions-Timeouts getrennt behandeln.

## Einen eigenen Service schreiben

```java
package example;

import hoori.micro.Microservice;
import hoori.http.Response;

public final class Main {
    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create("todos")) {
            app.routes().get("/v1/ping", request -> Response.text(200, "pong"));
            app.run();
        }
    }
}
```

Ein aufrufender Controller verwendet den Hoori-Request explizit als Kontext:

```java
Recipe recipe = app.client().getJson(
        request.raw(), "recipes", "/v1/recipes/1", RecipeCodec.INSTANCE);
```

`Recipe` und `RecipeCodec` stammen hier aus dem Demo-Modul. Eigene APIs definieren
ihre eigenen versionierten Verträge. Für POST gibt es `postJson`; für andere
Methoden, Header, leere Erfolgsantworten oder explizite Statusauswertung `exchange`.
Kein eingehender Authorization-/Cookie-Header wird dabei automatisch übernommen.

Das Framework-JAR soll später in einem eigenen internen Maven-Repository publiziert
werden. Es ist derzeit **nicht** öffentlich auf Maven Central verfügbar. Die hier
verwendete Snapshot-Version ist für den Bootstrap, nicht für reproduzierbare Releases.

## Struktur und nächster Schritt

```text
framework/                  Wiederverwendbare hoori.micro-Bibliothek
examples/demo-contracts/    Kleiner expliziter JSON-Vertrag der Demo
examples/recipes-service/  Rein lesender Anbieter
examples/shopping-service/ Aufrufer über logischen Service-Namen
docker/                    Hoori-Entrypoint und Runtime-Image
scripts/                   Build, Integrität, lokale und native Prüfungen
docs/                      Architektur, Konfiguration, Roadmap und Nachweise
```

Die [Roadmap](docs/roadmap.md) trennt Framework-Abnahme, Dahemm-Vorbereitung und
spätere Migration. Die bestehende Hoori-VM-Roadmap wird nicht wieder geöffnet.
Als erster Schnitt ist ein **lesender, rückschaltbarer Dahemm-Use-Case** vorgesehen,
nicht die sofortige Zerlegung des gesamten Backends.

Die Beispieldienste haben keine Authentifizierung und speichern keine Daten.
Sie sind ausschließlich für lokale/private Entwicklungsnetze gedacht. Ein internes
Docker-Netz ersetzt weder Service-Authentifizierung noch Mandantenautorisierung.
Siehe außerdem [NOTICE](NOTICE.md) zur noch offenen Lizenzentscheidung.
