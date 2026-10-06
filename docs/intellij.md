# IntelliJ: Beispiele auf Hoori starten

Das Root-`pom.xml` als Maven-Projekt öffnen. Die geteilten Konfigurationen unter
`.run/` erscheinen in **Run → Edit Configurations** im Ordner **Hoori**.
Sie verwenden das mitgelieferte
[Shell-scripts-Plugin](https://www.jetbrains.com/help/idea/run-debug-configuration-shell-script.html)
und zeigen die Ausgabe im Run-Fenster. `scripts/intellij.sh` ruft die vorhandenen
Build-/Testskripte auf; Anwendungen laufen über `bin/hoori` der gepinnten
Distribution mit Guest Base, SDKs und den benötigten Capabilities.

## Einmal einrichten

Benötigt werden die [Build-Werkzeuge und Distribution](../README.md#bauen-und-starten).
Lokale Pfade in `.idea/hoori.env` hinterlegen; die Datei wird nicht versioniert
und enthält Bash-Zuweisungen:

```bash
HOORI_JAVA21_HOME=/absoluter/pfad/zum/jdk-21.0.12.1
HOORI_DISTRIBUTION=/absoluter/pfad/zur/headless/release-distribution
# Nur für den Datenbanktest: Checkout mit den originalen gepinnten JDBC-Fixtures.
# HOORI_CHECKOUT=/absoluter/pfad/zum/hoori-checkout
```

Eine bereits über `build.sh` übernommene Distribution kann weiterverwendet werden:
ohne `HOORI_DISTRIBUTION` nimmt der Build `.docker-context/runtime`.
`HOORI_JAVA21_HOME` setzt das Build-JDK unabhängig vom IntelliJ-Projekt-SDK.
Maven und Python müssen im von IntelliJ geerbten `PATH` liegen.

## Bauen, ausführen und testen

Zuerst **Hoori - Build** ausführen, auch nach Änderungen an Quellen oder Verträgen.
Damit laufen Maven-Verify, Annotation-Processor und Staging mit dem geprüften
Distributionscache. Startkonfigurationen führen keinen weiteren Build aus;
gleichzeitig laufende Services benötigen dadurch keine konkurrierenden Clean-Builds.

| Konfiguration | Verwendung |
|---|---|
| `Hoori - Demo` | Registry, Recipes, Pantry, Shopping und Gateway gemeinsam starten; Gateway unter `http://127.0.0.1:8080`, API-Referenz unter `/_hoori/docs` |
| `Hoori - registry/recipes/pantry/shopping/gateway` | Einzelne Rolle starten; Ports 8090/8081/8083/8082/8080 |
| `Hoori - mvc-crud` | Kleines CRUD-Beispiel auf Port 8085, z. B. `GET /recipes/1` |
| `Hoori - Build task-facade`, danach `Hoori - task-facade` | Optionales Beispiel separat bauen und auf Port 8084 starten; Registry, Recipes und Pantry vorher starten |
| `Hoori - Build local-data`, danach `Hoori - local-data` | Datenbeispiel separat bauen und auf Port 8084 starten; Registry, Recipes, Pantry und eigene [Demo-Datenbank](../examples/local-data/README.md) vorbereiten |
| `Hoori - Test Core/HTTP/MVC (GC stress)` | Vorhandene native Core-, HTTP- und CRUD-/Bootstrap-Prüfungen |
| `Hoori - Test task-facade/local-data` | Nach dem jeweiligen optionalen Build; Datentest benötigt zusätzlich Docker und den gepinnten Fixture-Checkout |

Die Demo wird parallel gestartet; Discovery und die Gateway-Dokumentation brauchen
kurz, bis die Anbieter bekannt sind. Die HTTP-Tests starten ihre eigenen Prozesse
auf freien Ports und räumen diese wieder auf.

Im jeweiligen Run-Dialog unter **Environment variables** lassen sich
`HOORI_ENGINE=interpreter` (Default: `mixed`), `HOORI_PORT` und weitere
[Service-Einstellungen](configuration.md) setzen. DB-Zugangsdaten lokal hinterlegen,
keine Passwörter in geteilte `.run`-Dateien eintragen. Task-Fassade und Local Data
belegen standardmäßig denselben Port. CRUD/Recipes sowie Task-Fassade/Shopping
haben kollidierende Gateway-Routen und gehören in getrennte Gateway-Demos.

Die Stop-Schaltfläche erreicht dank `exec` direkt die HooriVM; deren Signalbehandlung
führt den normalen Shutdown aus. Die Konfigurationen dienen dem Ausführen und
Testen, Java-Breakpoints benötigen eine gesonderte Debugger-Anbindung an Hoori.
