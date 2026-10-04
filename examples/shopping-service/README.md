# Shopping

`ShoppingApplication` startet `controller/ShoppingController`,
`service/ShoppingService` und die injizierten `client/RecipeClient`/`PantryClient`.
Controller binden Eingaben; der Service entscheidet über parallele Reads,
Fehlerpolicy und Batch/Bulk. DTOs liegen im gemeinsamen Contract-Modul.

Nach dem Root-Build alle fünf Rollen mit `scripts/run-local.sh` starten:

```bash
curl -fsS http://127.0.0.1:8080/meals/1
curl -fsS http://127.0.0.1:8080/overview/1
curl -fsS http://127.0.0.1:8080/dashboard/2
curl -fsS http://127.0.0.1:8080/meals/bulk -H 'Content-Type: application/json' -d '{"ids":[2,1,2]}'
```

Overview verlangt beide Reads. Dashboard unterscheidet einen optionalen Ausfall
von erfolgreichen leeren Daten; globale Deadline/Admission/Cleanup bleiben Fehler.
Batch verarbeitet höchstens 16 IDs mit höchstens zwei gleichzeitig gestarteten
Reads. Bulk nutzt dafür einen einzelnen HTTP-Aufruf. Reihenfolge und Duplikate
bleiben erhalten. Diese Fachkomposition benötigt keine DB und ist keine
migrierte Einkaufslogik von Dahemm.
