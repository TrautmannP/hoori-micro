# Optionale Task-Fassade

`FacadeApplication` nutzt denselben Starter und Bootstrap wie die HTTP-Beispiele:
`controller/OverviewController` bindet den Request, `service/OverviewComposition`
komponiert zwei Reads, `client/RecipeClient` und `PantryClient` beschreiben HTTP.
Das injizierte `OverviewService`-Interface setzt mit `@TaskScoped` eine
800-ms-Methodengrenze. Ein kürzeres Parent-Budget bleibt maßgeblich.

```bash
python3 scripts/optional_example.py build task-facade
# Registry, Recipes und Pantry über scripts/run-local.sh starten, dann:
python3 scripts/optional_example.py run task-facade
curl -fsS http://127.0.0.1:8084/overview/1
```

`@GenerateTasks` an den Clients erzeugt originale Hoori-Task-Fassaden. Der
Anwendungsgraph konstruiert und injiziert sie sowie den Scoped-Delegate;
Anwendungscode verdrahtet keine erzeugten Klassen. Ein TaskSpec bleibt bis zur
Ausführung lazy und verwendet dann den aktuellen Kontext. Der Controller prüft
`@Positive` vor dem Fachservice; `/overview/0` liefert einen begrenzten Feldfehler.

Dekoration gilt für das injizierte öffentliche Interface. Direkte Aufrufe der
konkreten Implementierung und Selbstaufrufe bleiben normale Java-Aufrufe.
Es gibt keinen allgemeinen AOP-Container. Rückkehr wartet auf lokalen
Child-/Ressourcenabschluss; ein Remote-Provider kann unabhängig weiterlaufen.

Dieses Modul wird separat gebaut. Die Task-Processor bleiben auf dem Buildpfad,
DB-Abhängigkeiten sind nicht enthalten. `test_composition.py --facade` prüft beide
normalen App-Einstiege und zusätzliche kontrollierte Fälle für Lazy-Ausführung,
Kontext, überlappende Reads, Checked Exceptions, Deadline, Recovery und Shutdown.
Den Check auch mit `HOORI_ENGINE=interpreter` ausführen.

Für einzelne parallele Aufrufe kann normale Fachlogik stattdessen
`Tasks.task(() -> client.get(id))` verwenden, wie im Shopping-Service.
