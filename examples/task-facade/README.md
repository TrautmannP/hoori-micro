# Optionale Task-Fassaden

Dieses Beispiel wird separat vom HTTP-Reaktor gebaut. Es verwendet die Original-
POMs und den `hoori-task-processor` derselben geprüften Distribution:

```bash
./scripts/build.sh /pfad/zur/gepinnten/distribution
python3 scripts/optional_example.py build task-facade
# Registry, Recipes und Pantry über run-local.sh starten, dann:
python3 scripts/optional_example.py run task-facade
curl -fsS http://127.0.0.1:8084/overview -H 'Content-Type: application/json' -d '{"id":1}'
```

`RecipesClientTasks` und `PantryClientTasks` entstehen über den expliziten Maven-
Processor-Pfad. Ihre normalen `TaskSpec`s starten erst bei Ausführung und binden
erst dort den aktuellen Micro-Kontext. Die handgeschriebenen Delegates rufen
`app.context().call(...)` auf; Discovery bleibt im vorhandenen Broker.

`new OverviewServiceScoped(delegate)` setzt die erklärte 800-ms-Methodengrenze.
Ein kürzeres Request-/Parent-Budget bleibt die Obergrenze. Rückkehr wartet auf
lokalen Kind-/Ressourcenabschluss; ein Remote-Provider kann unabhängig weiterlaufen.
Direkte Aufrufe und Selbstaufrufe des ursprünglichen Delegates bleiben gewöhnliche
Java-Aufrufe. Es gibt keine automatische Interception oder Instanzsuche.

Erzeugte Klassen stehen in `target/generated-sources/annotations` und im App-JAR.
Annotationen/Processor bleiben auf dem Build-/Compilepfad. Zum Ausführen werden
nur das App-JAR, Micro, Verträge und die vier SDKs samt Guest Base geladen.
`HOORI_ENGINE=interpreter` wählt die andere Engine; die Port-/Registry-Variablen
aus der allgemeinen Konfiguration gelten ebenfalls.

Der kürzere Weg ohne Codegen bleibt `ctx.task(Recipes.GET, query)` in Shopping.
Die Fassade lohnt sich für bestehende synchrone Client-Interfaces; sie spart dort
handgeschriebene Task-Wrapper, fügt aber einen bewussten Buildschritt hinzu.
Lokale generierte Transaktionsgrenzen gehören zum separaten Datenbankbeispiel (#27).

Nach dem Build prüft `python3 scripts/test_composition.py --facade` echte
Discovery-Calls, verzögerte/wiederholte Ausführung, Request-Kontext, Checked Exceptions,
lokale/Parent-Budgets und Recovery. Den Befehl auch mit
`HOORI_ENGINE=interpreter` ausführen. Die generische Processor-Matrix bleibt upstream.
