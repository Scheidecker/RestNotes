# CURL guide — authentication, team administration and operational endpoints
Author: Carlos Scheidecker

Start the API using the [quick start](Quick_start.md). Examples below use Bash, curl and Python 3. Use HTTPS outside localhost, do not enable shell tracing (`set -x`), and never print tokens. Set the base URL once:

```bash
export RN_BASE_URL='http://localhost:8081'
```

## Register, login and read your profile
### 1. Register an account — POST /api/v1/auth/register
Registration is enabled by default in dev/test, disabled by default in UAT/prod. Pick an email not already registered. Passwords must have 12–72 characters and at most 72 UTF-8 bytes; they are not trimmed.

```bash
export RN_EMAIL='new.user@scheideckerantunes.com'
export RN_FULL_NAME='New User'
IFS= read -r -s -p 'New account password: ' RN_PASSWORD
printf '\n'
export RN_PASSWORD
python3 -c 'import json,os; print(json.dumps({
    "email":os.environ["RN_EMAIL"],
    "password":os.environ["RN_PASSWORD"],
    "fullName":os.environ["RN_FULL_NAME"]}))' |
  curl --fail-with-body --include \
    --header 'Content-Type: application/json' --data-binary @- \
    "$RN_BASE_URL/api/v1/auth/register"
```

Expected: 201, Location `/api/v1/users/me`, and id/email/fullName/createdAt. No hash, password, token or membership is returned. Duplicate normalized emails return 409, invalid/unknown fields return 400, disabled registration returns 403. Repeating registration is not idempotent; use login for an existing account.

### 2. Authenticate — POST /api/v1/auth/login
Use the registered RN_EMAIL/RN_PASSWORD, or switch RN_EMAIL to `engineering.admin@scheideckerantunes.com` or `marketing.admin@scheideckerantunes.com` and re-enter the local-demo password from the [schema reference](Database-Schema-Reference.md#required-development-seed-dataset).

Capture the token rather than displaying the login response:

```bash
RN_TOKEN=$(
  python3 -c 'import json,os; print(json.dumps({
      "email":os.environ["RN_EMAIL"],
      "password":os.environ["RN_PASSWORD"]}))' |
    curl --silent --show-error --fail-with-body \
      --header 'Content-Type: application/json' --data-binary @- \
      "$RN_BASE_URL/api/v1/auth/login" |
    python3 -c 'import json,sys; result=json.load(sys.stdin)
if "accessToken" not in result: sys.exit("Login failed; no access token returned")
print(result["accessToken"])'
)
export RN_TOKEN
unset RN_PASSWORD
```

Expected login status: 200, with accessToken, tokenType Bearer, expiresIn (default 900 seconds) and expiresAt, plus Cache-Control no-store. The pipeline captures only accessToken. Wrong passwords and unknown users return the same generic 401. A failed login yields no usable RN_TOKEN; correct credentials and retry.

### 3. Read your profile — GET /api/v1/users/me
The token is sent through curl's header input stream rather than putting it literally into the command:

```bash
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --fail-with-body --header @- "$RN_BASE_URL/api/v1/users/me"
```

Expected: 200 with the current user's safe scalar profile. No arbitrary-user profile lookup or hash is exposed. Missing/invalid/expired bearer credentials return 401 with WWW-Authenticate Bearer. Tokens in query parameters are not accepted.

### 4. List your current affiliations — GET /api/v1/users/me/teams

```bash
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --fail-with-body --header @- \
    "$RN_BASE_URL/api/v1/users/me/teams?page=0&size=20"
```

Expected: `{items,page,size,totalElements,totalPages}`. Defaults: page 0, size 20; size is limited to 100 and ordering is fixed by team name then ID. Invalid pagination returns 400. New accounts have an empty items collection; each seeded admin initially sees only its own team and ADMIN role. Membership changes appear without obtaining a new token because roles are not trusted from JWTs.

### 5. Check protected-route failures

```bash
curl --include --header 'X-Correlation-Id: auth-example' "$RN_BASE_URL/api/v1/users/me"
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --include --header @- "$RN_BASE_URL/actuator/env"
```

Expected: 401 for the first request and 403 for the second. Errors use application/problem+json with status, safe detail and correlationId. Supplied correlation IDs must contain only letters/digits/dot/underscore/hyphen, at most 64 characters; otherwise a new UUID is assigned.

There is no refresh/logout endpoint yet. Re-login after expiry; dev's ephemeral keys also require re-login after API restart. Keep RN_TOKEN for the following team walkthrough and discard it using the cleanup commands afterward.

## Team administration
Continue with the newly registered user's token from above, or log in again if it expired. Every team operation requires a bearer token. New users can create teams but do not automatically join any existing teams.

### 1. Create a team — POST /api/v1/teams
The caller becomes its ADMIN atomically. The description is optional; names are nonblank and limited to 100 characters, descriptions to 2,000. Duplicate names are allowed.

```bash
RN_TEAM_ID=$(
  curl --silent --show-error --fail-with-body \
    --header @<(printf 'Authorization: Bearer %s\n' "$RN_TOKEN") \
    --header 'Content-Type: application/json' \
    --data '{"name":"Release Planning","description":"Small-team release notes"}' \
    "$RN_BASE_URL/api/v1/teams" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)
export RN_TEAM_ID
RN_CREATOR_ID=$(
  printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
    curl --silent --show-error --fail-with-body --header @- "$RN_BASE_URL/api/v1/users/me" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)
export RN_CREATOR_ID
```

Expected: 201, Location `/api/v1/teams/{id}` and a team representation with your current role ADMIN. The script captures its ID. Bash process substitution supplies an Authorization header file descriptor without putting the token value into curl's command-line arguments.

### 2. Discover teams — GET /api/v1/teams

```bash
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --fail-with-body --header @- "$RN_BASE_URL/api/v1/teams?page=0&size=20"
```

Expected: 200 with the page envelope. Only teams where you are currently a member appear, including total counts. Ordering is fixed by name then ID; page defaults to 0, size to 20, maximum 100.

### 3. Read a team — GET /api/v1/teams/{team_id}

```bash
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --fail-with-body --header @- "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID"
```

Expected: 200 with id/name/description/createdAt and your current role. Missing and inaccessible teams both return the same generic 404.

### 4. Add a registered member — POST /api/v1/teams/{team_id}/members
Use an existing account different from the caller. The local demo Marketing admin is suitable when following this walkthrough as a newly registered user or the Engineering admin; choose Engineering instead if the caller is Marketing.

```bash
export RN_MEMBER_EMAIL='marketing.admin@scheideckerantunes.com'
RN_MEMBER_ID=$(
  python3 -c 'import json,os; print(json.dumps({
      "email":os.environ["RN_MEMBER_EMAIL"], "role":"VIEWER"}))' |
    curl --silent --show-error --fail-with-body \
      --header @<(printf 'Authorization: Bearer %s\n' "$RN_TOKEN") \
      --header 'Content-Type: application/json' --data-binary @- \
      "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID/members" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["userId"])'
)
export RN_MEMBER_ID
```

Expected: 201 with Location pointing at the membership URI and userId/email/fullName/role/joinedAt (no password hash). This is a direct addition, not an emailed invitation. The role is required and must be ADMIN, EDITOR or VIEWER. Unknown registered-user email returns 404; adding the same member again returns 409 with code MEMBER_ALREADY_EXISTS.

### 5. List members — GET /api/v1/teams/{team_id}/members

```bash
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --fail-with-body --header @- \
    "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID/members?page=0&size=20"
```

Expected: 200 with the page envelope, fixed ordering by fullName/userId and maximum size 100. All current team roles may read this team-scoped member list. No global user/member listing is exposed.

### 6. Change a role — PATCH /api/v1/teams/{team_id}/members/{user_id}

```bash
curl --fail-with-body --request PATCH \
  --header @<(printf 'Authorization: Bearer %s\n' "$RN_TOKEN") \
  --header 'Content-Type: application/json' --data '{"role":"EDITOR"}' \
  "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID/members/$RN_MEMBER_ID"
```

Expected: 200 with the updated member. Only current ADMINs may change roles. Repeating the same role is a no-op without duplicate activity. Role changes and removal affect authorization immediately; tokens do not contain team permissions.

Since the added member is EDITOR and you remain the only ADMIN, the following requests must fail:

```bash
curl --include --request PATCH \
  --header @<(printf 'Authorization: Bearer %s\n' "$RN_TOKEN") \
  --header 'Content-Type: application/json' --data '{"role":"VIEWER"}' \
  "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID/members/$RN_CREATOR_ID"
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --include --request DELETE --header @- \
    "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID/members/$RN_CREATOR_ID"
```

Expected: 409 with code LAST_TEAM_ADMIN for both. To hand off ownership operationally, first promote another member to ADMIN, then demote or remove yourself. Concurrent attempts cannot leave the team without an admin.

### 7. Remove a member — DELETE /api/v1/teams/{team_id}/members/{user_id}

```bash
printf 'Authorization: Bearer %s\n' "$RN_TOKEN" |
  curl --fail-with-body --include --request DELETE --header @- \
    "$RN_BASE_URL/api/v1/teams/$RN_TEAM_ID/members/$RN_MEMBER_ID"
```

Expected: 204. Repeating removal of an absent membership returns 204 while the caller remains a team ADMIN; it produces no extra activity. An EDITOR/VIEWER attempting writes receives 403. A nonmember cannot view/administer this team and receives 404, including a caller that previously removed their own membership.

Team changes and their activity metadata commit together. Role writes are serialized, without ETag/If-Match requirements. No team rename/delete, standalone member-detail GET, invitations or activity-feed HTTP operations are promised by this increment.

Clean up shell credentials/identifiers when finished:

```bash
unset RN_TOKEN RN_EMAIL RN_FULL_NAME RN_TEAM_ID RN_CREATOR_ID RN_MEMBER_EMAIL RN_MEMBER_ID
```

## Health and application info
Run `curl --fail-with-body http://localhost:8081/actuator/health` (200, status UP).

Run `curl --fail-with-body http://localhost:8081/actuator/health/liveness` and `curl --fail-with-body http://localhost:8081/actuator/health/readiness` (200 when ready).

Run `curl --fail-with-body http://localhost:8081/actuator/info` (200, approved application/build metadata). Health omits infrastructure details.

## OpenAPI and Swagger — dev/test only
Run `curl --fail-with-body http://localhost:8081/v3/api-docs` for JSON, or `curl --fail-with-body http://localhost:8081/v3/api-docs.yaml` for YAML (200).

Run `curl --fail-with-body http://localhost:8081/v3/api-docs/swagger-config` for Swagger configuration (200).

Run `curl --fail-with-body --location http://localhost:8081/swagger-ui.html` to follow the redirect to Swagger HTML (200). Direct assets are served under `/swagger-ui/`.

These routes are disabled and denied in UAT/prod. In dev/test the definition includes authentication/profile and team/member operations with bearerAuth security for protected endpoints.

## Verify fail-closed access
Run `curl --include http://localhost:8081/actuator/env` (401).

Run `curl --include http://localhost:8081/api/v1/notes` (401 anonymously; 403 with a valid token). This is a security-boundary check, not an implemented note listing.

## Persistence and remaining workflows
The same operational endpoints now run with MySQL/Liquibase and JPA schema validation. Readiness includes database connectivity; health still hides component details.

The [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) lists the two admin users, sample identifiers and local-only demo password. Notebook/note CRUD, tags, comments, history/restore, search and activity HTTP operations remain unavailable. Their CURL workflows will be added alongside implementation rather than documenting nonexistent endpoints as working.
