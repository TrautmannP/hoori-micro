# Hoori Micro

Ein eigenständiger Microservice-Bootstrap auf **HooriVM**, `hoori-rest-api` und
`hoori-http-api`. Arbeitsname: **Hoori Micro**, Maven-Artefakt: `dev.hoori:hoori-micro`.
Kein Fork der VM, kein Servlet-/Spring-Adapter und keine zweite HTTP-Implementierung.

**Status: Bootstrap.** Portable Checks, Maven/JUnit, Guest-Checks und der Docker-
Smoke-Test sind gegen die gepinnte Hoori-Distribution gelaufen; Umfang und Grenzen
siehe [Validierung](docs/validation.md). Keine Produktionsfreigabe.

## Actions statt Routen

Ein Service definiert seine Actions **einmal** bei sich. Daraus entstehen der lokale
Dispatcher und der veröffentlichte Katalog:

```java
Service recipes = Service.named("recipes").version(1)
        .action("get", GetRecipe.CODEC, RecipeCodec.INSTANCE, (ctx, input) -> repository.get(input.id))
        .http("GET", "/recipes/{id}").requirePermission("recipes:read");   // optional öffentlich
```

Ein Aufrufer kennt nur den fachlichen Namen und die Hauptversion, keine Route,
keinen Host und keine Instanz:

```java
Service shopping = Service.named("shopping").dependsOn("recipes", 1)
        .action("meal", GetRecipe.CODEC, RecipeCodec.INSTANCE,
                (ctx, input) -> ctx.call(Recipes.GET, input));        // typisiert
// generisch, ohne Vertragsklasse:  ctx.call("recipes.get", Map.of("id", 1))
```

```text
                 registry  (Katalog: Instanzen × Actions, TTL)
                  ↑   ↓ Registrierung, kleine Lease / bedingter Katalog
 Host ─► gateway ─────► shopping ─────► recipes
         (publizierte   POST /_hoori/invoke, direkt über hoori-http
          Actions)
```

| Änderung an Recipes | Shopping/Gateway neu deployen? |
|---|---|
| Neue Action, neue Implementierung, andere Adresse/Instanzzahl | Nein; Katalog aktualisiert sich über Heartbeats |
| Neue Action mit `http()` + `requirePermission()` | Nein; das Gateway übernimmt sie, sofern seine Policy die Permission gewährt |
| Verwendete Action entfernt oder inkompatibel geändert | Ja: Vertragsmigration bzw. neue Hauptversion. Discovery löst das nicht |

Details und Grenzen: [Architektur](docs/architecture.md),
[Konfiguration](docs/configuration.md), geplanter [Security-Ablauf](docs/security.md).

## Was enthalten ist

| Bereich | Implementiert |
|---|---|
| Service-Lifecycle | Expliziter Bootstrap, Live-/Ready-Zustände, Signal-Polling, geordneter Shutdown mit Deregistrierung |
| Actions | `Service`-Definition, typisierte `Action`-Verträge, generische Aufrufe (`JsonTree`), ein fester Invoke-Endpunkt |
| Registry | Zentrale In-Memory-Registry mit TTL, Heartbeats, Wiederanmeldung nach Neustart, `complete`-Markierung |
| Broker | Abhängigkeitsspezifischer Katalog ohne Gateway-Metadaten, Auswahl pro Action/Hauptversion, direkte Aufrufe, begrenztes Katalogalter |
| Gateway | Vom Anbieter deklarierte, per Policy freigegebene Routen; Konflikte werden zurückgehalten |
| Fehler und Kontext | Sichere Fehler, Request-ID über alle Hops, keine Retries/Redirects/Credential-Weitergabe |
| Betrieb | Hoori-HTTP-Metriken, begrenzte Bodies/Verbindungen/Timeouts, Docker Compose, nicht privilegierte Container |
| Prüfungen | Portable Checks, JUnit-Vertragstests, Distributionsprüfung, echte Hoori-/Docker-Abnahme |

Nicht enthalten: Authentifizierung (Registry, Invoke-Endpunkt und Demo-Gateway sind
unauthentifiziert), Mandantenmodell, Datenbankzugriff, Events, Circuit Breaker,
serviceübergreifendes Deadline-Budget, hochverfügbare Registry, DI-Container oder
migrierte Dahemm-Fachlogik.

## Schnellstart

### 1. Passende Hoori-Distribution bauen

Der Bootstrap ist an Hoori-Commit
`3254301e0b412669ffcc86a2c439327c18b72fe9` gebunden. Das verhindert, dass unterschiedliche
Quellstände trotz unveränderter SDK-Version `0.1.0` vermischt werden.
`hoori.lock.json` enthält diese Baseline.

Benötigt werden zum Bauen JDK 21, Maven 3.9.x, Python 3.10+ und die Rust-/Linux-
Build-Voraussetzungen des Hoori-Repositories. Zum Containerbetrieb werden Docker
Engine und Compose v2 benötigt. Im fertigen Laufzeit-Container ist kein JDK nötig.

Die folgenden Befehle **im vorhandenen Hoori-Checkout** ausführen. Ein separates
Worktree vermeidet Änderungen am eigenen Arbeitsstand:

```bash
# Einen noch nicht vorhandenen Zielpfad wählen.
git worktree add --detach ../hoori-micro-runtime 3254301e0b412669ffcc86a2c439327c18b72fe9
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
curl -fsS http://127.0.0.1:8080/meals/1     # gateway → shopping.meal → recipes.get
# Erwartet: {"id":1,"title":"Kartoffelsuppe"}
curl -fsS http://127.0.0.1:8080/recipes/1   # gateway → recipes.get

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
Er prüft Gateway-Veröffentlichung, Action-Aufrufe über zwei Hops, Kontext, das
Nachrüsten einer Action ohne Neustart von Shopping/Gateway, Registry-Ausfall und
-Neustart, Anbieter-Ausfall und den SIGTERM-Drain eines laufenden Aufrufs. Die wiederholten GETs im Test
sind neue Probeaufrufe, keine versteckte Retry-Funktion im Framework.

### Ohne Docker entwickeln

Nach erfolgreichem `build.sh` je ein Terminal, in dieser Reihenfolge:

```bash
./scripts/run-local.sh registry   # 127.0.0.1:8090
./scripts/run-local.sh recipes    # 127.0.0.1:8081
./scripts/run-local.sh shopping   # 127.0.0.1:8082
./scripts/run-local.sh gateway    # 127.0.0.1:8080
```

Der Launcher setzt Loopback, Registry- und Advertise-URL. Auch lokal startet er
Hoori, nicht HotSpot. Die drei Sekunden lange `slow`-Action braucht einen
Client-Timeout über drei Sekunden (`HOORI_CLIENT_TIMEOUT_MS`).

## Einen eigenen Service schreiben

```java
public static void main(String[] args) throws Exception {
    Service todos = Service.named("todos").version(1)
            .action("ping", JsonTree.CODEC, JsonTree.CODEC, (ctx, input) -> "pong");
    try (Microservice app = Microservice.create(todos)) {
        app.routes().get("/local", request -> Response.text(200, "ok")); // lokale Route, unverändert möglich
        app.run();
    }
}
```

Action-Fehler mit fachlicher Bedeutung als `RequestException(status, öffentlicheMeldung)`
werfen; Aufrufer sehen den Status über `ServiceCallException.upstreamStatus()`, nie den
Body. Aus einer normalen Route heraus ruft `app.context(request).call(...)` andere
Actions auf. Kein eingehender Authorization-/Cookie-Header wird weitergegeben.

Das Framework-JAR soll später in einem eigenen internen Maven-Repository publiziert
werden. Es ist derzeit **nicht** öffentlich auf Maven Central verfügbar. Die hier
verwendete Snapshot-Version ist für den Bootstrap, nicht für reproduzierbare Releases.

## Java formatieren

Spotless mit Palantir formatiert Java-Quellen und Tests in allen Modulen. Beide
Werkzeugversionen sind im Parent-POM festgelegt; sie laufen auf dem Host-JDK.
Danach setzt `scripts/BlankLines.java` per JDK-Syntaxbaum eine Leerzeile vor und
nach `if` sowie vor `return`, jeweils zwischen benachbarten Anweisungen. Direkt an
Blockklammern entstehen keine zusätzlichen Leerzeilen; `else if` bleibt zusammen.
Kommentare bleiben bei ihrer Anweisung. Der Schritt benötigt keine zusätzliche
Bibliothek und wird durch `scripts/test-core.sh` mitgeprüft.

```bash
mvn spotless:apply   # im Repository-Root alle Java-Dateien formatieren
mvn spotless:check   # Formatierung prüfen
```

Der Check läuft auch bei `mvn verify` und damit in `scripts/build.sh`.

## Struktur und nächster Schritt

```text
framework/                  hoori.micro: Service, Broker, Registry, Gateway
examples/demo-contracts/    Typisierte Demo-Actions (Recipes.GET) und Codecs
examples/recipes-service/  Rein lesender Anbieter
examples/shopping-service/ Aufrufer über Action-Namen, selbst Anbieter von meals
docker/                    Hoori-Entrypoint und Runtime-Image
scripts/                   Build, Integrität, lokale und native Prüfungen
docs/                      Architektur, Konfiguration, Roadmap und Nachweise
```

Die [Roadmap](docs/roadmap.md) trennt Framework-Abnahme, Dahemm-Vorbereitung und
spätere Migration. Die bestehende Hoori-VM-Roadmap wird nicht wieder geöffnet.
Als erster Schnitt ist ein **lesender, rückschaltbarer Dahemm-Use-Case** vorgesehen,
nicht die sofortige Zerlegung des gesamten Backends.

Reproduzierbare A–D-Lastkontrollen und ihre Messgrenzen stehen unter
[Vergleichsbenchmarks](docs/benchmarks.md).

Die Beispieldienste haben keine Authentifizierung und speichern keine Daten.
Sie sind ausschließlich für lokale/private Entwicklungsnetze gedacht. Ein internes
Docker-Netz ersetzt weder Service-Authentifizierung noch Mandantenautorisierung.
Siehe außerdem [NOTICE](NOTICE.md) zur noch offenen Lizenzentscheidung.
