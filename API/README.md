# RestNotes
Author: Carlos Scheidecker

Spring Boot REST API for small-team notes. **Phase 1 foundation only:** the application starts, exposes operational endpoints and development OpenAPI/Swagger UI, and denies all business requests. Registration, JWT login, database integration and pre-populated data are not implemented yet.

## Stack
Java 25, Maven Wrapper 3.9.16, Spring Boot 4.1.1, Springdoc 3.1.1, Spring MVC, Validation, Security and Actuator. Spring dependency versions come from the Boot parent. The optional `spring-milestones` Maven profile declares the requested milestone repository with snapshots disabled; normal builds use Central.

Persistence dependencies (Data JPA, MySQL and Liquibase), migrations and Testcontainers are added together in Phase 2 rather than disabling auto-configuration in an otherwise empty application. JWT authentication follows in Phase 3.

## Run locally
Prerequisites: JDK 25 and unzip (or Expand-Archive on Windows); first build requires network access to Maven Central. No database is needed in this phase. The wrapper pins the Maven ZIP SHA-256, so unzip is required on Unix to avoid its alternate tar.gz download path.

Run `./mvnw spring-boot:run` from the API directory. The default profile is dev and the port is 8081. Alternatively use `./mvnw verify` then `java -jar target/restnotes.jar`.

## Run with Docker Compose
Prerequisites: Docker Engine with Compose. From this directory run `docker compose up --build --wait`. The build runs tests and static checks. API publishing is loopback-only at http://localhost:8081.

Use `docker compose logs api` to inspect logs and `docker compose down` to stop the containers. Do not add volume deletion flags for a routine shutdown.

The optional `database` Compose profile reserves MySQL configuration for Phase 2. It is not started by default or connected to the API yet. Supply nonempty DB_PASSWORD and MYSQL_ROOT_PASSWORD securely before enabling that profile; never use empty-password options. No root credentials are passed to the API. MySQL initialization variables affect only an empty data directory; a named volume preserves data across restarts.

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

Production database/JWT secret validation will be introduced when those features exist; Phase 1 does not accept or consume those credentials. Never store real secrets in Git, logs, images or command-line literals. `.env.example` contains names only; local `.env` files and key files are ignored.

## Validation
- `./mvnw test`: Checkstyle and foundation/MVC security tests.
- `./mvnw verify`: full current suite, real-HTTP integration smoke test, and executable jar.
- `./mvnw checkstyle:check`: source style checks.
- `docker compose config --quiet`: validate container configuration without rendering values.

Tests currently cover safe health/info, both health probes, dev JSON/YAML OpenAPI and Swagger assets, sensitive endpoint denial, and production documentation restrictions. A Failsafe integration smoke test also exercises the embedded HTTP server on a random port. MySQL-backed integration and concurrency suites are Phase 2 onward; H2 is not planned.

## Required demo data — Phase 2
Liquibase will pre-populate exactly two isolated teams:
- Engineering: `engineering.admin@scheideckerantunes.com`, ADMIN, one notebook and one note authored by this admin.
- Marketing: `marketing.admin@scheideckerantunes.com`, ADMIN, one notebook and one note authored by this admin.

Each sample note will have an initial history snapshot and activity entry. Demo changesets will be explicitly development-only, will not duplicate records on restart, and will never load in production. Demo logins become usable with Phase 3 authentication.

## Documentation
- [API design](docs/API.Design.md)
- [Quick start](docs/Quick_start.md)
- [CURL guide for currently implemented operations](docs/CURL_Guide.md)

Planned manuals, to be linked when authored: `Database-Schema-Reference.md`, `GCP_Deployment.md`, `AWS_Deployment.md`, `Step-by-step-usage-manual.md`, and `SYSTEM_MANUAL.md` under docs/. PlantUML component, class and sequence sources and rendered SVGs will live under docs/diagrams/ as the domain is implemented.

## Review gates
1. Foundation — this increment.
2. Persistence, Liquibase and demo fixtures.
3. Identity, teams and role enforcement.
4. Notebooks/notes, tags, archive and concurrency.
5. Comments, history restore, search and activity.
6. Release validation and complete deployment/usage manuals.

