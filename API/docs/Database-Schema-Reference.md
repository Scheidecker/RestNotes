# Database schema reference
Author: Carlos Scheidecker

## Runtime and migrations
MySQL 8.4.11 (pinned image digest), Liquibase Community 5.0.4, Boot-managed Hibernate/JDBC.
The official image creates `RestNotes` and `APIUser` on an empty volume. APIUser has all privileges on RestNotes only for local migrations; the API never receives the root password.

`db/changelog/schema.yml` creates the schema and FULLTEXT index. `demo.yml` includes the same schema changesets and a transactional `@demo` seed changeset. The application chooses schema-only unless DEMO_DATA_ENABLED=true in dev/test. UAT/prod reject demo mode and missing/known demo DB credentials before opening connections. Migrations default off in UAT/prod; run schema-only migrations with a separate deployment identity before starting those environments.

Never edit a changeset that has been applied to a persistent database. Add forward migrations instead. MySQL DDL is not universally transactional; partial failed DDL may require an operator-approved repair/restore. Database/user creation belongs to the image or deployment operator, not Liquibase.

Migration `004-notebook-sharing` adds notebook_shares and extends the activity action check without rewriting changesets 001–003. Existing edited notebooks/notes/users are preserved; no grants are seeded. Upgrade tests reproduce the prior migration identities/checksums and verify the additive upgrade. Normal startup on a previous local volume should apply this migration, not require a reset.

Migration `005-note-locks` adds optional editing leases without rewriting applied schema/demo/sharing changesets. Upgrade tests cover both pre-sharing and pre-lease schemas, preserving existing content/shares and seed identities. Existing volumes upgrade normally; do not reset them.

## Tables
- `users`: normalized unique email, BCrypt password hash, display name and timestamps.
- `teams`: name/description, timestamps and optimistic version.
- `team_members`: composite team/user key, constrained ADMIN/EDITOR/VIEWER role and joined timestamp.
- `notebooks`: owning team, title/description, creator, archive timestamp and optimistic version.
- `notebook_shares`: unique notebook/recipient-team key, constrained VIEWER/EDITOR grant and shared/update timestamps.
- `notes`: notebook/team, Markdown title/content, pinned state, attribution, archive timestamp and optimistic version.
- `tags`: team-scoped normalized name and timestamps.
- `note_tags`: note/tag key and shared team column constrained against both parent tables.
- `note_locks`: one row per note, owner UUID, SHA-256 token digest, acquired/expiry UTC timestamps; restrictive note/user foreign keys and deadline/digest checks.
- `comments`: note, author, bounded plain text and timestamps.
- `note_history`: unique positive `(note_id, version_number)`, immutable title/content/pinning/tag-name JSON snapshot, editor and timestamp.
- `activity_logs`: immutable team/user/action/entity metadata and timestamp; no note contents or secrets.

All UUIDs use ordinary BINARY(16) bytes: SQL `UUID_TO_BIN(uuid)` without byte swapping matches Hibernate. References use explicit UUID fields rather than bidirectional entity collections. Foreign keys enforce existence; team services now enforce membership authorization and last-admin protection under a team-row lock.

All timestamps use UTC DATETIME(6). Domain timestamps use application-assigned microsecond Instants; lease timestamps use database UTC time. `version` is an optimistic concurrency token, not the history sequence. Content is MEDIUMTEXT with a 256 KiB byte check; comment length is 1–10,000 characters. Tags are normalized and unique per team.

Notebook shares are team-level VIEWER/EDITOR only with restrictive foreign keys and a recipient lookup index. Grant writes also advance the owning notebook's JPA version/update timestamp and record activity atomically. Archiving retains share/note/history rows but removes access through all ordinary queries. Current membership and grant checks happen before pagination/counting; strongest access paths combine without duplicate results.

Composite foreign keys enforce notebook/note ownership and same-team note/tag links. Deletion is restricted; notebooks and notes archive without cascading away history. Hibernate validates schema and never creates/updates it.

Note CRUD/tag management needs no schema migration. Initial snapshots use version_number 1; real title/content/pinning or tag association changes allocate max(version_number)+1 under the note row lock, not from the JPA version. No-op/failed writes add nothing. Archive changes the row version and activity but does not duplicate a content snapshot. Tag names are bounded to 20 in responses/snapshots.

Tag mutation locks owning/access-path teams, notebook and note, checks current role/If-Match, and updates links, note attribution/version, snapshots and NOTE_TAGGED/NOTE_UNTAGGED activity transactionally. Catalog scope always comes from the note's owning team, including for shared editors. Names are stripped/lowercased and validated after normalization (1–50 UTF-16 units, safe encoded path segments). The existing utf8mb4_0900_ai_ci uniqueness also equates case/accent aliases; catalog lookups reuse the stored canonical name. A new 21st association fails before inserting a catalog row. Duplicate adds/absent removals are no-ops with a current validator. Removing associations retains catalog entries; PUT/PATCH preserve links. These application-enforced limits must also be respected by operator SQL.

Lease rows use JDBC in the same JPA-managed transaction; the authorized note row is locked before lease reads/writes, including absent-row acquisition. Expiry uses database UTC time after lock acquisition. Only a token digest is stored; raw tokens are never persisted. Lease-only operations do not update note rows or content history/activity. Active leases gate every note/tag mutation, while expired leases permit tokenless writes/takeover. Revoked holders cannot renew; a lease may block remaining editors for at most five minutes. Expired rows are replaced/deleted lazily; note archive removes its lease atomically. Notebook archive retains rows but hides all lease access. Operator SQL must preserve these service-enforced rules and must not bypass token/role checks.

Indexes support user-to-team membership, notebook/team discovery, note lists, comments by time, history versions and activity by time. FULLTEXT covers note title/content. MySQL token length, stopwords and collation affect matching; this is not arbitrary substring search. Team-aware search is implemented later.

## Required development seed dataset
The dataset has exactly two teams/admin users/memberships/notebooks/notes, and one initial history/activity row per note. No cross-team memberships are seeded.

- Engineering admin: `engineering.admin@scheideckerantunes.com`.
- Marketing admin: `marketing.admin@scheideckerantunes.com`.
- Demo initial password: `RestNotesDemo#8`.

The password above is a public, local-demo value, stored only as BCrypt hashes in seed SQL. Never reuse these accounts or passwords in shared/cloud environments. Login, team/notebook administration and note CRUD/tag management are available. Do not confuse application identities with the APIUser database account.

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
