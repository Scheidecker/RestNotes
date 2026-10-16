# CURL guide — authentication and operational endpoints
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

There is no refresh/logout endpoint yet. Re-login after expiry; dev's ephemeral keys also require re-login after API restart. Discard the token when finished:

```bash
unset RN_TOKEN RN_EMAIL RN_FULL_NAME
```

## Health and application info
Run `curl --fail-with-body http://localhost:8081/actuator/health` (200, status UP).

Run `curl --fail-with-body http://localhost:8081/actuator/health/liveness` and `curl --fail-with-body http://localhost:8081/actuator/health/readiness` (200 when ready).

Run `curl --fail-with-body http://localhost:8081/actuator/info` (200, approved application/build metadata). Health omits infrastructure details.

## OpenAPI and Swagger — dev/test only
Run `curl --fail-with-body http://localhost:8081/v3/api-docs` for JSON, or `curl --fail-with-body http://localhost:8081/v3/api-docs.yaml` for YAML (200).

Run `curl --fail-with-body http://localhost:8081/v3/api-docs/swagger-config` for Swagger configuration (200).

Run `curl --fail-with-body --location http://localhost:8081/swagger-ui.html` to follow the redirect to Swagger HTML (200). Direct assets are served under `/swagger-ui/`.

These routes are disabled and denied in UAT/prod. In dev/test the definition includes the four implemented authentication/profile operations and a bearerAuth security scheme for protected profile endpoints.

## Verify fail-closed access
Run `curl --include http://localhost:8081/actuator/env` (401).

Run `curl --include http://localhost:8081/api/v1/notes` (401 anonymously; 403 with a valid token). This is a security-boundary check, not an implemented note listing.

## Persistence and remaining workflows
The same operational endpoints now run with MySQL/Liquibase and JPA schema validation. Readiness includes database connectivity; health still hides component details.

The [schema reference](Database-Schema-Reference.md#required-development-seed-dataset) lists the two admin users, sample identifiers and local-only demo password. Team administration, notebook/note CRUD, tags, comments, history/restore, search and activity HTTP operations remain unavailable. Their CURL workflows will be added alongside implementation rather than documenting nonexistent endpoints as working.
