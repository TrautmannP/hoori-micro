# Kleines MVC-/OpenAPI-Beispiel

`CrudApplication` konstruiert Controller, Fachservice und In-Memory-Repository
über den normalen generierten App-Graphen. `src/main/resources/openapi.json` ist
die Vertragsquelle für GET/Liste, POST mit 201/Location und DELETE mit 204.
Die öffentlichen Methoden tragen `@GatewayRoute`; der Build gleicht Routen,
DTOs und Eingabeconstraints ab und verpackt das kanonische Vertragsartefakt.

`scripts/build.sh <Distribution>` im Repository baut das Beispiel mit.
`python3 scripts/test_mvc.py --gc-stress` startet es auf der gepinnten HooriVM
und prüft die normalen HTTP-/Validation-Grenzen. `test_application_build.py`
baut es zusätzlich außerhalb des Reactors und startet es ohne Quellen.

Für eine eigene Gateway-Demo `crud:read,crud:write` freigeben. Das Beispiel nutzt
dieselben `/recipes`-Pfade wie `recipes-service`: diese beiden Varianten nicht
im selben Gateway veröffentlichen. Das Gateway hält solche Konflikte zurück.
Kein persistenter Speicher und keine produktive Autorisierung.
