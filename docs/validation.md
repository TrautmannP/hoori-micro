# Validierung und offene Abnahme

Stand: **28. September 2026**. Baseline siehe `hoori.lock.json`.

## Tatsächlich ausgeführt

| Prüfung | Ergebnis | Aussagegrenze |
|---|---|---|
| `scripts/test-core.sh` mit OpenJDK 21.0.11 | **99 Assertions bestanden** | Namens-/Origin-/Pfadvalidierung, Konfiguration, Grenzen und unveränderliche Kopien; kein Netzwerk |
| Python `unittest` für `runtime_check.py` | **12 Tests bestanden** | Synthetische Distributionen: gültig, manipuliert, fehlend, zusätzliche Dateien, falsche Revision, Dirty-Zustand, Features, Symlinks und unsichere Manifestpfade |
| `bash -n`, `sh -n` | Bestanden | Syntax der Shell-Scripte; kein erfolgreicher VM-/Docker-Start daraus ableitbar |
| Python-Kompilation | Bestanden | Syntax der Python-Scripte |
| XML-Parser für fünf POMs | Bestanden | Wohlgeformtes XML; kein Maven-Lifecycle ausgeführt |
| YAML-Parser für Compose/CI | Bestanden | YAML-Syntax; keine semantische Docker-Compose-Abnahme |
| Zusätzlicher Java-21-Syntax-/Typcheck aller 14 Java-Dateien | Bestanden | Temporäre, aus gelesenen Quellsignaturen abgeleitete API-/JUnit-Fixtures; **kein Compile gegen die echten SDK-JARs**, keine Ausführung |

Die temporären API-/JUnit-Fixtures des letzten Checks wurden nicht in das Archiv
aufgenommen, sind kein Produktions-Fallback und zählen nicht als SDK-Kompatibilitäts-
nachweis. Die normalen Build-Scripte verwenden ausschließlich die echten SDK-JARs
aus einer geprüften Hoori-Distribution.

## Nicht ausgeführt

Die Arbeitsumgebung enthält kein Maven, keine ausführbare HooriVM-Distribution und
keine Docker Engine. Deshalb wurden **nicht** ausgeführt:

- vollständiges `mvn clean verify` gegen Hoori-SDKs einschließlich der sechs
  `ServiceClientTest`-Methoden und des JUnit-Wrappers der Core-Checks;
- Guest-Ausführung der Core-Checks auf HooriVM;
- Image-Build, Container-DNS, echte Service-Aufrufe, HTTPS oder Signal-/Drain-Integration;
- Überlast-/Dauertests, garantierte IP-Wechsel, Multi-Instanz-Lastverteilung oder Benchmarks.

Die Repository-Quellen wurden gelesen und die verwendeten Schnittstellen daran
angepasst. Das ist eine Quellprüfung, kein Ersatz für die native Abnahme.

## Reproduzierbare nächste Prüfungen

```bash
./scripts/build.sh /pfad/zur/passenden/headless/distribution
./scripts/test-hoori-core.sh
python3 scripts/smoke.py

HOORI_ENGINE=interpreter ./scripts/test-hoori-core.sh
HOORI_ENGINE=interpreter python3 scripts/smoke.py
```

`build.sh` führt Maven gegen den importierten echten SDK-Stand aus. Ein inkompatibler
oder anderer Hoori-Stand wird bereits vor dem Import abgewiesen. Der Dockerfile-
Build prüft die tatsächliche Runtime auf Ausführbarkeit mit der gewählten Basis.

Der Smoke-Test prüft zwei getrennte Prozesse/Container, typisiertes JSON, sichere
Fehler, Request-ID über einen Hop, keine versehentliche Authorization-Weitergabe,
Health/Metriken, Ausfall und Wiederanlauf, Container-Neuerstellung sowie graceful
SIGTERM mit laufendem ausgehenden Request. Er verwendet ein eigenes Compose-Projekt;
fehlende Voraussetzungen oder gescheiterte Checks liefern Fehler statt eines JVM-
Fallbacks. Standardport 18080 muss verfügbar sein, sonst `HOORI_DEMO_PORT` setzen.

Container-Neuerstellung ist kein Nachweis einer geänderten IP-Adresse. Auch der
Smoke-Test ist keine Produktions-, Sicherheits- oder Lastfreigabe. Zusätzliche
Abnahmepunkte stehen unter F1 in `roadmap.md`.

## Berichterstattung

Bei späterer Abnahme mindestens Runtime-Receipt, Java-/Maven-Version,
Container-Basis/Digest, Engine-Modus, Testausgaben und relevante Fehler festhalten.
Nicht ausgeführte Schritte ausdrücklich offen lassen. Die bestehende CI heißt
bewusst `Portable bootstrap checks` und meldet keine erfolgreiche native Integration.

Es gibt in diesem Bootstrap **keine gemessenen Performancegewinne**. Pool-Wiederverwendung,
begrenzte Konfiguration und der Verzicht auf zusätzliche Infrastruktur sind zunächst
Architekturentscheidungen; die Messplanung steht in `architecture.md`.
