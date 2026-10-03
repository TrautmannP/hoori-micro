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
        .action(Recipes.GET, (ctx, input) -> repository.get(input.id))
        .http("get", "GET", "/recipes/{id}").requirePermission("get", "recipes:read");
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
                  ↑   ↓ Registrierung, Lease / bedingter Katalog
 Host ─► gateway ─────► shopping ────► recipes
         publizierte      │
         Actions         └─────────► pantry
                   POST /_hoori/invoke, direkt über hoori-http
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
| Gateway | Vorbereitete, begrenzte Routing-Snapshots mit per Request geprüfter Policy; Konflikte werden zurückgehalten |
| Fehler und Kontext | Gemeinsames Restbudget und Request-ID über alle Hops, sichere Fehler, keine Retries/Redirects/Credential-Weitergabe |
| Betrieb | Admission vor Framework-JSON-Verarbeitung, feste Call-/Pool-Metriken, eigener Control-Pool, begrenzte Bodies/Verbindungen/Wartende/Timeouts, Docker Compose |
| Prüfungen | Portable Checks, JUnit-Vertragstests, Distributionsprüfung, echte Hoori-/Docker-Abnahme |

Nicht enthalten: Authentifizierung (Registry, Invoke-Endpunkt und Demo-Gateway sind
unauthentifiziert), Mandantenmodell, Datenbankzugriff, Events, Circuit Breaker,
hochverfügbare Registry, DI-Container oder
migrierte Dahemm-Fachlogik.

## Schnellstart

### 1. Passende Hoori-Distribution bauen

Der Bootstrap ist an Hoori-Commit
`7d7245aa782ba6f79c47397f008789f13552a4c5` gebunden. Das verhindert, dass unterschiedliche
Quellstände trotz unveränderter SDK-Version `0.1.0` vermischt werden.
`hoori.lock.json` enthält diese Baseline.

Benötigt werden zum Bauen JDK 21, Maven 3.9.x, Python 3.10+ und die Rust-/Linux-
Build-Voraussetzungen des Hoori-Repositories. Zum Containerbetrieb werden Docker
Engine und Compose v2 benötigt. Im fertigen Laufzeit-Container ist kein JDK nötig.

Die folgenden Befehle **im vorhandenen Hoori-Checkout** ausführen. Ein separates
Worktree vermeidet Änderungen am eigenen Arbeitsstand:

```bash
# Einen noch nicht vorhandenen Zielpfad wählen.
git worktree add --detach ../hoori-micro-runtime 7d7245aa782ba6f79c47397f008789f13552a4c5
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
curl -fsS http://127.0.0.1:8080/overview/1  # shopping → recipes + pantry
curl -fsS http://127.0.0.1:8080/dashboard/2 # pantry: erfolgreich, leere Liste

curl -fsS http://127.0.0.1:8080/health/ready
curl -fsS http://127.0.0.1:8080/metrics
docker compose down
```

`build.sh` überprüft Runtime-Prüfsummen, Revision, SDK-Koordinaten und Original-POMs,
installiert Guest Base sowie HTTP, REST, Concurrent und Concurrent HTTP in
`.cache/m2/<SHA256-der-Distribution>`, führt `mvn clean verify` aus und erzeugt
`.docker-context/`. Dieser Build-Kontext enthält nur Runtime, Anwendungs-JARs und
Docker-Dateien, keinen privaten Checkout und keine GitHub-Zugangsdaten.
Die SDKs werden mit ihren ausgelieferten POMs installiert; Guest Base hat upstream
keinen POM und verwendet dessen dokumentierte `install-file`-Konvention.
`runtimeSdks` in `hoori.lock.json` bestimmt Prüfung, Installation und Klassenpfad.
Das originale Distributionspaket bleibt vollständig und prüfbar; seine übrigen
SDKs einschließlich DB-Adaptern und Processor liegen außerhalb des Klassenpfads.
Ein HTTP-Service benötigt weder Datenbankbibliotheken noch einen Laufzeit-Processor.

Dockerfile und Compose pinnen `debian:trixie-slim` auf den amd64-Digest
`sha256:7792b1f7702a86946cd518db72b6a407302c3e9bc1635634368b878189e8221c`
und signierte Debian-Paketquellen auf den Snapshot vom 30.09.2026. Qualifiziert ist
Linux x86_64; eine andere Debian-Basis über `HOORI_RUNTIME_BASE` erneut prüfen.
Der Build führt das tatsächliche Binary mit `build-info` aus und scheitert bei
fehlenden nativen Bibliotheken. Aktualisierungen von Basis/Paketen bewusst neu pinnen.

### 3. Integration wirklich abnehmen

```bash
./scripts/test-core.sh
python3 -m unittest discover -s scripts/tests -v
./scripts/test-hoori-core.sh
./scripts/test-task-runtime.sh
python3 scripts/test_budgets.py
python3 scripts/test_tasks.py
python3 scripts/test_composition.py
python3 scripts/smoke.py
```

Der Smoke-Test verwendet ein eigenes Compose-Projekt und standardmäßig Port 18080.
Er prüft Gateway-Veröffentlichung, Action-Aufrufe über zwei Hops, Kontext, das
Nachrüsten einer Action ohne Neustart von Shopping/Gateway, Registry-Ausfall und
-Neustart, wiederholte Lastspitzen, Routenentfernung, Anbieter-Crash/TTL und SIGTERM
bei blockierter Registry unter laufenden Calls. Ressourcen und Identitäten landen
in `.cache/hoori-micro-check-<pid>.json`. Die wiederholten GETs im Test
sind neue Probeaufrufe, keine versteckte Retry-Funktion im Framework.

### Ohne Docker entwickeln

Nach erfolgreichem `build.sh` je ein Terminal, in dieser Reihenfolge:

```bash
./scripts/run-local.sh registry   # 127.0.0.1:8090
./scripts/run-local.sh recipes    # 127.0.0.1:8081
./scripts/run-local.sh pantry     # 127.0.0.1:8083
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
Body. Aus einer normalen Route heraus ruft `app.context().call(...)` andere
Actions auf. Jede Fachroute besitzt automatisch eine verwaltete Request-Operation. Kein eingehender Authorization-/Cookie-Header wird weitergegeben.

`ctx.call(...)` führt direkt aus. `ctx.task(...)` erzeugt einen normalen, noch nicht
gestarteten `TaskSpec`; erst `Tasks.parallel(...).map(...)` startet die ausdrücklich
komponierten Calls. Die Specs halten Eingaben per Referenz und lesen den aktuellen
 Kontext erst bei ihrer Ausführung. Eingaben währenddessen nicht verändern.

```java
// Innerhalb einer Action oder normalen Route, ohne manuelles fork/join:
return Tasks.parallel(ctx.task(Recipes.GET, query), ctx.task(Pantry.FOR_RECIPE, query))
        .named("shopping.overview").failFast().map(Overview::new);
```

Das Framework gibt Antworten erst nach Kind-/Ressourcenabschluss frei. `Invocation`
enthält nur Korrelation und Ursprung; `ctx.ownerRequest()` ist ausschließlich beim
HTTP-Owner verfügbar, nicht in Kindern. Request-ID ist keine Identität/Berechtigung.
Ein kürzeres lokales Budget setzt `TaskScope.named("operation").within(duration)`;
es verlängert nie die laufende Request-Frist. Startup-/Wartungsarbeit verwendet
`app.runTask(task)` und wartet synchron auf ihren Abschluss. Calls ohne gültige
Micro-Ausführungsgrenze werden vor JSON und Netzwerk abgewiesen.

Das kompilierte Beispiel in [ShoppingMain](examples/shopping-service/src/main/java/dev/hoori/micro/demo/ShoppingMain.java)
zeigt drei Policies: Standardkomposition wartet auf alle Kinder; das Pflicht-
Overview wählt ausdrücklich `failFast()`; das Dashboard verwendet `settled()` und
markiert nur lokale Upstream-Ausfälle als `unavailable`. Erfolgreich leere Daten
bleiben `status: ok, data: []`. Globale Deadline, Admission und Cleanup scheitern weiter.

`POST /meals/batch` demonstriert `Tasks.map(...).maxConcurrency(2).toList()`;
`toList()` benötigt O(n) Ergebnisspeicher. Das globale Broker-Limit gilt zusätzlich.
Für diese fachlich passende Massenabfrage ist `POST /meals/bulk` der bessere normale
Weg: `ctx.call(Recipes.GET_MANY, new RecipeIds(ids))` nutzt einen einzigen RPC.
Beide akzeptieren höchstens 16 IDs und behalten Reihenfolge und Duplikate:

```bash
curl -fsS http://127.0.0.1:8080/meals/bulk -H 'Content-Type: application/json' \
  -d '{"ids":[2,1,2,1,2]}'
```

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
examples/recipes-service/  Rein lesender Recipe-Anbieter
examples/pantry-service/   Unabhängiger lesender Pantry-Anbieter
examples/shopping-service/ Direkter Call, typisiertes Overview, Dashboard, Batch/Bulk
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
Die Lizenzentscheidung für das Framework ist noch offen.
