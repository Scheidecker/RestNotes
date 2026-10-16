# Quick start — note CRUD
Author: Carlos Scheidecker

From the API directory, run `docker compose up --build --wait`. Compose starts MySQL and the API, initializes the database/user, and applies Liquibase schema plus explicitly enabled local demo data. No manual SQL or extra Bash script is needed. Local defaults are development-only.

Open http://localhost:8081/actuator/health (expect UP) and http://localhost:8081/swagger-ui.html (identity, teams, notebooks, sharing and note CRUD are documented).

Run `./mvnw test` for unit/style checks and `./mvnw verify` for the full real-MySQL integration suite (Docker required). Stop Compose with `docker compose down`, without deleting volumes; restart preserves records. Container builds run unit/style checks, not Docker-backed integration tests.

Engineering and Marketing have distinct admin users, one notebook/note each and initial history/activity. See [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) for sample identifiers and local-demo credentials, then follow the [CURL authentication walkthrough](CURL_Guide.md#register-login-and-read-your-profile) and [team administration walkthrough](CURL_Guide.md#team-administration).

New users have no memberships until they create a team or an admin adds them. Continue through [notebooks/sharing](CURL_Guide.md#notebooks-and-cross-team-sharing) and [note CRUD](CURL_Guide.md#note-crud). Note EDITOR-or-higher access permits create/update/archive; VIEWER stays read-only, including capped shared access. Capture the note's own ETag before PUT/PATCH/DELETE. Content/history/activity commit together.

Local dev/test generate ephemeral signing keys unless both key-file locations are configured: restarting the API invalidates existing tokens, so log in again. UAT/prod require mounted persistent keys and default registration off.

For a host-run JVM, provision a reachable MySQL instance and configure DB_URL/DB_USERNAME/DB_PASSWORD as described in [README](../README.md). Compose does not expose MySQL to the host by default.

See [API design](API.Design.md) and [CURL guide](CURL_Guide.md). Review and manually commit this CRUD increment before requesting tags or later collaboration/history APIs.
