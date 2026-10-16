# CURL guide — implemented foundation endpoints
Author: Carlos Scheidecker

Start the API using the [quick start](Quick_start.md). All URLs assume the default local port 8081; substitute the configured host port if changed.

## Health and application info
Run `curl --fail-with-body http://localhost:8081/actuator/health` (200, status UP).

Run `curl --fail-with-body http://localhost:8081/actuator/health/liveness` and `curl --fail-with-body http://localhost:8081/actuator/health/readiness` (200 when ready).

Run `curl --fail-with-body http://localhost:8081/actuator/info` (200, approved application/build metadata). Health omits infrastructure details.

## OpenAPI and Swagger — dev/test only
Run `curl --fail-with-body http://localhost:8081/v3/api-docs` for JSON, or `curl --fail-with-body http://localhost:8081/v3/api-docs.yaml` for YAML (200).

Run `curl --fail-with-body http://localhost:8081/v3/api-docs/swagger-config` for Swagger configuration (200).

Run `curl --fail-with-body --location http://localhost:8081/swagger-ui.html` to follow the redirect to Swagger HTML (200). Direct assets are served under `/swagger-ui/`.

These routes are disabled and denied in UAT/prod. The OpenAPI definition currently has no business operations.

## Verify fail-closed access
Run `curl --include http://localhost:8081/actuator/env` (401).

Run `curl --include http://localhost:8081/api/v1/notes` (401). This is a security-boundary check, not an implemented note listing.

## Phase 2 persistence
The same operational endpoints now run with MySQL/Liquibase and JPA schema validation. Readiness includes database connectivity; health still hides component details.

The [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) lists the two pre-populated admin users, deterministic sample identifiers and local-only demo password. The accounts and data exist in MySQL, but registration/JWT login is Phase 3 and note/team workflows follow later. No usable API token exists yet. Working login and business CURL examples will be added with those endpoints.
