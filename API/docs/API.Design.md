# RestNotes API design
Author: Carlos Scheidecker

## Implemented: note CRUD
One Java 25 Spring Boot application, one Maven module, package `com.scheideckerantunes.notes`. Identity/teams, notebooks/sharing and note CRUD/pinning are implemented. Tag mutation, comments, history/restore, search and activity REST remain unavailable.

Health/probes/info, dev/test documentation, and POST register/login permit anonymous requests. Implemented profile/team/notebook/note routes require bearer authentication; other routes default-deny. Basic/form login and cookie sessions are disabled. CSRF is disabled for header-bearer authentication; revisit it if cookies are introduced. No cross-origin policy is enabled. Health hides components and Actuator exposure is not wildcarded.

UAT/prod disable documentation and registration by default and require non-demo DB credentials plus mounted persistent RSA keys, including mixed dev/prod profiles. Deployment-edge throttling, identity recovery and remaining collaboration workflows are not implemented; this increment must not be treated as a complete production service.

## Authentication contract
`POST /api/v1/auth/register` accepts only email/password/fullName, normalizes identity, validates size/UTF-8 limits, persists BCrypt and returns a credential-free profile with 201. It creates no membership. Duplicate email races are decided by the database unique constraint and return 409. Unknown JSON fields are rejected rather than accepting privilege-related attributes.

`POST /api/v1/auth/login` delegates password checks to Spring's DaoAuthenticationProvider, keeping generic 401 failures and unknown-user timing mitigation. Successful login returns a non-cacheable RS256 JWT with UUID subject, issuer, audience, issued-at, expiry and unique token ID. No role/profile data is encoded.

Spring's resource-server decoder validates the signature/algorithm and all required claims, with a 30-second clock tolerance; current account existence is checked on bearer requests. It trusts no role/scope authorities from tokens. A profile is scalar; affiliations are queried from current database memberships before pagination and counting. No token query parameters, refresh flow, logout/revocation list or public account lookup route is supported.

Controller/filter errors use RFC 9457 Problem Details and a safe X-Correlation-Id. Request/response credentials and token values are never logged. Password-bearing request toString methods are redacted, and profile DTOs exclude hashes. Generic failures do not return exception messages.

Dev/test may generate ephemeral keys; configured keys must be matching RSA PEM files of at least 2048 bits. UAT/prod cannot fall back to generated keys. Stable keys survive restart; ephemeral keys do not. See README for variables and operational limitations.

## Team administration contract
Authenticated users may create teams. Creation persists the team, creator ADMIN membership and TEAM_CREATED activity in one transaction. Names are trimmed, nonblank, at most 100 characters; descriptions are optional and limited to 2,000 characters. Names are not globally unique; UUIDs identify teams.

Team discovery and detail require current membership. All three roles can list their team's members, including email/fullName/role/joinedAt but never password hashes. Team/member collections are paginated and filtered/count-scoped in SQL; ordering is fixed by name/ID and fullName/userId respectively.

Only a current team ADMIN can add, change roles or remove members. Adds use a normalized existing-user email and explicit ADMIN/EDITOR/VIEWER role; no email is sent, no user is implicitly registered, and no global admin permission exists. Nested target IDs must belong to the selected team. No-op role updates return 200 without new activity. Authorized removal of an already absent membership returns 204 without new activity; a removed caller has no further team access and receives 404 on repeat access.

All membership writes acquire a pessimistic team-row lock before locking/refreshing the caller and target memberships. The explicit refresh prevents a cached role from authorizing a write. The final-admin check uses a locking current-read query, not a repeatable-read snapshot count. This serializes cross-row invariant checks across replicas without application-memory locks. Membership write transactions have a 15-second timeout. Role updates use serialized last-writer-wins semantics, not ETags.

Demoting/removing the final ADMIN returns 409 (`LAST_TEAM_ADMIN`); duplicate additions return 409 (`MEMBER_ALREADY_EXISTS`). Missing/nonmember-visible resources use the same generic 404, while non-admin members receive 403 for writes. Mutations record MEMBER_ADDED/UPDATED/REMOVED metadata transactionally; failed/no-op operations create no activity. These are basic activity records, not a complete security audit trail; the activity HTTP feed is a later phase.

The last-admin invariant applies to service-mediated writes. SQL foreign keys/checks alone cannot enforce it; migrations and operator scripts must preserve it. Team rename/delete and invitation/acceptance APIs are not part of this increment.

## Notebook management and explicitly restored sharing
Owner-team ADMINs create/update/archive notebooks and manage grants. Notebook title is trimmed/nonblank and at most 150 characters; description is optional and at most 2,000. Ownership/creator cannot be patched. PATCH omits/nulls to leave fields unchanged and accepts an empty description to clear its text; an empty patch is 400. Repeating unchanged metadata or grants is a no-op without extra activity or version changes.

The user explicitly restored cross-team sharing. A grant targets an existing team UUID and contains VIEWER or EDITOR only; self-sharing is 409 and external ADMIN is not a supported grant. No public links, individual-user grants, invitations or ownership transfer are provided. Only owner-team ADMINs can list/upsert/revoke grants; recipient admins do not gain this authority.

Effective access is the strongest current path: owning-team role, or min(shared access, recipient membership role). A recipient VIEWER stays VIEWER even with an EDITOR grant; a recipient ADMIN/EDITOR caps at EDITOR. Independent legitimate paths combine, so removing one grant does not revoke another path. An owner-team VIEWER with a valid external EDITOR path may read with EDITOR capability but still cannot administer the notebook.

Notebook and existing note repository reads share one EXISTS-based visibility predicate and exclude archives before pagination/counting. Effective roles are resolved in two batch queries for a notebook page, rather than per item; no grants/notes are embedded as unbounded lists. The optional notebook teamId filter means the owning team, not the recipient. Permissions are separately paginated by target team name/ID; notebook lists use update-time/ID ordering.

Metadata/archive and permission changes require one strong If-Match. Detail/create/mutation responses return ETags scoped to notebook version, caller and effective role; this also reflects role changes without falsely reusing the same strong validator. Missing precondition is 428, stale is 412, malformed/weak/wildcard or multi-tag input is 400. Permissions mutations advance the notebook's normal JPA version/update timestamp along with atomic activity.

Write commands use READ_COMMITTED and lock teams before the notebook, refreshing current role/state and retaining the 15-second timeout. Grant/revoke commands lock owner and recipient teams in sorted UUID order to avoid reciprocal-share foreign-key deadlocks; missing recipients are reported only after authorization. The initial ownership lookup is a scalar projection, avoiding stale managed entity versions before locking and older MySQL repeatable-read refresh snapshots. Read-only transactions retain the datasource's default consistent snapshot behavior.

DELETE archives, does not cascade-delete notes/history/grants, and hides metadata/permissions and note visibility from all normal paths. Repeating archive as an owner ADMIN with its returned current tag is 204; an old tag is 412. Revoking an absent grant with a current tag is 204. No unarchive/purge API exists.

## Note CRUD contract
POST/GET `/notebooks/{id}/notes` and GET/PUT/PATCH/DELETE `/notes/{id}` are implemented. Full notes include Markdown, pinning, immutable attribution, row/notebook versions, effective capability and at most 20 existing tag names. Collection DTOs are summaries without bodies/tags, ordered pinned-first then update-time descending/ID, bounded by page/size 0/20/max 100. There is no global GET `/notes` or note-moving operation.

Create derives notebook/team/creator from the route and authenticated access. Title is trimmed/nonblank/max 255; content is required but may be empty, bounded to 262144 UTF-8 bytes. Pinning defaults false. PUT requires the complete mutable title/content/pinned representation. PATCH tracks field presence, leaves omitted fields unchanged, rejects explicit null and empty patches, and supports clearing content with an empty string. Tags remain read-only until their mutation increment.

VIEWER can read. Owner ADMIN/EDITOR and appropriately capped external EDITOR paths can create/edit/archive; creator identity does not impose an additional owner-only restriction. Scalar scope lookups precede writes. Commands use READ_COMMITTED and lock sorted owning/candidate access-path teams, then notebook and note. Capabilities are re-read under those locks, so grant removal/member downgrade serialize with edits. A new qualifying path discovered after lock selection returns retry conflict ACCESS_CHANGED rather than authorizing through an unlocked team. This conservative per-notebook/team serialization is intentional for small teams.

GET/create/changed responses return strong note ETags containing note version, notebook ID/version, caller and current role. PUT/PATCH/DELETE require one current note If-Match: missing 428, stale 412, malformed/weak/wildcard/multiple 400. Permission or notebook metadata changes can invalidate a note tag even when its row is unchanged; re-fetch before editing. Authorization is checked before conditional-write details, so revoked access is hidden as 404 or denied as 403 rather than leaked through version errors.

Creation stores history snapshot 1; real title/content/pinning changes append immutable snapshots under the note lock, independently of the JPA row version. Snapshots preserve current sorted tag names. Data/version, history and activity commit/rollback together. No-op writes do not change updater/version or create history/activity. Archive changes attribution/version and adds NOTE_ARCHIVED activity, retains existing content/history/tags, and creates no redundant content snapshot. Archived notes/notebooks are hidden; repeating note archive with its returned current tag is 204 while the caller retains editor access and the notebook is active.

Note request DTOs redact content/title in toString. Validation enforces 256 KiB UTF-8 content, and RequestBodyAdvice bounds JSON reads to 2 MiB regardless of Content-Length. Size violations are 413 with static details; application exception advice takes precedence over Boot's default validation handler. Full note bodies are never used in application logs or error details.

## Persistence increment
JPA/MySQL/Liquibase integration is implemented. Liquibase owns DDL; Hibernate validates it. Testcontainers runs the same MySQL image as Compose, not H2. Official image variables create the RestNotes database and APIUser on an empty volume; Liquibase does not connect as root or provision server users.

The seed dataset contains Engineering and Marketing, each with its distinct admin, notebook, authored note, initial history and activity. Emails use `@scheideckerantunes.com`. Both users belong only to their own team. Demo data requires explicit dev/test activation and is rejected in UAT/prod.

An early configuration guard rejects root/unsafe protected credentials before connections. A Spring bean lifecycle hook selects the schema-only or explicit-demo Liquibase entry point before migration initialization, without replacing Boot auto-configuration or relying on private Boot customizer types. A direct demo changelog invocation without the required `@demo` runtime context cannot seed data. See [schema reference](Database-Schema-Reference.md).

## Planned business contracts
Feature-oriented controllers/services/repositories stay in one deployment. Authentication and notes are separate concerns, not separate services. Team/notebook/note services authorize through current memberships and grants rather than token roles.

ADMIN manages notebooks and memberships; EDITOR modifies notes; VIEWER reads. Admin is team-scoped. Protect the last admin and authorize every nested-resource/query/count operation. Expose DTOs, not JPA entities. Registration never grants team access.

Use `/api/v1`, Markdown-only content, owning-team plus granted access, archive instead of destructive deletion, RFC 9457 errors and paginated DTOs. Note ETag/history/activity behavior is implemented; future restore must append history and reuse the same locked authorization/write contract.

Public/individual ACLs, editing leases, rich-text JSON, email invites, mentions/notifications, refresh tokens and live co-editing remain deferred. Team-level cross-team notebook sharing is now implemented. Use optimistic concurrency and MySQL full-text search rather than more infrastructure.

## Dependencies and delivery
Stable published baseline: Boot 4.1.1, Springdoc 3.1.1 and Maven 3.9.16. Boot controls Spring/Security/JWT library versions. Liquibase Community is explicitly overridden to 5.0.4 and verified with Boot/MySQL; Hibernate, JDBC and Testcontainers remain Boot-managed. The milestone repository is opt-in; no milestone dependency is used.
