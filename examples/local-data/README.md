# Optionale lokale Daten-/Outbox-Transaktion

Das separat gebaute Beispiel verwendet die originalen Transaction-/JDBC-/Jdbi-SDKs,
pgJDBC **42.7.13** und Jdbi Core **3.55.0**. Der HTTP-Kern und seine normalen
Anwendungen laden keine Datenbank-JARs.

```text
DataApplication
  controller/DraftController       POST /drafts, DTO-Binding und Validation
  service/DraftService             Remote-Vorbereitung
  service/DraftWriter              kurze lokale @Transactional-Grenze
  service/LocalDraftWriter         Fachoperation innerhalb dieser Grenze
  repository/DraftRepository      SQL für Datensatz und Outbox
  client/RecipeClient,PantryClient HTTP-Verträge
  config/DatabaseConfiguration     ein stabiler Jdbi-Manager
  dto/Draft,SavedDraft             Eingabe und Antwort
```

Der generierte Anwendungsgraph injiziert Clients, Manager und den originalen
Transactional-Delegate. Remote-Reads laufen **vor** der lokalen Transaktion.
Beide `@Positive`-IDs werden vorher validiert. Daten und Outbox werden gemeinsam
committet, bevor JSON-Encoding und HTTP-Antwort beginnen.

```bash
python3 scripts/optional_example.py build local-data
python3 scripts/test_data.py --hoori-checkout ../hoori
HOORI_ENGINE=interpreter python3 scripts/test_data.py --hoori-checkout ../hoori
```

Die Probe verwendet ein eigenes wegwerfbares PostgreSQL **18.6** und die zur
gepinnten Runtime-Revision geprüften originalen Fault-Peers. Der App-Build selbst
braucht nur die Distribution. Schema/Container werden durch den Test verwaltet.

Für die manuelle Demo [schema.sql](schema.sql) in einer eigenen Demo-Datenbank
anlegen, Registry/Recipes/Pantry starten und die Verbindung konfigurieren:

```bash
export HOORI_DB_URL=jdbc:postgresql://127.0.0.1:5432/hoori_micro_demo
export HOORI_DB_USER=hoori_micro_demo
export HOORI_DB_PASSWORD='<lokales Demo-Passwort>'
export HOORI_DB_SSLMODE=disable # nur lokale Probe; Default: require
python3 scripts/optional_example.py run local-data
curl -fsS http://127.0.0.1:8084/drafts -H 'Content-Type: application/json' -d '{"id":1,"recipeId":1}'
```

Für Gatewayzugriff zusätzlich `drafts:write` in dessen Demo-Permissions aufnehmen.
Der öffentliche Vertrag liegt in `src/main/resources/openapi.json`; der normale
Build prüft ihn und verpackt das hashgebundene Artefakt wie bei den HTTP-Beispielen.
Das ist keine produktive Schreibautorisierung. Keine Dahemm-Datenmigration,
Outbox-Zustellung oder Exactly-once-Zusage.

Der Manager ist keine erfundene Pool-/Close-Abstraktion: die stabile Factory
liefert je Transaktion eine Verbindung, die der originale SDK besitzt und schließt.
Connect ist auf höchstens 5 s, Socket-I/O auf 10 s und Cancel-Signal auf 2 s begrenzt;
kürzere positive Restbudgets werden auf Sekunden aufgerundet. Das qualifizierte
Login-Limit liegt darüber bei 300 s. URL-Queryparameter dürfen diese Grenzen nicht
überschreiben. Acquisition ist nicht allgemein durch Scope-Cancellation abbrechbar;
nach Rückkehr prüft der SDK den Zustand. Mandatory Completion hat endliche
I/O-Grenzen, keine harte globale Abschlussfrist.

Gleichzeitige Requests besitzen getrennte Handles. Same-owner REQUIRED-Nesting
verwendet denselben Handle und erhält rollback-only. Kinder dürfen keinen Handle
übernehmen; eine ausdrücklich begonnene Child-Transaktion ist unabhängig.
Bestätigter Commit plus späterer Callback-/Encodingfehler bleibt `COMMITTED`;
verlorene Bestätigung bleibt `UNKNOWN` und verwirft die Verbindung ohne Replay.
Ein abgebrochenes Jdbi-Statement erhält seinen primären SQL-Fehler; der DB-freie
HTTP-Kern gibt dafür einen sicheren 500 zurück.

Die nativen Checks prüfen Tabelleninhalte, Child-Finally vor Completion,
Handle-Isolation, REQUIRED-Rollback, unabhängigen Child-Commit, verlorenem
COMMIT-ACK, drei echte Query-Cancel-/GC-/Recovery-Zyklen und SIGTERM. Zusätzlich
startet die unveränderte `DataApplication` mit ihrem erzeugten Graphen.
Die gesamte Upstream-Treiber-/TLS-Matrix wird dadurch nicht erneut qualifiziert.
