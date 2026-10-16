# RestNotes
Author: Carlos Scheidecker

Spring Boot REST API for small-team notes. **Note locking increment:** identity/teams, notebook sharing, Markdown note CRUD/pinning, tags and optional database-backed editing leases are implemented, with atomic history snapshots. Comments and later collaboration/history REST workflows remain review-gated.

## Stack
Java 25, Maven Wrapper 3.9.16, Spring Boot 4.1.1, Springdoc 3.1.1, Spring MVC, Validation, Security and Actuator. Spring dependency versions come from the Boot parent. The optional `spring-milestones` Maven profile declares the requested milestone repository with snapshots disabled; normal builds use Central.

Persistence uses Data JPA, MySQL 8.4.11, Liquibase Community 5.0.4 and Testcontainers 2.0.5. The Liquibase override of Boot's 5.0.3 has passed real-MySQL tests. Spring Security's supported RSA JWT encoder/decoder and password authentication provider implement identity without a separate authentication service.

## Run locally
Prerequisites: JDK 25 and unzip (or Expand-Archive on Windows); first build requires network access to Maven Central. The wrapper pins the Maven ZIP SHA-256, so unzip is required on Unix.

The easiest supported path is the two-service Compose startup below. A host-run JVM needs an existing MySQL RestNotes database reachable using DB_URL, DB_USERNAME and DB_PASSWORD. Defaults are localhost:3306/RestNotes and the request's development APIUser credentials. Compose intentionally does not publish MySQL to the host; use an explicit loopback-only port override if host-run development is needed.

With that database available, run `./mvnw spring-boot:run` from the API directory. The default profile is dev and the port is 8081. Host-run dev applies schema migrations but loads demo data only when DEMO_DATA_ENABLED=true. Alternatively run `./mvnw verify` then `java -jar target/restnotes.jar`.

## Run with Docker Compose
Prerequisites: Docker Engine with Compose. From this directory run `docker compose up --build --wait`. It starts MySQL, waits until APIUser can query RestNotes, then starts the API, applies migrations, validates JPA mappings and loads demo data. API publishing is loopback-only at http://localhost:8081; MySQL stays on the internal network.

The image builder runs unit tests and static checks, but skips Docker-backed integration tests because a Docker daemon is not mounted into the build. Run `./mvnw verify` on the host for the full MySQL-backed suite.

Use `docker compose logs api` to inspect logs and `docker compose down` to stop the containers. Do not add volume deletion flags for a routine shutdown.

The Compose file uses the supplied fixed database/root credentials only as local development defaults. No root credentials are passed to the API. MySQL initialization variables affect only an empty data directory; changing them does not rotate credentials in an existing volume. Data survives both restart and ordinary `docker compose down` followed by startup. Never delete the volume as a routine troubleshooting step.

## Available endpoints
- http://localhost:8081/actuator/health
- http://localhost:8081/actuator/health/liveness
- http://localhost:8081/actuator/health/readiness
- http://localhost:8081/actuator/info
- http://localhost:8081/swagger-ui.html (dev/test)
- http://localhost:8081/v3/api-docs and `/v3/api-docs.yaml` (dev/test)

Authentication endpoints:
- `POST /api/v1/auth/register`: email, password and fullName; returns 201 and a safe profile (no automatic team membership or token).
- `POST /api/v1/auth/login`: email/password; returns accessToken, tokenType, expiresIn and expiresAt.
- `GET /api/v1/users/me`: authenticated scalar profile.
- `GET /api/v1/users/me/teams?page=0&size=20`: current affiliations, fixed ordering by team name then ID; maximum size 100.

Use `Authorization: Bearer ...` for protected routes. Unknown/unimplemented routes remain denied (401 without authentication, 403 with a valid token). Basic/form login and cookie sessions are disabled. Swagger documents the new endpoints and bearer authorization. UAT/prod disable documentation by default. See the [CURL guide](docs/CURL_Guide.md) for the complete working onboarding/login sequence.

Team administration endpoints (all require a bearer token):
- `POST /api/v1/teams`: create a team and atomically become its ADMIN.
- `GET /api/v1/teams?page=0&size=20`: list teams where you are currently a member.
- `GET /api/v1/teams/{team_id}`: read a visible team and your current role.
- `POST /api/v1/teams/{team_id}/members`: ADMIN adds a registered user by email with an explicit ADMIN/EDITOR/VIEWER role.
- `GET /api/v1/teams/{team_id}/members?page=0&size=20`: members list team members and their roles.
- `PATCH /api/v1/teams/{team_id}/members/{user_id}`: ADMIN changes a member's role.
- `DELETE /api/v1/teams/{team_id}/members/{user_id}`: ADMIN removes a member.

Every team must retain at least one ADMIN, including during concurrent requests. Membership writes lock the team first, re-check current membership roles, and commit activity metadata with the change. Admin status is team-specific; it does not grant access to other teams. No email invitation or global user search is involved.

Collections use the stable page envelope and size limit 100. Team lists sort by name/ID; member lists by full name/user ID. Unknown/inaccessible resources return 404, insufficient role on a visible team returns 403, and duplicate membership/final-admin conflicts return 409 with a stable code. Team names need not be globally unique.

Notebook/sharing endpoints:
- `POST /api/v1/notebooks`: owner-team ADMIN creates a notebook.
- `GET /api/v1/notebooks?page=0&size=20&teamId=...`: visible owned/shared notebooks; optional owning-team filter.
- `GET /api/v1/notebooks/{id}`: metadata, effective accessLevel and a caller-scoped ETag.
- `PATCH /api/v1/notebooks/{id}`: owner-team ADMIN updates title/description.
- `DELETE /api/v1/notebooks/{id}`: owner-team ADMIN archives without deleting notes/history.
- `GET /api/v1/notebooks/{id}/permissions`: owner-admin-only paginated team grants.
- `PATCH /api/v1/notebooks/{id}/permissions`: owner ADMIN upserts one `{teamId,accessLevel}` grant.
- `DELETE /api/v1/notebooks/{id}/permissions/{teamId}`: owner ADMIN revokes a grant.

Notebook PATCH/DELETE and grant mutations require one current strong If-Match: missing 428, stale 412, malformed/weak/wildcard 400. Retrieve your own current ETag from notebook detail and use returned ETags after mutations. Tags are scoped to notebook/version/caller/effective role; do not reuse another user's tag.

Sharing grants only VIEWER/EDITOR and caps each path by the recipient's current membership role. The strongest legitimate path wins when a user belongs to multiple teams. Shared access never grants notebook administration or membership in the owning team. Archived resources are hidden through all paths. Read the [notebook walkthrough](docs/CURL_Guide.md#notebooks-and-cross-team-sharing) before editing permissions.

Note endpoints:
- `POST /api/v1/notebooks/{id}/notes`: create with title/content and optional pinned (default false).
- `GET /api/v1/notebooks/{id}/notes?page=0&size=20`: visible summaries, pinned first then update-time/ID; no Markdown bodies.
- `GET /api/v1/notes/{id}`: full note, bounded existing tag names, capability and ETag.
- `PUT /api/v1/notes/{id}`: replace required title/content/pinned; preserve tag associations.
- `PATCH /api/v1/notes/{id}`: partially update those fields; omission is unchanged, explicit null is rejected.
- `DELETE /api/v1/notes/{id}`: conditionally archive without physical deletion.

Note writes require current EDITOR-or-higher access (including capped shared grants). PUT/PATCH/DELETE require a current note If-Match. Note ETags include both note/notebook versions, caller and role; a notebook metadata/permission change invalidates old note validators too. Missing precondition is 428, stale is 412, malformed/weak/wildcard/multiple validators are 400.

Markdown content may be empty but is limited to 256 KiB UTF-8; note JSON bodies are limited to 2 MiB, including streamed/chunked requests. Oversized input returns 413. Titles are trimmed/nonblank and at most 255 characters. Create/change writes atomically save immutable history plus activity; no-op updates do neither. Archive records activity without duplicating an unchanged content snapshot. See the [note walkthrough](docs/CURL_Guide.md#note-crud).

Tag endpoints (same current EDITOR-or-higher access and note If-Match):
- `POST /api/v1/notes/{id}/tags`: add one `{name}`; 200 with the full note and ETag.
- `DELETE /api/v1/notes/{id}/tags/{tagName}`: remove an association; 204 with ETag. URI-encode the name segment.

Names are stripped/lowercased, validated after normalization (1–50 UTF-16 units), and scoped to the note's owning team, including shared edits. Existing collation-equivalent names reuse the stored canonical spelling. A note supports at most 20 tags; a new 21st returns 409 TAG_LIMIT without inserting a catalog row. Duplicate adds and absent removals with a current validator are no-ops, even at capacity; stale requests still return 412. Real changes advance the note version/updater and atomically append history plus NOTE_TAGGED/NOTE_UNTAGGED activity without changing content/pinning. Removal retains reusable catalog entries. PUT/PATCH do not accept tags, and no global catalog/rename/delete endpoint exists. See the [tag walkthrough](docs/CURL_Guide.md#tag-management) for name restrictions and encoded paths.

Note locking endpoints:
- `GET /api/v1/notes/{id}/lock`: current readers retrieve active owner/expiry metadata, never tokens.
- `POST /api/v1/notes/{id}/lock`: current editors acquire a five-minute lease with note If-Match; supplying the holder's Note-Lock-Token renews it.
- `DELETE /api/v1/notes/{id}/lock`: current editors release with note If-Match and the holder's token; unlocked tokenless release is an idempotent 204.

Locks are optional: unlocked notes retain ordinary ETag-based writes. While locked, all note PUT/PATCH/archive and tag mutations require the authenticated holder's Note-Lock-Token, including no-ops. Another client of the same user also needs the token. Conflicts return 423 NOTE_LOCKED; stale/expired supplied tokens return 409 STALE_LOCK_TOKEN rather than becoming unlocked writes; malformed tokens return 400. ETags remain mandatory.

Acquisition returns 200, unchanged note ETag, ownerId/acquiredAt/expiresAt and a secret token; renewal returns metadata without reissuing it. Capture the token without printing it. Lease responses use no-store; only digests are persisted. Expiry is decided by database UTC time under ordered team/notebook/note locks, so takeover works across replicas without a timer. Losing access immediately prevents renewal/writes; others may wait up to five minutes for expiry. No force-unlock/admin bypass exists. Lease changes do not create content history/activity or update note attribution/version. Holder archive removes its lease; notebook archive hides all note/lease routes. See the [locking walkthrough](docs/CURL_Guide.md#note-locking).

## Configuration
All Spring configuration is in `src/main/resources/application.yml`.
- SERVER_PORT: 8081, then platform PORT as fallback for a host-run JVM. In Compose, SERVER_PORT controls the host port; the container listens on 8081.
- SPRING_PROFILES_ACTIVE: dev default; test, uat and prod are supported profile names.
- LOG_LEVEL: INFO.
- OPENAPI_ENABLED: true in dev/test; UAT/prod keep docs disabled.

Additional persistence settings:
- DB_URL: `jdbc:mysql://localhost:3306/RestNotes`; Compose uses `mysql:3306/RestNotes`.
- DB_USERNAME / DB_PASSWORD: supplied APIUser development defaults in dev/test only; required non-demo credentials in UAT/prod.
- DB_POOL_SIZE / DB_CONNECTION_TIMEOUT_MS: 10 / 10000.
- DEMO_DATA_ENABLED: false by default; local Compose explicitly defaults it to true.
- MIGRATIONS_ENABLED: true in dev/test; false in UAT/prod. Use a schema-only deployment migration identity before starting protected environments.

The API refuses root connections in all profiles, and UAT/prod reject missing/blank/known demo credentials and demo seeding before datasource initialization.

Authentication settings:
- REGISTRATION_ENABLED: true in dev/test, false in UAT/prod. An operator can explicitly enable onboarding; public deployment requires abuse controls.
- BCRYPT_STRENGTH: 10 by default, configurable 10–14. Registration requires 12–72 characters and at most 72 UTF-8 bytes; password whitespace is preserved.
- JWT_TTL_SECONDS: 900 by default, configurable 60–3600.
- JWT_ISSUER / JWT_AUDIENCE: RestNotes / RestNotes-api.
- JWT_PRIVATE_KEY_LOCATION / JWT_PUBLIC_KEY_LOCATION: matching PKCS#8 private and X.509 public PEM `file:` URIs. Both must be set together; RSA must be at least 2048 bits.

Without configured keys, dev/test generate an ephemeral in-memory pair. Restart invalidates their tokens. UAT/prod, including mixed dev/prod profiles, require stable externally supplied key files. Mount them read-only outside the image, grant the runtime user read access, and share the same pair across replicas. Compose forwards explicitly configured security variables but does not create key-file mounts.

Tokens validate RS256 signature, issuer, audience, expiration, issued-at, canonical UUID subject and current user existence. Timestamp tolerance is 30 seconds. Tokens contain no team roles, passwords or profile email; affiliations are read live from MySQL. No refresh/logout/revocation-list endpoint is provided: re-login on expiry, and discard local tokens when finished. Key replacement invalidates old tokens unless a later overlapping-key rotation mechanism is introduced; coordinate replacement across replicas.

HTTPS, edge throttling on registration/login, secret distribution, identity recovery/email verification and full authorization workflows remain production-deployment prerequisites. CORS is not enabled; this is a header-bearer API, not cookie authentication.

Never store real secrets in Git, logs, images or command-line literals. `.env.example` documents variable names; local `.env` files and key files are ignored. Do not print `docker compose config` or container environment values when real credentials are configured; use `config --quiet` for syntax validation.

## Validation
- `./mvnw test`: Checkstyle and database-independent domain/configuration safety tests.
- `./mvnw verify`: unit tests plus MySQL-backed migrations/repositories/MVC/real-HTTP tests and executable jar. Docker is required and integration tests are not silently skipped.
- `./mvnw checkstyle:check`: source style checks.
- `docker compose config --quiet`: validate container configuration without rendering values.

Tests cover identity/team authorization, notebooks/sharing, note CRUD/tags/leases, byte/body limits, null/omission behavior, snapshots, canonical catalog reuse, tag capacity, no-ops, lease expiry/token fencing, concurrent acquisition/edits/revocations/downgrades and transactional rollback, plus prior migration/operational checks. Testcontainers use the same pinned MySQL image as Compose and isolated disposable storage. H2 is not used.

## Required demo data — Phase 2
Liquibase pre-populates exactly two isolated teams when demo mode is explicitly enabled:
- Engineering: `engineering.admin@scheideckerantunes.com`, ADMIN, one notebook and one note authored by this admin.
- Marketing: `marketing.admin@scheideckerantunes.com`, ADMIN, one notebook and one note authored by this admin.

Each sample note has an initial history snapshot and activity entry. Demo changesets do not duplicate/reset records on restart. See the [schema reference](docs/Database-Schema-Reference.md#required-development-seed-dataset) for identifiers and local-only credentials. The two teams start isolated until an owner ADMIN explicitly shares a notebook. Seeded notes can now be read/edited using the note endpoints.

## Documentation
- [API design](docs/API.Design.md)
- [Database schema reference](docs/Database-Schema-Reference.md)
- [Quick start](docs/Quick_start.md)
- [CURL guide for currently implemented operations](docs/CURL_Guide.md)
- [Implementation phases](docs/implementation-phases.md)

Planned manuals, to be linked when authored: `GCP_Deployment.md`, `AWS_Deployment.md`, `Step-by-step-usage-manual.md`, and `SYSTEM_MANUAL.md` under docs/. PlantUML component, class and sequence sources and rendered SVGs will live under docs/diagrams/ as the domain is implemented.

## Review gates
1. Foundation — completed.
2. Persistence, Liquibase and demo fixtures — completed.
3. Identity, teams and role enforcement — completed in this increment.
4. Notebook/sharing and note CRUD/pinning/tags/snapshots/editing leases — implemented; awaiting review/manual commit.
5. Comments, history restore, search and activity.
6. Release validation and complete deployment/usage manuals.

