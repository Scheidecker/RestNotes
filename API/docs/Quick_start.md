# Quick start — notes, tags and locking
Author: Carlos Scheidecker

From the API directory, run `docker compose up --build --wait`. Compose starts MySQL and the API, initializes the database/user, and applies Liquibase schema plus explicitly enabled local demo data. No manual SQL or extra Bash script is needed. Local defaults are development-only.

Compose requires Docker Engine with the Compose plugin; the full CURL walkthrough also requires Bash, curl and Python 3. Existing volumes upgrade through additive migration 005 without a reset.

Open http://localhost:8081/actuator/health (expect UP) and http://localhost:8081/swagger-ui.html (identity, teams, notebooks, sharing, notes, tags and editing leases are documented).

Run `./mvnw test` for unit/style checks and `./mvnw verify` for the full real-MySQL integration suite (Docker required). Stop Compose with `docker compose down`, without deleting volumes; restart preserves records. Container builds run unit/style checks, not Docker-backed integration tests.

Engineering and Marketing have distinct admin users, one notebook/note each and initial history/activity. See [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) for sample identifiers and local-demo credentials, then follow the [CURL authentication walkthrough](CURL_Guide.md#register-login-and-read-your-profile) and [team administration walkthrough](CURL_Guide.md#team-administration).

New users have no memberships until they create a team or an admin adds them. Continue through [notebooks/sharing](CURL_Guide.md#notebooks-and-cross-team-sharing), [note CRUD](CURL_Guide.md#note-crud) and [tag management](CURL_Guide.md#tag-management). Note EDITOR-or-higher access permits create/update/archive/tag changes; VIEWER stays read-only, including capped shared access. Capture the note's own ETag before PUT/PATCH/DELETE or tag mutations. Content/tags/history/activity commit together.

Add one tag with POST `/api/v1/notes/{id}/tags` and `{name}` (200 with full note/ETag); remove with DELETE `/api/v1/notes/{id}/tags/{tagName}` (204 with ETag). URI-encode the path segment. Names normalize to stripped lowercase and are limited to 50 UTF-16 units; notes support 20 tags. Current-validator duplicate adds/absent removals are no-ops. Catalog entries belong to the note's team and survive removal; PUT/PATCH do not accept tags.

For optional exclusive editing, follow [note locking](CURL_Guide.md#note-locking): GET lock status, POST with note If-Match to acquire a fixed five-minute lease, then send Note-Lock-Token on note/tag writes, renewal and release. Capture the initial secret without printing it; renewal returns no replacement token. Unlocked notes retain ordinary ETag writes. Missing holder credentials or another actor's active lease return 423; a holder's mismatched token or any supplied token with no active lease returns 409. No admin bypass exists; revoked holders cannot renew and others can take over after expiry. A lost acquisition response/token cannot be recovered through GET; wait for expiry rather than assuming the request failed.

Local dev/test generate ephemeral JWT signing keys unless both key-file locations are configured: restarting the API invalidates existing bearer tokens, so log in again. Database editing leases and their separate lock tokens survive restart until expiry. UAT/prod require mounted persistent keys and default registration off.

For a host-run JVM, provision a reachable MySQL instance and configure DB_URL/DB_USERNAME/DB_PASSWORD as described in [README](../README.md). Compose does not expose MySQL to the host by default.

See [project structure](../README.md#project-structure), [API design](API.Design.md) and [CURL guide](CURL_Guide.md). The implemented scope ends at editing leases; collaboration/history/search/activity REST and complete deployment/usage manuals remain later, separately authorized work.
