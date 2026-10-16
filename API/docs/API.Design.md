# RestNotes API design
Author: Carlos Scheidecker

## Implemented: note CRUD, tags and editing leases
One Java 25 Spring Boot application, one Maven module, package `com.scheideckerantunes.notes`. Identity/teams, notebooks/sharing, note CRUD/pinning, tags and optional editing leases are implemented. Comments, history/restore, search and activity REST remain unavailable.

Business paths below are relative to `/api/v1`. See the [project structure](../README.md#project-structure) for package responsibilities and the [CURL guide](CURL_Guide.md) for complete request sequences.

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

Create derives notebook/team/creator from the route and authenticated access. Title is trimmed/nonblank/max 255; content is required but may be empty, bounded to 262144 UTF-8 bytes. Pinning defaults false. PUT requires the complete mutable title/content/pinned representation. PATCH tracks field presence, leaves omitted fields unchanged, rejects explicit null and empty patches, and supports clearing content with an empty string. PUT/PATCH preserve tags; only the dedicated tag routes mutate associations.

VIEWER can read. Owner ADMIN/EDITOR and appropriately capped external EDITOR paths can create/edit/archive; creator identity does not impose an additional owner-only restriction. Scalar scope lookups precede writes. Commands use READ_COMMITTED and lock sorted owning/candidate access-path teams, then notebook and note. Capabilities are re-read under those locks, so grant removal/member downgrade serialize with edits. A new qualifying path discovered after lock selection returns retry conflict ACCESS_CHANGED rather than authorizing through an unlocked team. This conservative per-notebook/team serialization is intentional for small teams.

GET/create/changed responses return strong note ETags containing note version, notebook ID/version, caller and current role. PUT/PATCH/DELETE require one current note If-Match: missing 428, stale 412, malformed/weak/wildcard/multiple 400. Permission or notebook metadata changes can invalidate a note tag even when its row is unchanged; re-fetch before editing. Authorization is checked before conditional-write details, so revoked access is hidden as 404 or denied as 403 rather than leaked through version errors.

Creation stores history snapshot 1; real title/content/pinning changes append immutable snapshots under the note lock, independently of the JPA row version. Snapshots preserve current sorted tag names. Data/version, history and activity commit/rollback together. No-op writes do not change updater/version or create history/activity. Archive changes attribution/version and adds NOTE_ARCHIVED activity, retains existing content/history/tags, and creates no redundant content snapshot. Archived notes/notebooks are hidden; repeating note archive with its returned current tag and no old lock token is 204 while the caller retains editor access and the notebook is active. A supplied retired lease token instead returns 409 after the ETag check.

Note request DTOs redact content/title in toString. Validation enforces 256 KiB UTF-8 content, and RequestBodyAdvice bounds JSON reads to 2 MiB regardless of Content-Length. Size violations are 413 with static details; application exception advice takes precedence over Boot's default validation handler. Full note bodies are never used in application logs or error details.

## Tag management contract
POST `/notes/{id}/tags` accepts one `{name}` and returns 200 with the full NoteResponse and note ETag. DELETE `/notes/{id}/tags/{tagName}` returns 204 with the resulting note ETag. Both require current EDITOR-or-higher capability and a current caller-scoped note If-Match, including no-op requests. The same missing/stale/malformed conditions return 428/412/400. Current membership/share caps, archive hiding, ordered team/notebook/note locks, READ_COMMITTED and the 15-second timeout are reused.

Normalize with `String.strip().toLowerCase(Locale.ROOT)` and validate the resulting name, not the raw input: 1–50 UTF-16 units. Reject controls, Unicode format characters and line/paragraph separators, `/`, `\`, `;`, `%`, and exact `.`/`..` segments. Spaces, Unicode, plus/hash characters and dots within names are supported. Send ordinary names in JSON and URI-encode only the DELETE path segment; do not double-encode. Invalid input uses static, non-echoing error details. The streamed 2 MiB JSON limit also applies to tag requests.

The service derives catalog scope from the note's owning team, never the actor's team or a supplied team ID. MySQL's case/accent-insensitive uniqueness determines catalog identity; for example CAFE reuses a stored canonical café. Responses/snapshots retain that stored name. Removing a link leaves the team catalog row available to this and other notes. No directory, catalog rename, catalog deletion or cross-team transfer API is exposed.

At most 20 associations are allowed per note. Check capacity before creating a new catalog row; a new 21st returns 409 TAG_LIMIT without a partial insert. A duplicate add returns 200 and an absent removal 204 with unchanged ETags, updater, history and activity, including at capacity. A stale duplicate still fails 412. Serialized owner-team locks also protect canonical catalog reuse across different notes; note locks serialize competing tag/content writes and capacity checks.

Real association changes advance the note's normal JPA version/update timestamp/updater, preserve creator/title/content/pinning, and append a sorted tag-name history snapshot plus NOTE_TAGGED/NOTE_UNTAGGED activity in one transaction. Failures roll back the catalog/link, note version/attribution, snapshot and activity together. No schema migration is needed.

## Note locking contract
GET `/notes/{id}/lock` returns scalar locked/ownerId/acquiredAt/expiresAt for current readers; unlocked/expired status has null metadata. POST with current note If-Match and no Note-Lock-Token acquires an absent/expired lease for five minutes, returning 200 with metadata, a new secret token and the unchanged note ETag. POST with the active holder's token renews to five minutes from database time without returning the token again. DELETE with current note If-Match and holder token releases (204); tokenless release when unlocked/expired is an idempotent 204. All lease responses use Cache-Control no-store.

While active, the lease requires both authenticated owner identity and Note-Lock-Token for PUT/PATCH/archive and tag add/remove, including content/tag no-ops. Same-user clients do not share ownership without the credential. Otherwise unlocked writes remain unchanged. ETags still protect content versions; acquiring/renewing/releasing changes neither note ETag/updater/timestamps nor history/activity. Lock state is separate from NoteResponse, so note/list representations do not change silently under an unchanged validator.

Current editor access and note If-Match are checked before lease details. Existing 401/403/404 and 428/412/400 outcomes remain. An active lock without the holder's credential, or another actor even with that credential, returns 423 NOTE_LOCKED. For the current holder, a supplied valid-format token that does not match the generation returns 409 STALE_LOCK_TOKEN; any supplied token with no active lease also returns 409. Thus expired tokens cannot renew, release replacements, or silently write unlocked; a different actor still receives 423 when a replacement lease is active. Malformed/blank/multiple/noncanonical tokens return 400 INVALID_LOCK_TOKEN. A missing token is distinct from a blank header.

Migration 005 adds note_locks without modifying prior applied changesets. JDBC lease operations participate in the same Spring JPA transaction as note changes. The shared authorization helper locks sorted current access-path teams → notebook → note; the note mutex serializes missing-row acquisition, renewal, release, takeover and content writes across replicas. Database UTC time is queried after these locks, not before waiting or from a replica-local application clock. Expiry is exclusive: expiresAt equal to now is expired. No scheduled cleanup is needed; expired rows are replaced/removed lazily.

Tokens contain 256 random bits, canonical unpadded base64url, transported only in the named header and the initial no-store acquisition response. Persist SHA-256 digests and compare verifiers in constant time. Never log tokens/digests or echo submitted values; token-bearing DTOs redact string output. Clients keep the initial token through renewal, refetch note ETags after content/ACL changes, and acquire a fresh generation without an old token only after expiry/release.

The fixed duration is 300 seconds, not a configurable environment setting. Acquisition is not a token-recovery operation: if the response is lost, a retry without a token can return 423 even for the same user. GET status reveals no recoverable credential; wait for expiry. Retrying release without a token is safe only after checking the outcome/current status; never drop a token merely to turn an uncertain or stale-token write into an unlocked retry.

Membership downgrade/revocation immediately prevents holder writes, renewal and release. The abandoned lease can block other editors until its deadline (at most five minutes); no force-unlock/admin override is provided. Owner-admin notebook archive remains available and hides note/lease routes. Holder note archive removes its lease in the same transaction; rollback restores both lease and note state. No locking of new-note creation, read exclusion, editing presence feed, WebSocket or real-time co-editing is implied.

## Persistence increment
JPA/MySQL/Liquibase integration is implemented. Liquibase owns DDL; Hibernate validates it. Testcontainers runs the same MySQL image as Compose, not H2. Official image variables create the RestNotes database and APIUser on an empty volume; Liquibase does not connect as root or provision server users.

The seed dataset contains Engineering and Marketing, each with its distinct admin, notebook, authored note, initial history and activity. Emails use `@scheideckerantunes.com`. Both users belong only to their own team. Demo data requires explicit dev/test activation and is rejected in UAT/prod.

An early configuration guard rejects root/unsafe protected credentials before connections. A Spring bean lifecycle hook selects the schema-only or explicit-demo Liquibase entry point before migration initialization, without replacing Boot auto-configuration or relying on private Boot customizer types. A direct demo changelog invocation without the required `@demo` runtime context cannot seed data. See [schema reference](Database-Schema-Reference.md).

## Planned business contracts
Feature-oriented controllers/services/repositories stay in one deployment. Authentication and notes are separate concerns, not separate services. Team/notebook/note services authorize through current memberships and grants rather than token roles.

ADMIN manages notebooks and memberships; EDITOR modifies notes; VIEWER reads. Admin is team-scoped. Protect the last admin and authorize every nested-resource/query/count operation. Expose DTOs, not JPA entities. Registration never grants team access.

Use `/api/v1`, Markdown-only content, owning-team plus granted access, archive instead of destructive deletion, RFC 9457 errors and paginated DTOs. Note ETag/history/activity behavior is implemented; future restore must append history and reuse the same authorization, ETag and editing-lease enforcement rather than bypassing it.

Public/individual ACLs, rich-text JSON, email invites, mentions/notifications, refresh tokens and live co-editing remain deferred. Team-level cross-team sharing and optional editing leases are implemented. Retain optimistic concurrency alongside leases, and use MySQL full-text search rather than more infrastructure.

## Dependencies and delivery
Stable published baseline: Boot 4.1.1, Springdoc 3.1.1 and Maven 3.9.16. Boot controls Spring/Security/JWT library versions. Liquibase Community is explicitly overridden to 5.0.4 and verified with Boot/MySQL; Hibernate, JDBC and Testcontainers remain Boot-managed. The milestone repository is opt-in; no milestone dependency is used.
