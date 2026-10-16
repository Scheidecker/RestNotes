# RestNotes API design
Author: Carlos Scheidecker

## Implemented: Phase 2
One Java 25 Spring Boot application, one Maven module, package `com.scheideckerantunes.notes`. Web MVC/validation/security/Actuator/Springdoc and MySQL/JPA/Liquibase persistence are configured. No business controllers/services are exposed yet.

Only health/probes/info and dev/test documentation are allowed anonymously. Other routes are denied, Basic/form login are disabled, requests are stateless, and no generated user is available. CSRF is disabled for the intended header-bearer API; cookie authentication would require revisiting that choice. Health omits component details; info contains approved application/build metadata only. There is no wildcard Actuator exposure.

UAT/prod inherit disabled OpenAPI/Swagger configuration; the test suite verifies the production surface, including mixed dev/prod profiles. Database secret validation is implemented. JWT validation, throttling and public onboarding are not implemented and this increment must not be treated as production-ready.

## Persistence increment
JPA/MySQL/Liquibase integration is implemented. Liquibase owns DDL; Hibernate validates it. Testcontainers runs the same MySQL image as Compose, not H2. Official image variables create the RestNotes database and APIUser on an empty volume; Liquibase does not connect as root or provision server users.

The seed dataset contains Engineering and Marketing, each with its distinct admin, notebook, authored note, initial history and activity. Emails use `@scheideckerantunes.com`. Both users belong only to their own team. Demo data requires explicit dev/test activation and is rejected in UAT/prod.

An early configuration guard rejects root/unsafe protected credentials before connections. A Spring bean lifecycle hook selects the schema-only or explicit-demo Liquibase entry point before migration initialization, without replacing Boot auto-configuration or relying on private Boot customizer types. A direct demo changelog invocation without the required `@demo` runtime context cannot seed data. See [schema reference](Database-Schema-Reference.md).

## Planned business contracts
Feature-oriented controllers/services/repositories in one deployment. Authentication and notes are separate concerns, not separate services. Local password authentication will issue short-lived signed JWTs through Spring Security; authorization reads current team membership rather than stale token roles.

ADMIN manages notebooks and memberships; EDITOR modifies notes; VIEWER reads. Admin is team-scoped. Protect the last admin and authorize every nested-resource/query/count operation. Expose DTOs, not JPA entities. Registration never grants team access.

Use `/api/v1`, Markdown-only content, team-inherited notebook permissions, archive rather than ambiguous destructive deletion, RFC 9457 errors, and paginated business collection DTOs (page 0, size 20, max 100, stable sorts). Note writes require ETag/If-Match, returning 428 when absent and 412 when stale. Note updates/history/activity are transactional; restore appends history.

Cross-team ACLs, locks, rich-text JSON, email invites, mentions/notifications, refresh tokens and live co-editing are deferred. Use optimistic concurrency and MySQL full-text search rather than more infrastructure.

## Dependencies and delivery
Stable published baseline: Boot 4.1.1, Springdoc 3.1.1 and Maven 3.9.16. Boot controls Spring library versions. Liquibase Community is explicitly overridden to 5.0.4 and verified with Boot/MySQL; Hibernate, JDBC and Testcontainers remain Boot-managed. The milestone repository is opt-in; no milestone dependency is used. JWT dependencies arrive with Phase 3.
