# Roadmap

This consumer repository stays independent of Hoori's completed MS0–MS5 runtime roadmap. The
examples are not migrated Dahemm business services.

## MVC foundation (#35–#42)

The default model is application → controller → service → repository/client. Bootstrap, MVC
binding, typed clients, the HTTP endpoint catalog and the gateway use the original SDKs. The
optional task/data examples follow the same structure. The concrete local acceptance and its
limits are recorded in [validation.md](validation.md).

## Open operational work

The OpenAPI foundation adds service-owned contracts, build/baseline checks, hash-bound
artifacts and consistent gateway publications. Next steps, each with its acceptance criterion:

1. Normalize YAML only at build time, producing the same canonical JSON artifacts and the same
   failure cases. Acceptance: YAML and JSON produce identical bytes.
2. Extend the conservative baseline comparison with explicitly proven compatible schema
   extensions. Acceptance: directed request/response negative cases and a real old consumer
   against the new provider.
3. Add further HTTP features only together with original SDK support: conditional responses/304
   and request headers, problem-details media types, then streaming if needed. Per feature,
   extend binding, gateway forwarding, documents and native acceptance together.
4. Bind authentication/security schemes only to a real service and user identity. Then evaluate
   protected docs or separate internal API groups with a concrete publication policy.

Further items:

- [#8](https://github.com/TrautmannP/hoori-micro/issues/8): minimal runtime image and a
  qualified upgrade process.
- [#10](https://github.com/TrautmannP/hoori-micro/issues/10): own load/rolling and resource
  acceptance; functional smoke checks do not replace it.
- [#11](https://github.com/TrautmannP/hoori-micro/issues/11): repeated release baseline against
  plain REST. Old measurements do not qualify the MVC API.
- Production identity, registry authorization, TLS/capability negative cases and an authorized
  CI pipeline need their own implementation and evidence.

## Before a Dahemm migration

Record the current app API, domain boundaries, data ownership, household permissions and
transaction requirements separately. Only then pick a small use case that can be switched back.
The HTTP demo uses in-memory storage; the data example owns only its own schema and demonstrates
no data migration.

The actual login, household and object permissions must first be checked against the current
backend. This repository implements neither Firebase token verification nor a production token
relay or a general security filter chain. The MVC switch is not a security approval.

Design persistent outbox delivery, idempotent consumers, a highly available registry, circuit
breakers or tracing only for a concrete need. Do not derive distributed ACID/exactly-once
guarantees from local transaction checks.
