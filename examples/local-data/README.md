# Optionale lokale Daten-/Outbox-Transaktion

`local-data` ist ein separater Consumer von Transaction, JDBC und Jdbi aus der
gepinnten Hoori-Distribution, mit originalem pgJDBC **42.7.13** und Jdbi Core
**3.55.0**. Der normale HTTP-Build, Registry und Gateway laden keine DB-JARs.

```bash
./scripts/build.sh /pfad/zur/gepinnten/distribution
python3 scripts/optional_example.py build local-data
# Vollständige lokale Probe: wegwerfbares PostgreSQL 18.6, native Micro-Requests.
python3 scripts/test_data.py --hoori-checkout ../hoori
HOORI_ENGINE=interpreter python3 scripts/test_data.py --hoori-checkout ../hoori
```

Die Probe verwendet die unveränderten, zur Runtime-Revision geprüften Datenbank-
und Fault-Peer-Helfer des Hoori-Checkouts. Der App-Build braucht nur die Distribution.
Sie erzeugt ein eigenes kurzlebiges Testschema und entfernt ihren Container wieder.

## Lokal verwenden

In einer **eigenen Demo-Datenbank** [schema.sql](schema.sql) mit `psql` ausführen.
Registry, Recipes und Pantry wie im Root-README lokal starten. Danach:

```bash
export HOORI_DB_URL=jdbc:postgresql://127.0.0.1:5432/hoori_micro_demo
export HOORI_DB_USER=hoori_micro_demo
export HOORI_DB_PASSWORD='<lokales Demo-Passwort>'
export HOORI_DB_SSLMODE=disable # nur für diese lokale Probe; Default ist require
python3 scripts/optional_example.py run local-data # Port 8084

# In einem weiteren Terminal das lokale Demo-Gateway starten:
HOORI_GATEWAY_PERMISSIONS=recipes:read,pantry:read,shopping:read,drafts:write ./scripts/run-local.sh gateway
curl -fsS http://127.0.0.1:8080/drafts -H 'Content-Type: application/json' -d '{"id":1,"recipeId":1}'
curl -fsS http://127.0.0.1:8080/drafts/scoped -H 'Content-Type: application/json' -d '{"id":2,"recipeId":2}'
```

Die Registry vermittelt auch diesen Dienst dynamisch. Die Gateway-Permission ist
eine unauthentifizierte Demo-Freigabe, keine Schreibautorisierung für Produktion.
Keine Dahemm-Daten, Migration, Outbox-Zustellung oder Exactly-once-Zusage.

## Grenze und Besitz

[DataMain](src/main/java/dev/hoori/micro/data/DataMain.java) erstellt **einen stabilen
Manager** beim Start. Erst laufen zwei unabhängige Remote-Leseaufrufe. Danach folgt
eine kurze lokale `TaskScope.named(...).with(Transactions.required(manager))`-
Operation. SQL-Constraints, Datensatz und Outbox stehen in derselben Transaktion.
Der zweite Endpunkt verdrahtet `new StoreScoped(repository, manager)` ausdrücklich;
dieser generierte REQUIRED-Delegate hat dieselbe lokale Grenze. Direkte Aufrufe
des ursprünglichen Repositories benötigen weiterhin eine solche Grenze.

Gleichzeitige Requests erhalten getrennte Handles. Same-owner REQUIRED-Nesting
verwendet denselben Handle; ein gefangener innerer Fehler bleibt rollback-only.
Kinder dürfen keinen geerbten/gespeicherten Handle verwenden. Eine ausdrücklich
begonnene Child-Transaktion ist unabhängig: ihr Commit überlebt Parent-Rollback.
Jdbi `inTransaction`, SQL-Object-Transaktionsannotationen und Remote-Calls ersetzen
diese Grenze nicht.

Es gibt hier keinen Connection-Pool. Jede Transaktion besitzt ihre Verbindung bis
zum echten Kind-/Treiberabschluss und schließt sie anschließend. Ein später
eingeführter Pool muss gesundes `release` von physischem `discard` unterscheiden
und als Service-Ressource nach Request-Drain schließen. Keine Pools pro Request.

Die Factory begrenzt Connect auf höchstens 5 s, Socket-I/O auf 10 s und kürzere
positive Restbudgets auf aufgerundete Sekunden; Cancel-Signal auf 2 s. Das Login-
Limit von 300 s aus dem qualifizierten Upstream-Profil liegt über den endlichen
Socket-Waits. Acquisition ist nicht automatisch durch Scope-Cancellation abbrechbar;
nach Rückkehr prüft der SDK den aktuellen Zustand und verwirft ungeeignete Verbindungen.
URL-Queryparameter sind gesperrt, damit sie die Bounds nicht überschreiben.
Mandatory Completion hat weiter höchstens 10 s pro I/O, keine harte Gesamtfrist.
Der optionale Launcher benötigt File Read für die Host-Zeitzone/Driver-Konfiguration;
Classpath-Ressourcen, sichere Zufallswerte und Host-Auflösung sind ebenfalls explizit.

## Fehler und Nachweis

Der physische Commit liegt **vor** JSON-Encoding und HTTP-Antwort. Ein späterer
Fehler kann ihn nicht rückgängig machen. Bestätigt plus Callback-Fehler bleibt
`COMMITTED`; verlorene Bestätigung ist `UNKNOWN` und verwirft die Verbindung ohne
Retry. Der Datenbank-freie Micro-Kern erhält diese SDK-Zustände in seinen sicheren
Fehlerlogs. Ein abgebrochenes Jdbi-Statement behält seinen primären SQL-Fehler und
liefert deshalb 500 mit internem `ROLLED_BACK`; SQLSTATE 57014 wird nur im Test geprüft.

Die native Probe kontrolliert tatsächliche Tabelleninhalte, Child-Finally vor
Completion, lokale/generated Grenzen, getrennte Handles, drei echte Query-Cancel-
und GC-/Idle-Zyklen, UNKNOWN per verlorenem PostgreSQL-COMMIT-ACK und SIGTERM.
Die umfassende Upstream-Driver-/TLS-/GC-Matrix wird nicht erneut ausgeführt.
