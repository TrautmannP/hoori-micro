# Pantry

`PantryApplication` startet `controller/PantryController` und
`service/PantryService`. Der kleine feste Demospeicher benötigt keine zusätzliche
Repository-Schicht. Registry und Pantry nach dem Root-Build lokal starten:

```bash
curl -fsS http://127.0.0.1:8083/pantry/1
curl -fsS http://127.0.0.1:8083/pantry/2 # erfolgreiche leere Liste
curl -i http://127.0.0.1:8083/pantry/0  # Validation vor dem Fachservice
```

`pantry:read` erlaubt die ausdrückliche Gateway-Publikation. Das ist weiterhin eine
unauthentifizierte Demo mit Daten im Prozess.
