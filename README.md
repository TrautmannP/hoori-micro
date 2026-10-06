# Hoori Micro

Annotation-based HTTP microservices on **HooriVM**: controller → service → repository or typed
service client. The original Hoori HTTP/MVC SDKs handle transport, binding, JSON and validation;
Micro adds a build-time application graph, discovery, a gateway and managed request boundaries.

**Experimental.** The examples have no production authentication or data storage.

```java
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
}
```

## Documentation

The manual (guide, reference and examples) is at **<https://micro.hoori.dev>**. Good starting
points are the [quick start](https://micro.hoori.dev/docs/guide/quick-start) and the
[tutorial](https://micro.hoori.dev/docs/guide/tutorial).

Its sources live in [`pages/`](pages):

```bash
cd pages && npm install && npm run dev   # http://localhost:3000
```

## Build and run

Requires a full JDK **21.0.12.1**, Maven 3.9.x, Python 3.10+, Docker/Compose and a headless Hoori
distribution matching [`hoori.lock.json`](hoori.lock.json) (see the quick start).

```bash
./scripts/build.sh /absolute/path/to/headless/release-distribution
docker compose up --build --wait
curl -fsS http://127.0.0.1:8080/meals/1
docker compose down
```

Without Docker, start `scripts/run-local.sh registry|recipes|pantry|shopping|gateway` in separate
terminals. IntelliJ users: the shared `.run/` configurations are described on the
[IntelliJ page](https://micro.hoori.dev/docs/guide/ide).

## Contributing

[`AGENTS.md`](AGENTS.md) contains the working rules. [`docs/`](docs) is internal: architecture
invariants, [validation evidence](docs/validation.md), benchmarks, the source baseline and the
roadmap. User-facing documentation belongs in the manual.
