# Six incremental implementation phases
Author: Carlos Scheidecker

These are reviewable delivery increments, not an instruction to execute now. Tests and documentation evolve alongside behavior. A sequential single-agent implementation best preserves manual review gates; no child-agent orchestration is proposed.

Current delivery state: phases 1–3 and the Phase 4 notebook/note/tag/editing-lease slice are implemented. Phase 5 REST workflows and Phase 6 complete manuals/release-readiness work remain deferred. See the [README validation baseline](../README.md#validation) for the completed locking checks. Suggested commit subjects below describe historical delivery boundaries, not instructions to stage or commit.
## Phase 1 — Foundation and executable skeleton
Status: implemented; the following describes its original scope.
Scope: Java 25 Maven wrapper/project, chosen parent/starters, base package, main class, YAML profiles, author attribution, minimal security defaults, Actuator health/info, Springdoc, initial Docker/Compose scaffolding, README and architecture decisions.
Keep the empty skeleton runnable without pretending business endpoints exist. Add persistence dependencies together with the database implementation in Phase 2, rather than adding temporary auto-configuration exclusions. Add JWT dependencies with authentication in Phase 3. The Phase 1 optional MySQL Compose scaffold is not connected to the API.
Acceptance after explicit authorization: compilation/context smoke checks, health/OpenAPI accessibility under documented dev security, and no accidental exposure of sensitive Actuator endpoints.
Suggested manual commit subject: `chore: bootstrap RestNotes Spring Boot API`.

## Phase 2 — Database foundation and reproducible fixtures
Status: implemented, with additive sharing and lease migrations delivered in Phase 4.
Scope: MySQL Compose initialization, Liquibase schema/constraints/indexes, JPA entities/repositories, explicit demo-only data, schema validation, and Testcontainers migration/repository tests.
Persistence precedes service/controller implementation to avoid building a disposable fake in-memory application. Document database initialization versus schema migration responsibilities.
Acceptance: clean initialization creates exactly the required two demo teams, their distinct admin users/memberships, notebooks, and authored sample notes with initial history/activity. Repeat startup creates no duplicate seed records. Verify schema/UUID mappings, correct seed isolation, and database user permissions limited to RestNotes.
Suggested manual commit subject: `feat: add MySQL persistence and Liquibase migrations`.

## Phase 3 — Identity, teams, and authorization
Status: implemented through team administration.
Scope: registration/login/JWT/profile, team creation/discovery, membership add/list/update/removal, authorization policies, last-admin protection, Problem Details, and page conventions.
Include unit/MVC/integration security tests and matching CURL examples. No note routes are exposed with placeholder behavior.
Acceptance: successful onboarding/authentication, invalid credentials/tokens rejected, team boundaries protected, and role changes effective immediately.
Suggested manual commit subject: `feat: add authentication and team access control`.

## Phase 4 — Notebook and note vertical slice
Status: implemented and validated through optional five-minute database editing leases, with atomic acquisition/renewal/release, owner-token enforcement on every note/tag mutation, expiry/takeover and stale-token fencing. Notebook/sharing and note CRUD/pinning/tags are complete; later collaboration/history APIs remain review-gated.
Scope: notebook management/archive, note CRUD/archive, ETags/If-Match, pinning, tags, optional editing leases, immutable snapshot creation, and transactional activity recording.
Implement repositories/services/controllers together per feature and include concurrency/authorization tests. Record history/activity now so phase-4 operations are not lost before phase 5 exposes their read APIs.
Acceptance: end-to-end note/tag/lease workflows, viewer write denial, hidden archived data, canonical owner-team tag reuse, no-op/capacity semantics, lease expiry/fencing, concurrent acquisition/content/revocation, rollback across JDBC/JPA without partial history/activity, and deterministic pagination.
Suggested manual commit subject: `feat: implement notebooks notes and optimistic concurrency`.

## Phase 5 — Collaboration, history, and discovery
Scope: comments, history metadata/detail/restore, MySQL full-text search with access-aware filtering/counts, team activity retrieval, updated OpenAPI/CURL examples, and sequence diagrams.
Acceptance: restore appends rather than overwrites history, inaccessible history IDs cannot be restored, search never leaks another team's data, and every collection is paginated.
Suggested manual commit subject: `feat: add collaboration history and team search`.

## Phase 6 — Release readiness and complete manuals
Scope: expand test/concurrency/negative-case coverage, dependency/format/static checks, production safeguards, reproducible image build, clean-volume and restart Compose verification, documentation link/author audits, rendered diagrams, and both complete deployment guides.
Do not use this phase as an excuse to postpone essential tests/security from earlier phases. Do not deploy paid infrastructure without separate authorization.
Acceptance: Maven verification and approved Compose smoke workflow pass; all implemented endpoints have accurate CURL examples; README links every required manual; production forbids demo secrets/data; operational rollback/backup limits are documented.
Suggested manual commit subject: `docs: complete deployment runbooks and release validation`.
