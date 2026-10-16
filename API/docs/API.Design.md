# RestNotes API design
Author: Carlos Scheidecker

## Implemented: Phase 3 identity and teams
One Java 25 Spring Boot application, one Maven module, package `com.scheideckerantunes.notes`. Authentication/profile APIs and team creation/discovery/member administration are implemented. Notebook/note controllers remain unavailable and denied.

Health/probes/info, dev/test documentation, and POST register/login permit anonymous requests. Profile and implemented team routes require a validated bearer token; other routes default-deny. Basic/form login are disabled and requests are stateless. CSRF is disabled for header-bearer authentication; cookie authentication would require revisiting that choice. No cross-origin policy is enabled. Health omits component details and Actuator exposure is not wildcarded.

UAT/prod disable documentation and registration by default and require non-demo DB credentials plus mounted persistent RSA keys, including mixed dev/prod profiles. Deployment-edge throttling, identity recovery and the remaining note workflows are not implemented; this increment must not be treated as a complete production service.

## Authentication contract
`POST /api/v1/auth/register` accepts only email/password/fullName, normalizes identity, validates size/UTF-8 limits, persists BCrypt and returns a credential-free profile with 201. It creates no membership. Duplicate email races are decided by the database unique constraint and return 409. Unknown JSON fields are rejected rather than accepting privilege-related attributes.

`POST /api/v1/auth/login` delegates password checks to Spring's DaoAuthenticationProvider, keeping generic 401 failures and unknown-user timing mitigation. Successful login returns a non-cacheable RS256 JWT with UUID subject, issuer, audience, issued-at, expiry and unique token ID. No role/profile data is encoded.

Spring's resource-server decoder validates the signature/algorithm and all required claims, with a 30-second clock tolerance; current account existence is checked on bearer requests. It trusts no role/scope authorities from tokens. A profile is scalar; affiliations are queried from current database memberships before pagination and counting. No token query parameters, refresh flow, logout/revocation list or public account lookup route is supported.

Controller/filter errors use RFC 9457 Problem Details and a safe X-Correlation-Id. Request/response credentials and token values are never logged. Password-bearing request toString methods are redacted, and profile DTOs exclude hashes. Generic failures do not return exception messages.

Dev/test may generate ephemeral keys; configured keys must be matching RSA PEM files of at least 2048 bits. UAT/prod cannot fall back to generated keys. Stable keys survive restart; ephemeral keys do not. See README for variables and operational limitations.

## Team administration contract
Authenticated users may create teams. Creation persists the team, creator ADMIN membership and TEAM_CREATED activity in one transaction. Names are trimmed, nonblank, at most 100 characters; descriptions are optional and limited to 2,000 characters. Names are not globally unique; UUIDs identify teams.

Team discovery and detail require current membership. All three roles can list their team's members, including email/fullName/role/joinedAt but never password hashes. Team/member collections are paginated and filtered/count-scoped in SQL; ordering is fixed by name/ID and fullName/userId respectively.

Only a current team ADMIN can add, change roles or remove members. Adds use a normalized existing-user email and explicit ADMIN/EDITOR/VIEWER role; no email is sent, no user is implicitly registered, and no global admin permission exists. Nested target IDs must belong to the selected team. No-op role updates return 200 without new activity. Authorized removal of an already absent membership returns 204 without new activity; a removed caller has no further team access and receives 404 on repeat access.

All membership writes acquire a pessimistic team-row lock before locking/refreshing the caller and target memberships. The explicit refresh prevents a cached role from authorizing a write. The final-admin check uses a locking current-read query, not a repeatable-read snapshot count. This serializes cross-row invariant checks across replicas without application-memory locks. Membership write transactions have a 15-second timeout. Role updates use serialized last-writer-wins semantics, not ETags.

Demoting/removing the final ADMIN returns 409 (`LAST_TEAM_ADMIN`); duplicate additions return 409 (`MEMBER_ALREADY_EXISTS`). Missing/nonmember-visible resources use the same generic 404, while non-admin members receive 403 for writes. Mutations record MEMBER_ADDED/UPDATED/REMOVED metadata transactionally; failed/no-op operations create no activity. These are basic activity records, not a complete security audit trail; the activity HTTP feed is a later phase.

The last-admin invariant applies to service-mediated writes. SQL foreign keys/checks alone cannot enforce it; migrations and operator scripts must preserve it. Team rename/delete and invitation/acceptance APIs are not part of this increment.

## Persistence increment
JPA/MySQL/Liquibase integration is implemented. Liquibase owns DDL; Hibernate validates it. Testcontainers runs the same MySQL image as Compose, not H2. Official image variables create the RestNotes database and APIUser on an empty volume; Liquibase does not connect as root or provision server users.

The seed dataset contains Engineering and Marketing, each with its distinct admin, notebook, authored note, initial history and activity. Emails use `@scheideckerantunes.com`. Both users belong only to their own team. Demo data requires explicit dev/test activation and is rejected in UAT/prod.

An early configuration guard rejects root/unsafe protected credentials before connections. A Spring bean lifecycle hook selects the schema-only or explicit-demo Liquibase entry point before migration initialization, without replacing Boot auto-configuration or relying on private Boot customizer types. A direct demo changelog invocation without the required `@demo` runtime context cannot seed data. See [schema reference](Database-Schema-Reference.md).

## Planned business contracts
Feature-oriented controllers/services/repositories stay in one deployment. Authentication and notes are separate concerns, not separate services. Team services and affiliations already authorize through current memberships rather than token roles; forthcoming note services must do the same.

ADMIN manages notebooks and memberships; EDITOR modifies notes; VIEWER reads. Admin is team-scoped. Protect the last admin and authorize every nested-resource/query/count operation. Expose DTOs, not JPA entities. Registration never grants team access.

Use `/api/v1`, Markdown-only content, team-inherited notebook permissions, archive rather than ambiguous destructive deletion, RFC 9457 errors, and paginated business collection DTOs (page 0, size 20, max 100, stable sorts). Note writes require ETag/If-Match, returning 428 when absent and 412 when stale. Note updates/history/activity are transactional; restore appends history.

Cross-team ACLs, locks, rich-text JSON, email invites, mentions/notifications, refresh tokens and live co-editing are deferred. Use optimistic concurrency and MySQL full-text search rather than more infrastructure.

## Dependencies and delivery
Stable published baseline: Boot 4.1.1, Springdoc 3.1.1 and Maven 3.9.16. Boot controls Spring/Security/JWT library versions. Liquibase Community is explicitly overridden to 5.0.4 and verified with Boot/MySQL; Hibernate, JDBC and Testcontainers remain Boot-managed. The milestone repository is opt-in; no milestone dependency is used.
