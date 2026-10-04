# OpenAPI

Eine **OpenAPI-3.1-Datei pro Service ist die Vertragsquelle**. Der erste Schnitt
verwendet JSON und das begrenzte HTTP-/DTO-Profil der originalen Hoori-MVC-SDKs.
Der Build prüft den Vertrag; er erzeugt keinen zweiten Vertrag aus Reflection.

## Anwendung und Build

```java
@MicroApplication(name = "recipes", version = 1,
    openApi = "openapi.json", openApiBaseline = "openapi-v1-baseline.json")
public final class RecipesApplication { /* normaler Micro.run-Einstieg */ }
```

Die Dateien liegen unter `src/main/resources`. Die Baseline ist optional und
enthält den vorher freigegebenen Vertrag. Sie wird nicht automatisch überschrieben.
Alternativ nimmt der Processor `-Ahoori.openapi.baseline=/absoluter/pfad.json`
für den Build eines einzelnen Service-Moduls an; dieser Pfad hat Vorrang.

Der [Recipes-Vertrag](../examples/recipes-service/src/main/resources/openapi.json)
zeigt Pfad-/Queryparameter, DTOs, 201/Location, 204 und Fehlerantworten. Wichtige
Erweiterungen sind `x-hoori-service-version`, `x-hoori-api-group` (Default `public`)
und `x-hoori-permission` je Operation. Jede Operation hat eine eindeutige
`operationId` und genau eine passende `@GatewayRoute`-Methode. Interne Methoden
stehen nicht im öffentlichen Vertrag.

Die Prüfung vergleicht Methode/Pfad, Permission, Parameterpflichten und Defaults,
Body, DTO-Typen/Feldnamen, erforderliche Eingabefelder, Listenlimits und aktivierte
Jakarta-Eingabeconstraints. Statische Erfolgsstatus müssen übereinstimmen.
Erfolgsstatus/Header bei dynamischem `HttpResult`, Fachfehler und fachliche
Antwortinvarianten werden ausdrücklich im Vertrag beschrieben und durch
Anwendungstests belegt; der Processor beweist keine beliebigen Java-Codepfade.

Das JAR enthält die kanonische Datei unter
`META-INF/hoori-micro/<Application-FQN>/openapi.json`. Sortierte Objektschlüssel
machen ihre Bytes reproduzierbar; Zahlentoken bleiben ohne `double`-Rundung erhalten.
Ein SHA-256-Hash bindet Datei, erzeugten Bootstrap und Discovery-Metadaten.
Zur Laufzeit liefert der Service ausschließlich den passenden festen Pfad
`GET /_hoori/openapi/<hash>` mit ETag und immutable Cache-Control aus.

## Unterstütztes Profil

- OpenAPI 3.1.0–3.1.2 in JSON; endliche lokale Komponentenreferenzen ohne Zyklen.
- Pfadparameter und einzelne oder wiederholte Queryparameter; JSON-Request-/Response-
  Bodies, Records, skalare Werte, Enums, Listen und ausdrücklich nullable Typen.
- JSON-Fehler und begrenzte `text/plain`-Transportfehler. Erfolgsantworten bleiben
  JSON oder leer. Keine freie Header-/Medientyp-Weiterleitung durch das Gateway.
- `@NotBlank` wird als `x-hoori-not-blank` beschrieben. `@Size` auf Strings verwendet
  `x-hoori-min-utf16-length` / `x-hoori-max-utf16-length`: Java zählt UTF-16-Codeunits,
  während JSON Schema bei `minLength`/`maxLength` Zeichen zählt. Listen verwenden
  `minItems`/`maxItems`; Zahlengrenzen verwenden die passenden Schema-Keywords.

Unbekannte Standardfelder und nicht unterstützte Profile scheitern ausdrücklich.
YAML, externe/rekursive Referenzen, Schema-Komposition (`oneOf`/`allOf`), Multipart,
Streaming, Custom-Codecs und nicht angebundene Security-Schemes sind noch nicht
unterstützt. Es gibt keine allgemeine JSON-Schema-Validierung jeder Fachantwort.
Siehe [OpenAPI 3.1.2](https://spec.openapis.org/oas/v3.1.2.html) und
[JSON-Schema-Validierung](https://json-schema.org/draft/2020-12/json-schema-validation).

Die Baseline-Prüfung ist konservativ: neue Operationen sind innerhalb derselben
Service-Major erlaubt; bestehende Operation-IDs und expandierte Verträge bleiben
identisch. Änderungen an Parametern, Schemas, Antworten oder Permission benötigen
einen Major-Wechsel. Auch Änderungen verschachtelter Beschreibungen können diesen
strengen Vergleich auslösen. Eine allgemeine Subtyp-/Kompatibilitätsanalyse wird
nicht behauptet. `summary` auf Operationsebene ändert den Operationshash nicht.

## Gateway und Dokumentation

Registry-Protokoll **4** überträgt Hashes und API-Gruppe, keine Schemas. Alle
Teilnehmer müssen dieses Protokoll sprechen. Das Gateway berücksichtigt auch den
Operationshash bei Konflikten und Instanzauswahl. Ein alter und neuer Provider
können unveränderte Operationen gemeinsam bedienen; ein zusätzliches Mapping wird
nur bei Instanzen ausgewählt, die es anbieten. Widersprüchliche Verträge halten
beide Routenvarianten zurück.

`GET /_hoori/publication` liefert das Manifest aus demselben unveränderlichen
`ServiceBroker.View` wie das Routing: Katalogepoche/-revision, `publicationId`,
ausgewählte Vertrags-/Operationshashes, undokumentierte und zurückgehaltene Routen.
`complete` heißt nur, dass jede akzeptierte Route einen Vertrag hat.
`registryComplete` bezeichnet die Registry-Wiederanlaufphase. **Keines von beiden
beweist, dass alle gewünschten Deployment-Services laufen.**

`OpenApi.mount(app)` ergänzt einen optionalen Dokumentationsprozess im Gateway.
`Gateway.main` aktiviert ihn mit `HOORI_OPENAPI_ENABLED=true`; Compose und der
lokale Demo-Launcher setzen dies bereits. Ein eigener stabiler HTTP-Pool lädt
Verträge bei einer passenden Instanz über deren exakten Hashpfad. Die Arbeit liegt
außerhalb von Fachrequests und Registry-Heartbeat. Referenzen werden vor dem
Zusammenführen aufgelöst; doppelte Operation-IDs und Hash-/Routenabweichungen werden
abgewiesen. Nach erneutem Snapshot-Abgleich werden alle API-Gruppen gemeinsam
veröffentlicht.

| Adresse am Gateway | Inhalt |
|---|---|
| `/openapi.json` | Aktueller Vertrag der Gruppe `public` |
| `/_hoori/openapi` | Gruppenindex und Publikations-ID |
| `/_hoori/openapi/groups/<group>` | Aktueller Vertrag einer Gruppe |
| `/_hoori/docs` | Lokale, filterbare API-Referenz mit JSON-Download; ohne CDN oder Upload |
| `/_hoori/publication` | Routing-Manifest, auch ohne Dokumentationsprozess |

Jede aktuelle Antwort trägt `X-Hoori-Publication`. Bei Snapshotwechsel, fehlendem
Vertrag oder Frischeablauf liefern die Dokumentendpunkte 503, bis wieder eine
vollständige passende Publikation vorliegt. Ein unbekannter Gruppenname liefert
404. Es gibt keine verteilte Gleichzeitigkeit zwischen zwei getrennten Requests;
Clients können deren Publikations-IDs vergleichen.

Die Doku beschreibt strukturell veröffentlichte Routen. Die Gateway-Policy prüft
Permissions weiterhin je Request; die Doku ist kein Berechtigungsnachweis für den
aktuellen Benutzer. Registry und Demos bleiben unauthentifiziert.

Feste Grenzen: 64 KiB je Dokument/Manifest/aggregierter Gruppe, JSON-Tiefe 32,
128 Operationen pro Service, 256 Gateway-Routen, 512 Einträge je JSON-Container,
8192 Schritte je Schema-/Operationsauflösung, höchstens 64 Vertragsartefakte mit
zusammen 256 KiB kanonischen Quelldaten und
16 API-Gruppen pro Publikation. Überläufe machen die Doku unverfügbar; sie ersetzen
das fachliche Routing nicht. Der Dokumentenpool hat eine Verbindung und eine
Sekunde Exchange-Timeout; ein Refresh erhält ein gemeinsames Fünf-Sekunden-Budget.
Er wird beim Shutdown unterbrochen und geschlossen,
nachdem die HTTP-Handler gedraint wurden.

## Nächste Ausbaustufen

1. YAML ausschließlich beim Build normalisieren; gleiche kanonische JSON-Artefakte
   und dieselben Fehlerfälle verwenden. Abnahme: YAML und JSON erzeugen gleiche Bytes.
2. Den konservativen Baselinevergleich um ausdrücklich belegte kompatible Schema-
   Erweiterungen ergänzen. Abnahme: gerichtete Request-/Response-Negativfälle und
   ein echter alter Consumer gegen den neuen Provider.
3. Weitere HTTP-Features erst zusammen mit der originalen SDK-Unterstützung:
   bedingte Antworten/304 und Requestheader, Problem-Details-Medientypen, danach
   bei Bedarf Streaming. Je Feature Binding, Gateway-Weiterleitung, Dokument und
   native Abnahme gemeinsam erweitern.
4. Authentifizierungs-/Security-Schemes erst an eine tatsächliche Service- und
   Benutzeridentität binden. Anschließend geschützte Docs oder getrennte interne
   API-Gruppen mit einer konkreten Veröffentlichungs-Policy prüfen.
