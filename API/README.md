# RestNotes
Author: Carlos Scheidecker

Spring Boot REST API for small-team notes. **Phase 2 persistence:** MySQL, Liquibase, JPA models/repositories and the required two-team demo data are implemented. Operational endpoints and development OpenAPI/Swagger UI work; all business requests remain denied until their implementation phases.

## Stack
Java 25, Maven Wrapper 3.9.16, Spring Boot 4.1.1, Springdoc 3.1.1, Spring MVC, Validation, Security and Actuator. Spring dependency versions come from the Boot parent. The optional `spring-milestones` Maven profile declares the requested milestone repository with snapshots disabled; normal builds use Central.

Persistence uses Data JPA, MySQL 8.4.11, Liquibase Community 5.0.4 and Testcontainers 2.0.5. The deliberate Liquibase override of Boot's 5.0.3 has passed the real-MySQL migration/integration suite. JWT authentication follows in Phase 3.

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

The OpenAPI document intentionally has no business operations yet. Unknown/business routes and sensitive Actuator routes return 401; there is no generated default user, Basic login or form login. UAT/prod disable documentation by default. This skeleton is not a production-ready notes service.

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

The API refuses root connections in all profiles, and UAT/prod reject missing/blank/known demo credentials and demo seeding before datasource initialization. Production JWT safeguards arrive with Phase 3. This increment is not production-ready.

Never store real secrets in Git, logs, images or command-line literals. `.env.example` documents variable names; local `.env` files and key files are ignored. Do not print `docker compose config` or container environment values when real credentials are configured; use `config --quiet` for syntax validation.

## Validation
- `./mvnw test`: Checkstyle and database-independent domain/configuration safety tests.
- `./mvnw verify`: unit tests plus MySQL-backed migrations/repositories/MVC/real-HTTP tests and executable jar. Docker is required and integration tests are not silently skipped.
- `./mvnw checkstyle:check`: source style checks.
- `docker compose config --quiet`: validate container configuration without rendering values.

Tests cover safe operational/docs access, restricted production docs, clean schema, explicit demo contexts, repeat startup, preserved edits, seed graph, BCrypt hashes, binary UUIDs, UTC precision, tenant-aware pagination, cross-team foreign keys, history uniqueness, role/email constraints and stale writes. Testcontainers use the same pinned MySQL image as Compose and isolated disposable storage. H2 is not used.

## Required demo data — Phase 2
Liquibase pre-populates exactly two isolated teams when demo mode is explicitly enabled:
- Engineering: `engineering.admin@scheideckerantunes.com`, ADMIN, one notebook and one note authored by this admin.
- Marketing: `marketing.admin@scheideckerantunes.com`, ADMIN, one notebook and one note authored by this admin.

Each sample note has an initial history snapshot and activity entry. Demo changesets are development/test-only and do not duplicate or reset records on restart. See the [schema reference](docs/Database-Schema-Reference.md#required-development-seed-dataset) for deterministic identifiers and the public local-only demo password. These accounts cannot authenticate until Phase 3.

## Documentation
- [API design](docs/API.Design.md)
- [Database schema reference](docs/Database-Schema-Reference.md)
- [Quick start](docs/Quick_start.md)
- [CURL guide for currently implemented operations](docs/CURL_Guide.md)

Planned manuals, to be linked when authored: `GCP_Deployment.md`, `AWS_Deployment.md`, `Step-by-step-usage-manual.md`, and `SYSTEM_MANUAL.md` under docs/. PlantUML component, class and sequence sources and rendered SVGs will live under docs/diagrams/ as the domain is implemented.

## Review gates
1. Foundation — completed.
2. Persistence, Liquibase and demo fixtures — this increment.
3. Identity, teams and role enforcement.
4. Notebooks/notes, tags, archive and concurrency.
5. Comments, history restore, search and activity.
6. Release validation and complete deployment/usage manuals.

