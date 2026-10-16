# RestNotes API design
Author: Carlos Scheidecker

## Implemented: Phase 1
One Java 25 Spring Boot application, one Maven module, package `com.scheideckerantunes.notes`. Web MVC/validation/security/Actuator and Springdoc are configured. Controllers and persistence have not been invented prematurely.

Only health/probes/info and dev/test documentation are allowed anonymously. Other routes are denied, Basic/form login are disabled, requests are stateless, and no generated user is available. CSRF is disabled for the intended header-bearer API; cookie authentication would require revisiting that choice. Health omits component details; info contains approved application/build metadata only. There is no wildcard Actuator exposure.

UAT/prod inherit disabled OpenAPI/Swagger configuration; the test suite verifies the production surface. JWT validation, secret validation, throttling, and public onboarding are not implemented and this increment must not be treated as production-ready.

## Persistence increment
Add Boot-managed JPA/MySQL/Liquibase integration in Phase 2. Liquibase owns DDL; Hibernate validates it. Test real MySQL through Testcontainers rather than H2. Official MySQL image variables create the RestNotes database and APIUser on an empty volume; Liquibase must not connect as root or provision server users.

Seed two teams, Engineering and Marketing, each with its distinct admin, notebook, authored note, initial history and activity. Emails use `@scheideckerantunes.com`. Both users belong only to their own team. Demo data requires explicit development activation and must not appear in production.

## Planned business contracts
Feature-oriented controllers/services/repositories in one deployment. Authentication and notes are separate concerns, not separate services. Local password authentication will issue short-lived signed JWTs through Spring Security; authorization reads current team membership rather than stale token roles.

ADMIN manages notebooks and memberships; EDITOR modifies notes; VIEWER reads. Admin is team-scoped. Protect the last admin and authorize every nested-resource/query/count operation. Expose DTOs, not JPA entities. Registration never grants team access.

Use `/api/v1`, Markdown-only content, team-inherited notebook permissions, archive rather than ambiguous destructive deletion, RFC 9457 errors, and paginated business collection DTOs (page 0, size 20, max 100, stable sorts). Note writes require ETag/If-Match, returning 428 when absent and 412 when stale. Note updates/history/activity are transactional; restore appends history.

Cross-team ACLs, locks, rich-text JSON, email invites, mentions/notifications, refresh tokens and live co-editing are deferred. Use optimistic concurrency and MySQL full-text search rather than more infrastructure.

## Dependencies and delivery
Stable published baseline: Boot 4.1.1, Springdoc 3.1.1 and Maven 3.9.16, revalidated against Maven Central. Boot controls Spring library versions. The milestone repository is opt-in; no milestone dependency is used. Persistence and JWT dependencies arrive with their respective implementation phases to keep the foundation database-independent.
