# Security-Ablauf (geplant)

**Status: Entwurf, nicht implementiert.** Voraussetzungen in Hoori:
[#68](https://github.com/TrautmannP/hoori/issues/68) Signaturen,
[#69](https://github.com/TrautmannP/hoori/issues/69) Request-Attribute,
[#70](https://github.com/TrautmannP/hoori/issues/70) TLS-Listener/mTLS,
[#71](https://github.com/TrautmannP/hoori/issues/71) Classlib-Lücken.
Bis dahin gilt der Übergangsstand aus `architecture.md` (Demo-Policy
`HOORI_GATEWAY_PERMISSIONS`, unauthentifizierte Registry und Invoke-Endpunkt).
Keine Zwischenlösung bauen, die diesen Ablauf vorwegnimmt.

## Ausgangslage Dahemm

Dahemm nutzt **Firebase Authentication** mit Google Sign-In als einziger Login-Option.
Die App sendet `Authorization: Bearer <Firebase-ID-Token>`: ein JWT, RS256, 1 h gültig,
`sub` = Firebase-UID. Berechtigungen stehen **nicht** im Token, sondern in Dahemm.

## Ablauf

Orientiert an Spring Security: eine Filterkette authentifiziert, der Kontext liegt
am Request, Regeln autorisieren. Die Rollen von Spring stehen jeweils in Klammern.

```text
App ──Bearer (Firebase)──► Gateway
  SecurityChain (Middleware, vor dem Gateway registriert)          [SecurityFilterChain]
   1. BearerTokenResolver     nur Authorization-Header             [BearerTokenResolver]
   2. AuthenticationProvider  pro Issuer gewählt                   [AuthenticationManagerResolver]
        FirebaseTokenDecoder  JWKS, Signatur, Claims (siehe unten) [JwtDecoder]
        FirebaseIntrospector  optional: gesperrt/widerrufen?       [OpaqueTokenIntrospector]
   3. PrincipalResolver       UID → Dahemm-User + Permissions      [JwtAuthenticationConverter]
   4. SecurityContext         Request-Attribut (#69), nie ThreadLocal [SecurityContextHolder]
   5. Fehlerübersetzung       401 + WWW-Authenticate / 403         [ExceptionTranslationFilter]
  Gateway-Autorisierung       Regel der getroffenen Action         [AuthorizationManager]
       ──internes Token (EdDSA, ≤ 60 s) über mTLS (#70)──►
Service /_hoori/invoke
   InternalTokenDecoder → SecurityContext → ctx.authentication()
   Regel der Action erneut prüfen                                  [@PreAuthorize]
   Fachprüfung im Action-Code: gehört das Objekt diesem Haushalt?
```

Ohne Bearer-Header entsteht ein anonymer Kontext. Nur `permitAll()`-Actions sind dann
erreichbar. Ein vorhandener, aber ungültiger Token ist immer 401, nie anonym.

## Firebase-Token prüfen

| Prüfung | Regel |
|---|---|
| Header | `alg` = `RS256`, `kid` vorhanden; jeder andere Algorithmus wird abgewiesen |
| Schlüssel | JWKS `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`; Cache nach `Cache-Control: max-age`; unbekannte `kid` löst höchstens einen gedrosselten Neuabruf aus |
| `iss` | `https://securetoken.google.com/<projectId>` |
| `aud` | `<projectId>` |
| `exp`, `iat`, `auth_time` | `exp` in der Zukunft, `iat`/`auth_time` nicht in der Zukunft; Uhrtoleranz konfigurierbar (Default 60 s) |
| `sub` | nicht leer, ≤ 128 Zeichen; wird zur Firebase-UID |

Konfiguration: nur `HOORI_FIREBASE_PROJECT_ID`. JWKS-Abruf und Introspection laufen im
Gateway, das als einziger Dienst Internetzugang hat.

**Introspection (optional, abschaltbar):** Firebase kennt kein RFC 7662. Das Gegenstück ist
`accounts:lookup` der Identity Toolkit API mit Service-Account-Zugang (OAuth-JWT-Bearer,
RS256-signiert, #68). Abgewiesen wird bei `disabled` oder wenn `auth_time` vor
`validSince` liegt (Tokens widerrufen). Ergebnis pro UID kurz cachen (Default 60 s). Sonst
bleibt ein gesperrter Account bis zum Tokenablauf (≤ 1 h) aktiv. Das Service-Account-
Secret liegt nur beim Gateway.

`AuthenticationProvider` und `TokenIntrospector` sind Schnittstellen. Ein weiterer
OIDC-Provider oder RFC-7662-Introspection (z. B. Keycloak) kommt als zusätzliche
Implementierung, ausgewählt über `iss`, ohne Änderung am restlichen Ablauf.

## Principal und Permissions

`PrincipalResolver` ist Anwendungscode (Gegenstück zu `UserDetailsService`): Firebase-UID →
`Authentication(user, permissions)`. Er liest die Permissions aus Dahemm. Er kann selbst
eine Action sein, etwa `users.resolve` im Users-Service, mit kurzem Cache pro UID im
Gateway. Erstanmeldung (Anlegen eines Dahemm-Users) ist fachlich zu entscheiden und
gehört in diesen Resolver, nicht in die Kette.

## Regeln an der Action

Der Anbieter deklariert die Regel an seiner Action; das Gateway führt keine Pfadtabelle.
Geplante API; sie ersetzt `requirePermission` + `HOORI_GATEWAY_PERMISSIONS`:

```java
.action("get", …).http("GET", "/recipes/{id}").permitAll()
.action("mine", …).http("GET", "/recipes/mine").authenticated()
.action("delete", …).http("DELETE", "/recipes/{id}").requirePermission("recipes:write")
```

- `http()` verlangt genau eine Regel. Ohne `http()` ist eine Action nur intern erreichbar,
  aber nie anonym: Sie braucht ein gültiges internes Token (User oder Service).
- Permissions bleiben auf den eigenen Service-Namen begrenzt (`recipes:*`).
- Gateway und Anbieter prüfen dieselbe Regel aus derselben Definition. Die Fachprüfung
  (Haushalt, Besitz) bleibt im Action-Code beim Datenbesitzer.

## Interne Weitergabe

Das Firebase-Token geht **nicht** nach innen. Das Gateway stellt nach erfolgreicher
Prüfung ein internes Token aus:

| Claim | Inhalt |
|---|---|
| `iss` | `hoori-gateway` |
| `aud` | `hoori-internal` |
| `sub` | Dahemm-User-ID bzw. `service:<name>` für Hintergrundaufrufe |
| `perm` | vom `PrincipalResolver` aufgelöste Permissions |
| `rid` | Request-ID zur Korrelation |
| `exp` | ≤ 60 s |

- Signatur EdDSA (#68). Das Gateway hält den privaten Schlüssel, Services nur öffentliche
  Schlüssel mit `kid`; Rotation, indem vorübergehend zwei öffentliche Schlüssel gelten.
- `Context` reicht das eingehende interne Token bei weiteren Action-Aufrufen weiter
  (Token-Relay). Ein kompromittierter Service könnte es innerhalb seiner Laufzeit an
  andere Services richten. Die kurze Laufzeit und mTLS begrenzen das. Pro-Hop-Token-
  Exchange mit `aud` je Service erst bei konkretem Bedarf.
- Hintergrundaufrufe ohne User erhalten ein Service-Token. Die Ausgabe dafür muss
  geklärt werden: ein eigener Signierschlüssel pro Service oder eine Token-Action beim
  Gateway über mTLS.

## Transport und Registry

- `/_hoori/invoke` und die Registry nur über mTLS (#70). Die Zertifikatsidentität
  (SAN `spiffe://dahemm/<service>` o. ä.) ist die Service-Identität.
- Die Registry nimmt Registrierungen nur an, wenn der Service-Name der Zertifikats-
  identität entspricht. Das verhindert, dass ein Dienst fremde Actions oder Gateway-
  Routen kapert.
- Das Gateway akzeptiert publizierte Routen nur aus Registrierungen mit geprüfter
  Identität.

## Fehler und Logging

- 401 mit `WWW-Authenticate: Bearer error="invalid_token"` ohne Details, warum.
  403 ohne Nennung der fehlenden Permission.
- Nie Tokens, Claims, UIDs aus fehlgeschlagenen Prüfungen oder Introspection-Antworten
  loggen. Log- und Metrik-Labels höchstens mit festen Fehlerklassen (`expired`,
  `bad_signature`, …).
- Keine Cookies, daher kein CSRF-Schutz nötig. CORS erst mit einem Browser-Client.

## Umsetzungsreihenfolge

| Schritt | Inhalt | Braucht |
|---|---|---|
| 1 | `SecurityChain`, `Authentication`, SecurityContext, Action-Regeln, 401/403, Test-Provider mit fester Identität | #69 |
| 2 | `FirebaseTokenDecoder` mit JWKS-Cache und Claim-Prüfung, `PrincipalResolver` | #68 (RS256 prüfen) |
| 3 | Internes Token ausstellen/prüfen, `ctx.authentication()`, Token-Relay; `HOORI_GATEWAY_PERMISSIONS` entfernen | #68 (EdDSA) |
| 4 | `FirebaseIntrospector` über `accounts:lookup` | #68 (RS256 signieren, PKCS#8) |
| 5 | mTLS für Invoke und Registry, Registrierung an Zertifikatsidentität binden | #70 |

Jeder Schritt bringt seine Tests mit: JUnit mit festen Schlüsseln und Tokens (gültig,
abgelaufen, falscher `aud`/`iss`/`alg`, fremde Signatur, unbekannte `kid`) und Erweiterung
von `smoke.py` um 401/403/anonym/authentifiziert. Echte Firebase-Tokens nur in einem
separaten, manuell gestarteten Check, nie in CI-Fixtures.

## Nicht Bestandteil

Eigener Login oder Passwortverwaltung, Sessions und Cookies, Refresh-Token-Handling (macht
die App über Firebase), Rollenhierarchien, eine allgemeine Policy-Sprache.
