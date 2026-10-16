# Quick start — notebooks and sharing
Author: Carlos Scheidecker

From the API directory, run `docker compose up --build --wait`. Compose starts MySQL and the API, initializes the database/user, and applies Liquibase schema plus explicitly enabled local demo data. No manual SQL or extra Bash script is needed. Local defaults are development-only.

Open http://localhost:8081/actuator/health (expect UP) and http://localhost:8081/swagger-ui.html (identity, teams, notebooks and sharing are documented).

Run `./mvnw test` for unit/style checks and `./mvnw verify` for the full real-MySQL integration suite (Docker required). Stop Compose with `docker compose down`, without deleting volumes; restart preserves records. Container builds run unit/style checks, not Docker-backed integration tests.

Engineering and Marketing have distinct admin users, one notebook/note each and initial history/activity. See [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) for sample identifiers and local-demo credentials, then follow the [CURL authentication walkthrough](CURL_Guide.md#register-login-and-read-your-profile) and [team administration walkthrough](CURL_Guide.md#team-administration).

New users have no memberships until they create a team (becoming ADMIN) or an existing team admin adds them. Admins can manage members without removing/demoting the final admin. Continue with the [notebook/sharing walkthrough](CURL_Guide.md#notebooks-and-cross-team-sharing): create a notebook, capture its ETag, update it, share with another team, revoke and archive. Only owner-team ADMINs administer notebooks; recipient roles cap shared VIEWER/EDITOR access. Note HTTP workflows remain unavailable.

Local dev/test generate ephemeral signing keys unless both key-file locations are configured: restarting the API invalidates existing tokens, so log in again. UAT/prod require mounted persistent keys and default registration off.

For a host-run JVM, provision a reachable MySQL instance and configure DB_URL/DB_USERNAME/DB_PASSWORD as described in [README](../README.md). Compose does not expose MySQL to the host by default.

See [API design](API.Design.md) and [CURL guide](CURL_Guide.md). Review and manually commit this notebook/sharing increment before requesting note CRUD.
