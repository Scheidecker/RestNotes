# Quick start — Phase 2
Author: Carlos Scheidecker

From the API directory, run `docker compose up --build --wait`. Compose starts MySQL and the API, initializes the database/user, and applies Liquibase schema plus explicitly enabled local demo data. No manual SQL or extra Bash script is needed. Local defaults are development-only.

Open http://localhost:8081/actuator/health (expect UP) and http://localhost:8081/swagger-ui.html (expect the RestNotes definition without business operations).

Run `./mvnw test` for unit/style checks and `./mvnw verify` for the full real-MySQL integration suite (Docker required). Stop Compose with `docker compose down`, without deleting volumes; restart preserves records. Container builds run unit/style checks, not Docker-backed integration tests.

Engineering and Marketing now have distinct admin users, one notebook/note each and initial history/activity. See [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) for sample identifiers and local-demo credentials. Login is Phase 3 and notes HTTP workflows are Phase 4; do not expect seeded accounts to authenticate yet. All business routes remain denied.

For a host-run JVM, provision a reachable MySQL instance and configure DB_URL/DB_USERNAME/DB_PASSWORD as described in [README](../README.md). Compose does not expose MySQL to the host by default.

See [API design](API.Design.md) and [CURL guide](CURL_Guide.md). Review and manually commit this increment before requesting Phase 3.
