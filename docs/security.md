# Sicherheitsgrenzen

Die mitgelieferten Anwendungen sind eine private Entwicklungsdemo.
Registry und interne Controller-Endpunkte sind **unauthentifiziert**.
`HOORI_GATEWAY_PERMISSIONS` gewährt seine Permissions jedem Aufrufer; eine
`@GatewayRoute` ist eine ausdrückliche Veröffentlichung, keine Benutzeridentität.

Aktuelle Grenzen:

- Gatewayziele stammen ausschließlich aus dem geprüften, begrenzten Katalog.
  Ohne authentifizierte Registry ist dieser Katalog kein Identitätsnachweis.
- Das Gateway prüft die deklarierte Permission pro Anfrage; nicht publizierte
  Methoden bleiben vom Gateway unerreichbar. Interne Netzwerkzugriffe benötigen
  weiterhin eine vertrauenswürdige Umgebung.
- Externe Endpoint-/Versions-/Budgetheader bestimmen keine internen Aufrufe.
  Credentials, Cookies und Hop-by-hop-Header werden nicht weitergereicht.
- DTO-/Body-/Katalog-/Admission-Bounds und sichere Feld-/Fehlercodes begrenzen
  Eingaben und Ausgaben. Logs enthalten keine Tokens, Bodies oder rohen Peerfehler.
- Request-ID dient der Korrelation, nicht der Authentifizierung.

Vor einer produktiven Nutzung sind Benutzer- und Service-Identität, authentifizierte
Registry-Schreibrechte, TLS/mTLS, Schlüsselrotation und Autorisierung beim
Datenbesitzer separat umzusetzen. Der spätere Security-Kontext gehört in
Request-/TaskContext, nicht in ThreadLocal. Ein Benutzer-Token darf nicht ungeprüft
als interne Berechtigung weitergereicht werden.

Für eine Dahemm-Migration müssen die tatsächlichen Login-, Haushalts- und
Objektberechtigungen zuerst am aktuellen Backend geprüft werden. Dieses Repository
implementiert weder Firebase-Tokenprüfung noch einen produktiven Token-Relay oder
eine allgemeine Security-Filterkette. Die MVC-Umstellung ist keine Security-Freigabe.
