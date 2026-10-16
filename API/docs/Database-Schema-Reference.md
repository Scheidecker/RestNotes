# Database schema reference
Author: Carlos Scheidecker

## Runtime and migrations
MySQL 8.4.11 (pinned image digest), Liquibase Community 5.0.4, Boot-managed Hibernate/JDBC.
The official image creates `RestNotes` and `APIUser` on an empty volume. APIUser has all privileges on RestNotes only for local migrations; the API never receives the root password.

`db/changelog/schema.yml` creates the schema and FULLTEXT index. `demo.yml` includes the same schema changesets and a transactional `@demo` seed changeset. The application chooses schema-only unless DEMO_DATA_ENABLED=true in dev/test. UAT/prod reject demo mode and missing/known demo DB credentials before opening connections. Migrations default off in UAT/prod; run schema-only migrations with a separate deployment identity before starting those environments.

Never edit a changeset that has been applied to a persistent database. Add forward migrations instead. MySQL DDL is not universally transactional; partial failed DDL may require an operator-approved repair/restore. Database/user creation belongs to the image or deployment operator, not Liquibase.

## Tables
- `users`: normalized unique email, BCrypt password hash, display name and timestamps.
- `teams`: name/description, timestamps and optimistic version.
- `team_members`: composite team/user key, constrained ADMIN/EDITOR/VIEWER role and joined timestamp.
- `notebooks`: owning team, title/description, creator, archive timestamp and optimistic version.
- `notes`: notebook/team, Markdown title/content, pinned state, attribution, archive timestamp and optimistic version.
- `tags`: team-scoped normalized name and timestamps.
- `note_tags`: note/tag key and shared team column constrained against both parent tables.
- `comments`: note, author, bounded plain text and timestamps.
- `note_history`: unique positive `(note_id, version_number)`, immutable title/content/pinning/tag-name JSON snapshot, editor and timestamp.
- `activity_logs`: immutable team/user/action/entity metadata and timestamp; no note contents or secrets.

All UUIDs use ordinary BINARY(16) bytes: SQL `UUID_TO_BIN(uuid)` without byte swapping matches Hibernate. References use explicit UUID fields rather than bidirectional entity collections. Foreign keys enforce existence; team services now enforce membership authorization and last-admin protection under a team-row lock.

All timestamps use UTC DATETIME(6) and application-assigned microsecond Instants. `version` is an optimistic concurrency token, not the history sequence. Content is MEDIUMTEXT with a 256 KiB byte check; comment length is 1–10,000 characters. Tags are normalized and unique per team.

Composite foreign keys enforce notebook/note ownership and same-team note/tag links. Deletion is restricted rather than cascading away history; archive endpoints follow in Phase 4. Hibernate validates schema and never creates/updates it.

Indexes support user-to-team membership, notebook/team discovery, note lists, comments by time, history versions and activity by time. FULLTEXT covers note title/content. MySQL token length, stopwords and collation affect matching; this is not arbitrary substring search. Team-aware search is implemented later.

## Required development seed dataset
The dataset has exactly two teams/admin users/memberships/notebooks/notes, and one initial history/activity row per note. No cross-team memberships are seeded.

- Engineering admin: `engineering.admin@scheideckerantunes.com`.
- Marketing admin: `marketing.admin@scheideckerantunes.com`.
- Demo initial password: `RestNotesDemo#8`.

The password above is a public, local-demo value, stored only as BCrypt hashes in seed SQL. Never reuse these accounts or passwords in shared/cloud environments. Login and profile/team-affiliation APIs are now available; note workflows follow later. Do not confuse these application identities with the APIUser database account.

Deterministic UUID convention: final digit 1 is Engineering, 2 is Marketing:
- Users: `10000000-0000-4000-8000-000000000001` and `10000000-0000-4000-8000-000000000002`.
- Teams: `20000000-0000-4000-8000-000000000001` and `20000000-0000-4000-8000-000000000002`.
- Notebooks: `30000000-0000-4000-8000-000000000001` and `30000000-0000-4000-8000-000000000002`.
- Notes: `40000000-0000-4000-8000-000000000001` and `40000000-0000-4000-8000-000000000002`.
- History: `50000000-0000-4000-8000-000000000001` and `50000000-0000-4000-8000-000000000002`.
- Activity: `60000000-0000-4000-8000-000000000001` and `60000000-0000-4000-8000-000000000002`.

Fresh Compose startup explicitly enables demo loading. Host-run dev requires DEMO_DATA_ENABLED=true to opt in. Liquibase records completion, so restart does not duplicate or overwrite seeded records or reset passwords/content. A schema-only restart does not delete previously seeded data.

## Validation
`./mvnw test` runs database-independent tests. `./mvnw verify` additionally requires Docker and executes real MySQL integration tests, including migration/seed isolation, mappings, constraints and access-aware query pagination. Tests use separate disposable containers, never the Compose volume. Container image builds run unit checks but skip Docker-backed integration tests; run full verification on the host.
