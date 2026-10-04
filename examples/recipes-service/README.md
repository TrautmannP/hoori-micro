# Recipes

`RecipesApplication` startet den Graphen. `controller/RecipeController` bindet HTTP,
`service/RecipeService` besitzt die Fachoperationen, `repository/RecipeRepository`
ist ein begrenzter In-Memory-Demospeicher. `error/RecipeAdvice` übersetzt fehlende
Rezepte in einen öffentlichen Code. Die DTOs liegen im gemeinsamen Contract-Modul.

Nach dem Root-Build Registry und Recipes mit `scripts/run-local.sh` starten:

```bash
curl -fsS http://127.0.0.1:8081/recipes/1
curl -fsS 'http://127.0.0.1:8081/recipes?prefix=Kartoffel'
curl -i http://127.0.0.1:8081/recipes -H 'Content-Type: application/json' -d '{"title":"Möhrensuppe"}'
curl -i -X DELETE http://127.0.0.1:8081/recipes/3
```

GET/POST/DELETE sind mit `recipes:read`/`recipes:write` publiziert; Bulk und
Demo-Diagnostik bleiben interne Endpunkte. Keine dauerhafte Speicherung oder
produktive Authentifizierung.
