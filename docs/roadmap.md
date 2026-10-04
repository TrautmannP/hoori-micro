# Roadmap

Dieses Consumer-Repository bleibt unabhängig von Hooris abgeschlossener
MS0–MS5-Runtime-Roadmap. Die Beispiele sind keine migrierten Dahemm-Fachdienste.

## MVC-Grundlage (#35–#42)

Das Standardmodell ist Application → Controller → Fachservice → Repository/Client.
Bootstrap, MVC-Binding, typisierte Clients, HTTP-Endpunktkatalog und Gateway nutzen
die originalen SDKs. Optionale Task-/Datenbeispiele folgen derselben Struktur.
Die konkrete lokale Abnahme und ihre Grenzen stehen in [validation.md](validation.md).

## Offene Betriebsarbeit

- [#8](https://github.com/TrautmannP/hoori-micro/issues/8): minimales Runtime-Image
  und qualifizierter Upgrade-Prozess.
- [#10](https://github.com/TrautmannP/hoori-micro/issues/10): eigene Last-/Rolling-
  und Ressourcenabnahme; funktionale Smoke-Checks ersetzen sie nicht.
- [#11](https://github.com/TrautmannP/hoori-micro/issues/11): wiederholte Release-
  Baseline gegen nacktes REST. Alte Messwerte qualifizieren die MVC-API nicht.
- Produktionsidentität, Registry-Autorisierung, TLS-/Capability-Negativfälle und
  eine autorisierte CI-Pipeline benötigen eigene Umsetzung und Nachweise.

## Vor einer Dahemm-Migration

Aktuelle App-API, Fachgrenzen, Datenhoheit, Haushaltsberechtigungen und
Transaktionsanforderungen separat aufnehmen. Erst daraus einen kleinen
rückschaltbaren Use-Case wählen. Die HTTP-Demo hat einen In-Memory-Speicher; das
Datenbeispiel besitzt nur sein eigenes Schema und demonstriert keine Datenmigration.

Persistente Outbox-Zustellung, idempotente Consumer, hochverfügbare Registry,
Circuit Breaker oder Tracing erst bei einem konkreten Bedarf entwerfen.
Keine verteilte ACID-/Exactly-once-Zusage aus lokalen Transaktionschecks ableiten.
