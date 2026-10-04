# Hoori Micro

Annotationsbasierte HTTP-Anwendungen auf **HooriVM**: Controller → Fachservice →
Repository oder typisierter Service-Client. Hooris originale HTTP-/MVC-SDKs
übernehmen Transport, Binding, JSON und Validation. Micro ergänzt Bootstrap,
Discovery, Gateway und verwaltete Request-Grenzen.

**Experimentell.** Die lokale funktionale Abnahme steht in
[docs/validation.md](docs/validation.md). Die Beispiele haben keine produktive
Authentifizierung oder Dahemm-Datenhaltung.

## Eine Anwendung

Der Maven-Parent `dev.hoori:hoori-micro-starter:0.1.0-SNAPSHOT` konfiguriert den
Build. Die normalen Quellen enthalten Application, Controller und Fachklassen:

```java
@MicroApplication(name = "recipes", openApi = "openapi.json")
public final class RecipesApplication {
    public static void main(String[] args) throws Exception {
        Micro.run(RecipesApplication.class, args);
    }
}

@RestController
@RequestMapping("/recipes")
public final class RecipeController {
    private final RecipeService recipes;

    public RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    @GetMapping("/{id}")
    @GatewayRoute(permission = "recipes:read")
    public Recipe get(@PathVariable("id") @Positive long id) {
        return recipes.get(id);
    }

    @PostMapping
    @GatewayRoute(permission = "recipes:write")
    public HttpResult<Recipe> create(@RequestBody @Valid CreateRecipe input) {
        Recipe recipe = recipes.create(input);
        return HttpResult.of(201, recipe).header("Location", "/recipes/" + recipe.id());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(204)
    @GatewayRoute(permission = "recipes:write")
    public void delete(@PathVariable("id") @Positive long id) {
        recipes.delete(id);
    }
}

public record CreateRecipe(@NotBlank @Size(max = 200) String title) {}
public record Recipe(long id, String title) {}
```

Webannotation und `HttpResult` kommen aus `hoori.rest.mvc`, App-Annotationen aus
`hoori.micro.app`, Constraints und `@Valid` aus `jakarta.validation`.
`@Service RecipeService` erhält `@Repository RecipeRepository` per Konstruktor.
DTOs benötigen keine Codec-Annotation, Registrierung oder Validator-Factory.
Ungültige Eingaben scheitern vor der Fachmethode; ein Beispiel für `/recipes/0`:

```json
{"code":"validation_failed","violations":[{"path":"path.id","code":"positive"}]}
```

Der vollständige [Recipes-Service](examples/recipes-service/src/main/java/dev/hoori/micro/recipes)
zeigt zusätzlich Listen, Queryparameter und `@RestControllerAdvice` für einen
stabilen öffentlichen Fachfehler. Sein Speicher ist ausdrücklich nur im Prozess.

## Andere Services aufrufen

```java
@ServiceClient(name = "recipes", version = 1)
public interface RecipeClient {
    @GetMapping("/recipes/{id}")
    Recipe get(@PathVariable("id") long id);
}

@Service
public final class ShoppingService {
    private final RecipeClient recipes;

    public ShoppingService(RecipeClient recipes) {
        this.recipes = recipes;
    }

    public Recipe meal(long id) {
        return recipes.get(id);
    }
}
```

Der erzeugte Client nutzt den gemeinsamen Datenpool und den aktuellen
Request-Kontext. Er kennt Service, Version und HTTP-Vertrag; Adressen liefert der
lokale Discovery-Snapshot. Synchrone Aufrufe erzeugen keinen zusätzlichen Task.
Echte parallele Komposition verwendet die originalen `Tasks` oder die optionale
[Task-Fassade](examples/task-facade/README.md).

```text
Registry ← Registrierung / Lease / Katalog → Services und Gateway
Browser → Gateway → ShoppingController → ShoppingService → RecipeClient → Recipes
                                                      └→ PantryClient → Pantry
```

Die Registry liegt außerhalb des Request-Pfads. Das Gateway veröffentlicht nur
`@GatewayRoute`-Methoden und prüft deren Permission gegen seine Policy.
Neue Anbieter oder zusätzliche Endpunkte brauchen keinen Neubau bestehender
Clients. Inkompatible Änderungen eines verwendeten Vertrags benötigen eine neue
Version bzw. eine bewusste Consumer-Anpassung.

## OpenAPI

`@MicroApplication(openApi = "openapi.json")` bindet eine Vertragsdatei aus
`src/main/resources` an die öffentlichen Controller. Der Build prüft Routen,
DTOs, Parameter, Status und Constraints; eine optionale Release-Baseline verhindert
Änderungen bestehender Verträge innerhalb derselben Service-Major-Version.

Die Demo veröffentlicht unter `http://127.0.0.1:8080/openapi.json` den aktuellen
Gateway-Vertrag und unter `http://127.0.0.1:8080/_hoori/docs` eine lokale API-Referenz.
Dokumente stammen aus demselben Routing-Snapshot; ihre Publikations-ID macht
Wechsel sichtbar. Profil, Grenzen und weitere Ausbauschritte stehen in
[docs/openapi.md](docs/openapi.md).

## Bauen und starten

Benötigt: Maven 3.9.x, Python 3.10+, Docker/Compose für Containerchecks und ein
vollständiges JDK 21. Qualifiziert wurde **21.0.12.1**; alte JDK-21-Builds können
TYPE_USE-Metadaten aus Contract-JARs verlieren. Der Starter setzt den benötigten
Compiler-Schalter. Zur Laufzeit wird kein Host-JDK verwendet.

`hoori.lock.json` bindet Runtime, Guest Base und SDKs an den sauberen Hoori-Commit
`83d2b8fc83ffee6ed7c748409ff7b4802d8a341b`. Eine passende Headless-Distribution
kann übernommen oder im Hoori-Checkout separat gebaut werden:

```bash
# Im Hoori-Checkout; einen freien Worktree-Pfad wählen:
git worktree add --detach ../hoori-micro-runtime 83d2b8fc83ffee6ed7c748409ff7b4802d8a341b
cd ../hoori-micro-runtime
export JAVA_HOME=/pfad/zum/jdk-21.0.12.1
export HOORI_JAVA21_HOME="$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/build-distribution.sh --release
```

Dann in diesem Repository, mit demselben Build-JDK:

```bash
./scripts/build.sh /absoluter/pfad/zur/headless/release-distribution
docker compose up --build --wait
curl -fsS http://127.0.0.1:8080/meals/1
curl -fsS http://127.0.0.1:8080/overview/1
curl -i http://127.0.0.1:8080/recipes -H 'Content-Type: application/json' -d '{"title":"Möhrensuppe"}'
docker compose down
```

`build.sh` prüft Receipt, Revision, Prüfsummen und Original-POMs, baut in einem nach
Distribution getrennten Maven-Cache und erzeugt `.docker-context/`. Der Container
enthält die überprüfte Distribution und Runtime-Abhängigkeiten. Compiler,
Processor und DB-JARs stehen außerhalb des HTTP-Laufzeitklassenpfads.

Ohne Docker je ein Terminal öffnen: `scripts/run-local.sh registry`, `recipes`,
`pantry`, `shopping`, `gateway`. Die Ports sind 8090, 8081, 8083, 8082 und 8080.
Der langsame Demo-Endpunkt benötigt `HOORI_CLIENT_TIMEOUT_MS` über drei Sekunden.

Für eine eigene Maven-Anwendung zuerst die geprüften Micro-Artefakte installieren
(`mvn -Dmaven.repo.local=<Cache aus build.sh> -pl processor,starter -am install`),
dann den Starter als Parent mit `<relativePath/>` verwenden. Die eigene
Projektversion ist unabhängig von der Micro-Version. Ein separat veröffentlichter
Artefaktspeicher ist hier noch nicht eingerichtet.

## Beispiele und Prüfungen

| Beispiel | Aufbau |
|---|---|
| [mvc-crud](examples/mvc-crud) | Kleiner eigenständiger CRUD-Einstieg |
| [recipes-service](examples/recipes-service) | Controller, Fachservice, In-Memory-Repository, Advice |
| [pantry-service](examples/pantry-service) | Kleiner lesender Provider |
| [shopping-service](examples/shopping-service) | Injizierte Clients, Overview/Dashboard, begrenztes Batch/Bulk |
| [demo-contracts](examples/demo-contracts) | Gemeinsame DTOs, keine Framework-Verkabelung |
| [task-facade](examples/task-facade/README.md) | Erzeugte Task-Fassaden und lokale Methodengrenze |
| [local-data](examples/local-data/README.md) | Remote-Vorbereitung, kurze Jdbi-Transaktion und atomare Outbox |

Die [Validierung](docs/validation.md) nennt die reproduzierbaren Gates und deren
Evidenz. [Architektur](docs/architecture.md), [Konfiguration](docs/configuration.md),
[Security-Grenzen](docs/security.md) und [Roadmap](docs/roadmap.md) beschreiben den
zugesagten Umfang. Keine vollständige Spring-/Jakarta-/JDK-Kompatibilität.
