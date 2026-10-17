# Features and functionality summary
Author: Carlos Scheidecker

RestNotes is a small-team Markdown notes REST API. It currently implements identity, teams, notebook sharing, note CRUD/pinning, tags and optional editing leases. It is one application/module/database, not a live collaborative editor or a complete production identity platform.

Use [system requirements](system-requirements.md) to install/run it, the [CURL guide](CURL_Guide.md) for executable workflows, and the [API design](API.Design.md) for exact contracts.
See [architecture and diagrams](architecture.md) for the as-built component/class views and transaction/security sequences.

## Technology stack and architecture
- **Java 25**, Maven Wrapper **3.9.16**, Spring Boot **4.1.1**; one feature-organized application under `com.scheideckerantunes.notes`.
- Spring Web MVC for synchronous REST, Jakarta Validation for DTO constraints and Spring Security for authentication/default-deny HTTP access.
- BCrypt password hashing and Spring Security's supported RS256 JWT encoder/decoder/resource-server components. Roles are read from the database, not trusted from JWT claims.
- Spring Data JPA/Hibernate for domain persistence and JdbcTemplate for editing leases, participating in shared Spring-managed transactions. Open Session in View is disabled.
- **MySQL 8.4.11**, pinned by image digest, with InnoDB relational constraints/row locks, BINARY(16) UUIDs and UTC DATETIME(6) timestamps.
- **Liquibase Community 5.0.4** for controlled additive migrations; Hibernate validates mapped entities rather than creating schema.
- **Springdoc 3.1.1** for dev/test OpenAPI/Swagger; Actuator for limited health/readiness/liveness/info.
- JUnit/Mockito, MVC/security tests and **Testcontainers MySQL 2.0.5** for realistic testing; Maven Surefire/Failsafe and Checkstyle. No H2 substitute is used.
- Multi-stage, pinned Java 25 Docker images with a non-root runtime; Docker Compose starts the API and internal MySQL with health-ordered startup and persistent storage.
- YAML/environment configuration, separate dev/test and UAT/prod safeguards, and documented credential/volume behavior.

Boot manages compatible Spring/Hibernate/JDBC/Testcontainers dependencies; the Liquibase override is explicit and tested. No Node.js/Angular frontend, Redis, external identity provider, broker or separate search server is required.

## Identity and access
Implemented operations:
- Register an email/password/fullName account; identities normalize email, hashes never appear in API DTOs, and registration grants no team membership.
- Log in and receive a short-lived signed JWT with issuer/audience/time/subject validation and current-account checks.
- Retrieve a scalar current-user profile and paginated current affiliations.
- Enable explicit protected-environment signing keys; dev/test may generate ephemeral keys. UAT/prod reject unsafe database credentials/demo data and require persistent matching RSA key files.

Authentication is header-bearer based, without Basic/form login or cookie sessions. Default JWT lifetime is 900 seconds. No refresh/logout, MFA, external OAuth sign-in, email verification or password recovery workflow is implemented.

## Teams and roles
- Any authenticated user can create a team and atomically become its ADMIN.
- Members can discover/read their teams and list members with bounded pagination.
- ADMINs directly add existing registered users, change roles and remove memberships; no invitations or email delivery are involved.
- Roles are ADMIN, EDITOR and VIEWER, scoped to each team. Team ADMIN is never global administration.
- Concurrent membership changes cannot remove/demote the final ADMIN. Real changes and activity commit together; unchanged role updates and authorized absent removals are no-ops.

Use case: a team lead creates a workspace, adds already registered colleagues and controls editor/viewer access without granting access to unrelated teams.

## Notebooks and cross-team sharing
- Owner-team ADMINs create, update and archive notebooks.
- Current users list/read visible owned or explicitly shared notebooks. Optional team filtering refers to the owning team.
- Owner-team ADMINs list, upsert and revoke team-level VIEWER/EDITOR grants.
- Shared capability is capped by the recipient's current membership role: a recipient VIEWER remains VIEWER; recipient ADMIN/EDITOR can receive at most EDITOR.
- Multiple legitimate access paths combine using their strongest effective role without duplicate collection entries.
- Notebook metadata/archive and share mutations require caller-scoped strong ETags/If-Match. Current grant/member changes take effect without issuing a new JWT.

Use case: Engineering shares one release notebook with Marketing as EDITOR without giving Marketing notebook administration or general Engineering-team access.

## Markdown notes and pinning
- Create notes inside an authorized active notebook, read full note detail and list bounded notebook-specific summaries.
- PUT replaces required title/content/pinned fields; PATCH distinguishes omitted fields from explicit null. Tags are managed through their dedicated routes.
- Pin/unpin notes; summaries sort pinned first, then update time and ID.
- Creator, updater, owning team/notebook and timestamps are server-derived. Shared editors' notes still belong to the owning notebook's team.
- Archive notes instead of physically deleting their content/history/tags. Archived notes/notebooks disappear from ordinary access paths; no purge/unarchive/move API exists.
- EDITOR-or-higher capability permits writes; VIEWER is read-only.

Notes are Markdown-only. Titles are trimmed/nonblank and limited to 255 characters; content may be empty but is capped at 256 KiB UTF-8. Note/tag JSON reads are bounded to 2 MiB, including streamed requests. Oversized input returns 413.

Use case: editors maintain a pinned release checklist while viewers retrieve the current Markdown without write privileges.

## Tags
- Add one tag by name and remove a URI-encoded name association using the current note ETag.
- Names strip/lowercase with Locale.ROOT and validate after normalization, with a 1–50 UTF-16-unit limit and restrictions that permit safe path round-trips.
- Notes support at most 20 tags; duplicates and absent removals with current validators are no-ops, even at capacity.
- Catalog ownership comes from the note's team, including shared edits. MySQL case/accent-equivalent aliases reuse the existing canonical stored name.
- Associations are removed without deleting reusable catalog entries. A rejected new 21st tag does not create an unused catalog row.

Use case: colleagues label notes consistently across a team's notebooks without creating a global tag namespace or exposing another team's catalog. No standalone catalog listing/rename/delete endpoint exists.

## Concurrency and editing leases
Two complementary mechanisms protect writes:
1. **ETags/If-Match** detect stale note/notebook representations and permission-version changes. Missing conditions are 428, stale conditions 412 and malformed/weak/wildcard/multiple conditions 400.
2. **Optional five-minute editing leases** coordinate editors across requests and application replicas. GET status reveals owner/expiry only; POST acquires/renews; DELETE releases.

An active lease requires both the authenticated holder and a secret Note-Lock-Token on note/tag mutations, including no-ops. Another client of the same user also needs the credential. ETags remain mandatory. Tokens contain 256 random bits; only SHA-256 digests are stored, and the initial token response is no-store.

The note row serializes acquisition even when no lease row exists. Database UTC time after ordered locks decides expiry; expired generations can be replaced atomically. Lease changes alone do not change note ETags, attribution, history or activity. A supplied expired/stale token cannot silently become an unlocked write.

Another actor/missing holder token against an active lease returns 423. A holder's mismatched token, or any supplied token without an active lease, returns 409. Revocation/downgrade immediately prevents holder writes/renewal; others may wait up to five minutes for expiry. No admin bypass exists. API restarts preserve leases, while dev ephemeral JWTs require re-login.

Use case: one editor coordinates a focused editing session while another reads the note and waits, without losing the ETag protection needed after ACL or content changes. This is not WebSocket presence, simultaneous editing or offline synchronization.

## Internal history and activity
- Note creation stores snapshot 1.
- Real title/content/pinning/tag changes append snapshots with independent history numbering and current sorted tag names.
- Content/link changes, updater/version, snapshot and activity commit or roll back together.
- No-op writes append nothing; archive records activity without duplicating unchanged content.
- Team/notebook/membership/share changes record their implemented activity metadata transactionally.

These are application-level immutable snapshots and content activity records, not cryptographic tamper proofs or a complete security audit log. History listing/detail/restore and activity-feed REST APIs remain unimplemented. Direct database writers are outside the service authorization boundary.

## API and operational conventions
- Versioned `/api/v1` JSON DTO endpoints; persistence entities are not exposed directly.
- Business collection pagination defaults to page 0/size 20, maximum 100, with deterministic ordering and access-scoped counts.
- Missing/inaccessible resources return generic 404; a visible resource with insufficient role returns 403; unauthenticated protected access returns 401.
- RFC 9457 Problem Details, stable business-error codes where applicable and safe correlation IDs; generic failures do not reveal stack traces, submitted credentials or Markdown.
- Unimplemented routes default-deny rather than expose placeholder functionality.
- Public health/liveness/readiness/info contain limited approved metadata; sensitive Actuator endpoints stay denied.
- Swagger/OpenAPI are available in dev/test and disabled/denied in UAT/prod.
- Compose publishes the API on loopback port 8081 by default; MySQL stays on the internal network. Named-volume data survives routine shutdown.

## Reproducible demo and verification
Explicit local-demo initialization creates Engineering and Marketing with distinct `@scheideckerantunes.com` admins, ADMIN memberships, one notebook/note each and initial history/activity. Teams initially remain isolated. Repeat startup preserves edits and does not duplicate/reset seed records.

Migrations cover schema/full-text, optional demo data, notebook sharing and note locks through 005, including upgrade fixtures for old databases. MySQL image variables initialize the database/account; Liquibase never needs a root connection.

The [README validation baseline](../README.md#validation) records 184 passing tests (52 unit, 132 integration), zero Checkstyle violations, Docker validation and 77 isolated curl requests for the locking increment. Coverage includes role caps, tenant isolation, stale writes, limits, canonical tags, races, rollback, migration/restart behavior and token fencing. Rerun verification after code changes.

## Not implemented and production follow-up
- Angular desktop/PWA UX, offline sync and real-time co-editing.
- Comments, history read/restore, search and activity-feed REST workflows. A MySQL full-text index exists, but no search endpoint is exposed.
- MFA, OAuth 2.0/OIDC federation, account recovery/verification, refresh/logout or company-wide identity APIs.
- Public links, individual-user ACLs, invitations, notifications and note ownership transfer.
- Independent note integrity signatures/checksums, full security auditing and intrusion detection.
- Complete cloud deployment/usage/system manuals, backup/restore automation, load-tested capacity and a complete production security/compliance program.

Existing safeguards do not make this demo a fully production-ready or “hacker-proof” product. See [future improvements](../README.md#what-would-i-change-add-or-stop-doing-if-i-had-time) and [implementation phases](implementation-phases.md) for the intended next work.
