#!/usr/bin/env bash
# Author: Carlos Scheidecker
# Run, do not source. Mutating dev/test walkthrough of API/docs/CURL_Guide.md.
# Credentials go through environment/stdin/header descriptors, never curl argv.
{ set +x; } 2>/dev/null
if [[ ${BASH_SOURCE[0]} != "$0" ]]; then
    printf 'Run this script as a separate process; do not source it.\n' >&2
    return 1
fi
set -Eeuo pipefail

usage() {
    cat <<'HELP'
RestNotes CURL walkthrough — Author: Carlos Scheidecker

Usage: curl-sample.sh [--base-url URL] [--demo] [--yes] [--full-output]

  --base-url URL  Default: RN_BASE_URL or http://localhost:8081
  --demo          Allow the documented public local-demo recipient password.
                  Generates a private throwaway owner password unless supplied.
  --yes           Acknowledge creation of new demonstration records, no prompt.
  --full-output   Print full sanitized results instead of bounded previews.
  -h, --help      Show this help without making requests.

Environment inputs:
  RN_SAMPLE_EMAIL               New owner's email; defaults to a unique address.
  RN_SAMPLE_PASSWORD            Optional new-owner password; otherwise generated
                                and not retained for subsequent owner login.
  RN_SAMPLE_RECIPIENT_EMAIL      Default marketing.admin@scheideckerantunes.com
  RN_SAMPLE_RECIPIENT_PASSWORD   Existing recipient's current password.
  RN_SAMPLE_SHARE_TEAM_ID        Default seeded Marketing team UUID.

Requires Bash 4+, curl, Python 3 and a running dev/test API with registration and
OpenAPI enabled. The recipient must already exist and administer the share team.
Without --demo/password environment input, a terminal prompts for its password.
Use HTTPS outside loopback. No cloud platform-IAM header support is included.

This CREATES an account/team/notebooks/notes and temporarily adds the recipient
to the new team. Only new fixtures are edited/archived; seed notes are not edited.
Records remain for inspection (there are no account/team hard-delete APIs).
Passwords, bearer tokens, cookies and lease tokens are redacted. Synthetic note
bodies and profile/member metadata are displayed: protect the resulting output.
Do not pass credentials as arguments or run with shell/curl tracing.
HELP
}

BASE_URL=${RN_BASE_URL:-http://localhost:8081}
DEMO=false
ASSUME_YES=false
export RN_SAMPLE_FULL_OUTPUT=0
while (($#)); do
    case "$1" in
        --base-url)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            BASE_URL=$2
            shift 2
            ;;
        --demo) DEMO=true; shift ;;
        --yes) ASSUME_YES=true; shift ;;
        --full-output) RN_SAMPLE_FULL_OUTPUT=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown option. Use --help.\n' >&2; exit 2 ;;
    esac
done

((BASH_VERSINFO[0] >= 4)) || { printf 'Bash 4 or later is required.\n' >&2; exit 2; }
for tool in curl python3; do
    command -v "$tool" >/dev/null || { printf 'Missing dependency: %s\n' "$tool" >&2; exit 2; }
done
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SCHEMA_GUIDE="$SCRIPT_DIR/../API/docs/Database-Schema-Reference.md"
BASE_URL=$(RN_SAMPLE_BASE_URL="$BASE_URL" RN_SAMPLE_DEMO="$DEMO" python3 - <<'PY'
import os,sys,urllib.parse
value=os.environ["RN_SAMPLE_BASE_URL"].rstrip("/")
if any(ord(c)<33 or ord(c)==127 for c in value):
    sys.exit("Base URL contains unsafe whitespace/control characters")
try:
    url=urllib.parse.urlsplit(value)
    port=url.port
except ValueError:
    sys.exit("Invalid base URL")
if (url.scheme not in ("http","https") or not url.hostname or url.username is not None
        or url.password is not None or url.query or url.fragment):
    sys.exit("Use an HTTP(S) base URL without credentials, query or fragment")
if url.scheme=="http" and url.hostname not in ("localhost","127.0.0.1","::1"):
    sys.exit("HTTP is restricted to loopback; use HTTPS for remote targets")
if os.environ["RN_SAMPLE_DEMO"]=="true" and url.hostname not in ("localhost","127.0.0.1","::1"):
    sys.exit("--demo is restricted to loopback targets")
print(value)
PY
)

if [[ "$ASSUME_YES" != true ]]; then
    [[ -t 0 ]] || { printf 'Use --yes to acknowledge fixture creation in noninteractive runs.\n' >&2; exit 2; }
    printf 'This creates demonstration records at %s; it is intended for dev/test.\n' "$BASE_URL"
    read -r -p 'Continue? [y/N] ' answer
    [[ "$answer" == y || "$answer" == Y ]] || exit 0
fi

export RN_SAMPLE_EMAIL="${RN_SAMPLE_EMAIL:-curl.sample.$(python3 -c 'import uuid; print(uuid.uuid4().hex)')@scheideckerantunes.com}"
export RN_SAMPLE_PASSWORD="${RN_SAMPLE_PASSWORD:-$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')}"
export RN_SAMPLE_RECIPIENT_EMAIL="${RN_SAMPLE_RECIPIENT_EMAIL:-marketing.admin@scheideckerantunes.com}"
export RN_SAMPLE_SHARE_TEAM_ID="${RN_SAMPLE_SHARE_TEAM_ID:-20000000-0000-4000-8000-000000000002}"
export RN_SAMPLE_RECIPIENT_PASSWORD="${RN_SAMPLE_RECIPIENT_PASSWORD:-}"

if [[ -z "$RN_SAMPLE_RECIPIENT_PASSWORD" ]]; then
    if [[ "$DEMO" == true ]]; then
        # The documented seed value is public; never print or duplicate it here.
        RN_SAMPLE_RECIPIENT_PASSWORD=$(python3 - "$SCHEMA_GUIDE" <<'PY'
import pathlib,re,sys
match=re.search(r"Demo initial password: `([^`]+)`",pathlib.Path(sys.argv[1]).read_text())
if not match: sys.exit("Public demo credential reference was not found")
print(match.group(1))
PY
)
    else
        [[ -t 0 ]] || { printf 'Set RN_SAMPLE_RECIPIENT_PASSWORD securely or explicitly use --demo.\n' >&2; exit 2; }
        read -r -s -p 'Existing recipient application password: ' RN_SAMPLE_RECIPIENT_PASSWORD
        printf '\n'
    fi
fi
python3 - <<'PY'
import os,sys,uuid
password=os.environ["RN_SAMPLE_PASSWORD"]
if not 12<=len(password)<=72 or len(password.encode())>72:
    sys.exit("New-owner password must be 12–72 characters and at most 72 UTF-8 bytes")
if not os.environ["RN_SAMPLE_RECIPIENT_PASSWORD"]:
    sys.exit("Recipient password cannot be empty")
if os.environ["RN_SAMPLE_EMAIL"].strip().lower()==os.environ["RN_SAMPLE_RECIPIENT_EMAIL"].strip().lower():
    sys.exit("Use a fresh owner account, distinct from the existing recipient")
try: uuid.UUID(os.environ["RN_SAMPLE_SHARE_TEAM_ID"])
except ValueError: sys.exit("Invalid recipient team UUID")
PY

# These names are private to this child process and are cleared even on failure.
export RN_TOKEN='' RN_RECIPIENT_TOKEN='' RN_LOCK_TOKEN='' RN_OLD_LOCK_TOKEN=''
LAST_BODY='' LAST_ETAG='' LAST_LOCATION='' LAST_STATUS=''
REQUEST_COUNT=0
CURRENT_LABEL='initialization'
NOTE_ID='' NOTE_ETAG='' TEAM_ID='' BOOK_ID='' BOOK_ETAG=''
LEASE_HELD=false
FINISHED=false

headers() {
    local actor=$1 condition=${2:-} lease=${3:-none}
    printf 'X-Correlation-Id: curl-sample-%s\n' "$REQUEST_COUNT"
    case "$actor" in
        owner) printf 'Authorization: Bearer %s\n' "$RN_TOKEN" ;;
        recipient) printf 'Authorization: Bearer %s\n' "$RN_RECIPIENT_TOKEN" ;;
        none) ;;
        *) return 2 ;;
    esac
    [[ -z "$condition" ]] || printf 'If-Match: %s\n' "$condition"
    case "$lease" in
        current) printf 'Note-Lock-Token: %s\n' "$RN_LOCK_TOKEN" ;;
        old) printf 'Note-Lock-Token: %s\n' "$RN_OLD_LOCK_TOKEN" ;;
        none) ;;
        *) return 2 ;;
    esac
}

cleanup() {
    local code=$?
    trap - EXIT
    if [[ "$LEASE_HELD" == true && -n "$RN_LOCK_TOKEN" && -n "$NOTE_ETAG" && -n "$NOTE_ID" ]]; then
        # Best effort on this script's own fixture only; no retries or force-unlock.
        curl --disable --silent --show-error --connect-timeout 3 --max-time 5 \
            --proto '=http,https' --request DELETE \
            --header @<(headers owner "$NOTE_ETAG" current) \
            --output /dev/null "$BASE_URL/api/v1/notes/$NOTE_ID/lock" || true
    fi
    unset RN_SAMPLE_PASSWORD RN_SAMPLE_RECIPIENT_PASSWORD
    unset RN_TOKEN RN_RECIPIENT_TOKEN RN_LOCK_TOKEN RN_OLD_LOCK_TOKEN LAST_BODY
    if [[ "$FINISHED" != true ]]; then
        printf '\nStopped during "%s". Fixture records may remain; no writes were blindly retried.\n' "$CURRENT_LABEL" >&2
        [[ -z "$TEAM_ID" ]] || printf 'New fixture team: %s\n' "$TEAM_ID" >&2
        [[ -z "$NOTE_ID" ]] || printf 'New fixture note: %s\n' "$NOTE_ID" >&2
        printf 'An unconfirmed acquisition can remain locked until its five-minute expiry.\n' >&2
    fi
    exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

payload() {
    local kind=$1 value=${2:-}
    case "$kind" in
        none) ;;
        json) printf '%s' "$value" ;;
        register|owner-login|recipient-login)
            RN_SAMPLE_PAYLOAD_KIND="$kind" python3 - <<'PY'
import json,os
kind=os.environ["RN_SAMPLE_PAYLOAD_KIND"]
recipient=kind=="recipient-login"
data={"email":os.environ["RN_SAMPLE_RECIPIENT_EMAIL" if recipient else "RN_SAMPLE_EMAIL"],
      "password":os.environ["RN_SAMPLE_RECIPIENT_PASSWORD" if recipient else "RN_SAMPLE_PASSWORD"]}
if kind=="register": data["fullName"]="CURL sample owner"
print(json.dumps(data))
PY
            ;;
        *) return 2 ;;
    esac
}

render() {
    # Read raw bodies via stdin, not argv/environment or on-disk credential files.
    python3 -c '
import json,os,re,sys,unicodedata
raw=sys.stdin.read()
secrets=[os.environ.get(k,"") for k in (
    "RN_SAMPLE_PASSWORD","RN_SAMPLE_RECIPIENT_PASSWORD","RN_TOKEN",
    "RN_RECIPIENT_TOKEN","RN_LOCK_TOKEN","RN_OLD_LOCK_TOKEN")]
def clean(value):
    if isinstance(value,dict):
        return {key:("[REDACTED]" if (
            re.sub(r"[^a-z0-9]","",key.lower()) in {
                "password","passwordhash","token","accesstoken","refreshtoken",
                "authorization","cookie","setcookie","digest","privatekey","privatekeypem"
            }) else clean(item)) for key,item in value.items()}
    if isinstance(value,list): return [clean(item) for item in value]
    if isinstance(value,str):
        for secret in secrets:
            if secret: value=value.replace(secret,"[REDACTED]")
        return value
    return value
if not raw:
    print("(empty response body)")
    sys.exit(0)
try: output=json.dumps(clean(json.loads(raw)),indent=2,ensure_ascii=False)
except (ValueError,TypeError): output=clean(raw)
output="".join(c if c in "\n\t" or unicodedata.category(c) not in ("Cc","Cf") else "?" for c in output)
if os.environ.get("RN_SAMPLE_FULL_OUTPUT")!="1":
    lines=output.splitlines()
    bounded="\n".join(lines[:45])
    output=bounded[:6000]
    if len(lines)>45 or len(bounded)>6000: output+="\n[preview truncated; use --full-output for the full sanitized result]"
print(output)
'
}

field() {
    printf '%s' "$LAST_BODY" | python3 -c '
import json,sys
try:
    value=json.load(sys.stdin)
    for key in sys.argv[1].split("."): value=value[key]
    if not isinstance(value,(str,int,bool)): raise ValueError()
    print(value)
except (ValueError,KeyError,TypeError):
    sys.exit("Expected response field is missing or invalid: "+sys.argv[1])
' "$1"
}

identifier() {
    local value
    value=$(field "$1")
    RN_SAMPLE_ID="$value" python3 -c 'import os,sys,uuid
try: print(uuid.UUID(os.environ["RN_SAMPLE_ID"]))
except ValueError: sys.exit("Server returned an invalid resource UUID")'
}

etag() {
    [[ -n "$LAST_ETAG" ]] || { printf 'Expected a response ETag.\n' >&2; return 1; }
    printf '%s' "$LAST_ETAG"
}

request() {
    local label=$1 method=$2 path=$3 expected=$4 actor=${5:-none}
    local condition=${6:-} kind=${7:-none} body=${8:-} lease=${9:-none} follow=${10:-false}
    local raw head line transport_status
    local -a arguments
    CURRENT_LABEL=$label
    REQUEST_COUNT=$((REQUEST_COUNT + 1))
    printf '\n================================================================\n'
    printf '[%02d] %s\n%s %s%s\nExpected HTTP: %s\n' "$REQUEST_COUNT" "$label" "$method" "$BASE_URL" "$path" "$expected"
    printf 'curl --request %s %q' "$method" "$BASE_URL$path"
    [[ "$actor" == none ]] || printf ' --header "Authorization: Bearer [REDACTED]"'
    [[ -z "$condition" ]] || printf ' --header %q' "If-Match: $condition"
    [[ "$lease" == none ]] || printf ' --header "Note-Lock-Token: [REDACTED]"'
    [[ "$kind" == none ]] || printf ' --header "Content-Type: application/json" --data-binary @-'
    [[ "$follow" == false ]] || printf ' --location'
    printf '\n'
    if [[ "$kind" != none ]]; then
        printf 'Request JSON (sanitized):\n'
        payload "$kind" "$body" | render
    fi
    arguments=(--disable --silent --show-error --connect-timeout 5 --max-time 30
        --proto '=http,https' --request "$method" --dump-header - --write-out $'\n%{http_code}')
    [[ "$kind" == none ]] || arguments+=(--header 'Content-Type: application/json' --data-binary @-)
    # Only the unauthenticated Swagger HTML request follows redirects.
    if [[ "$follow" == true ]]; then
        [[ "$actor" == none && "$lease" == none ]] || return 2
        arguments+=(--location --max-redirs 5 --proto-redir '=http,https')
    fi
    if raw=$(payload "$kind" "$body" | curl "${arguments[@]}" \
        --header @<(headers "$actor" "$condition" "$lease") "$BASE_URL$path"); then
        transport_status=0
    else
        transport_status=$?
    fi
    if ((transport_status != 0)); then
        printf 'Transport failed (curl/pipeline exit %s). No automatic POST retry.\n' "$transport_status" >&2
        return 1
    fi
    LAST_STATUS=${raw##*$'\n'}
    raw=${raw%$'\n'*}
    LAST_ETAG='' LAST_LOCATION=''
    # Handles informational/proxy headers and the Swagger redirect chain.
    while [[ "$raw" == HTTP/* ]]; do
        [[ "$raw" == *$'\r\n\r\n'* ]] || { printf 'Malformed HTTP response headers.\n' >&2; return 1; }
        head=${raw%%$'\r\n\r\n'*}
        raw=${raw#*$'\r\n\r\n'}
        LAST_ETAG='' LAST_LOCATION=''
        while IFS= read -r line; do
            line=${line%$'\r'}
            case "${line,,}" in
                etag:*) LAST_ETAG=${line#*:}; LAST_ETAG=${LAST_ETAG# } ;;
                location:*) LAST_LOCATION=${line#*:}; LAST_LOCATION=${LAST_LOCATION# } ;;
            esac
        done <<< "$head"
    done
    LAST_BODY=$raw
    printf 'Result HTTP: %s\n' "$LAST_STATUS"
    [[ -z "$LAST_ETAG" ]] || printf 'ETag: %s\n' "$LAST_ETAG"
    [[ -z "$LAST_LOCATION" ]] || printf 'Location: %s\n' "$LAST_LOCATION"
    printf 'Response (sanitized):\n'
    printf '%s' "$LAST_BODY" | render
    if [[ "$LAST_STATUS" != "$expected" ]]; then
        printf 'FAIL: expected HTTP %s, received %s. Check dev/test settings, credentials and fixture state.\n' \
            "$expected" "$LAST_STATUS" >&2
        return 1
    fi
    printf 'PASS\n'
}

refresh_book() {
    request 'Refresh owner notebook ETag' GET "/api/v1/notebooks/$BOOK_ID" 200 owner
    BOOK_ETAG=$(etag)
}

refresh_note() {
    request 'Refresh owner note ETag' GET "/api/v1/notes/$NOTE_ID" 200 owner
    NOTE_ETAG=$(etag)
}

printf 'RestNotes CURL guide demonstration — Author: Carlos Scheidecker\n'
printf 'Target: %s\nNew owner: %s\n' "$BASE_URL" "$RN_SAMPLE_EMAIL"
printf 'Only generated fixture notes are displayed. Passwords and tokens are never printed.\n'

# 1. Identity: registration, login, profile, affiliations and access failures.
request 'Register a fresh owner account' POST /api/v1/auth/register 201 none '' register
OWNER_ID=$(identifier id)
request 'Log in as the new owner' POST /api/v1/auth/login 200 none '' owner-login
RN_TOKEN=$(field accessToken)
request 'Read current owner profile' GET /api/v1/users/me 200 owner
request 'List owner affiliations (initially empty)' GET '/api/v1/users/me/teams?page=0&size=20' 200 owner
request 'Reject anonymous profile access' GET /api/v1/users/me 401
request 'Deny sensitive Actuator endpoint to authenticated user' GET /actuator/env 403 owner

# 2. Teams: all writes affect only the newly created team.
request 'Create a team and become its ADMIN' POST /api/v1/teams 201 owner '' json \
    '{"name":"CURL Release Planning","description":"Disposable walkthrough fixtures"}'
TEAM_ID=$(identifier id)
request 'Capture creator profile' GET /api/v1/users/me 200 owner
OWNER_ID=$(identifier id)
request 'Discover current teams' GET '/api/v1/teams?page=0&size=20' 200 owner
request 'Read the new team' GET "/api/v1/teams/$TEAM_ID" 200 owner
MEMBER_JSON=$(python3 -c 'import json,os; print(json.dumps({"email":os.environ["RN_SAMPLE_RECIPIENT_EMAIL"],"role":"VIEWER"}))')
request 'Add the existing recipient as VIEWER in the new team' POST "/api/v1/teams/$TEAM_ID/members" \
    201 owner '' json "$MEMBER_JSON"
MEMBER_ID=$(identifier userId)
request 'List team members' GET "/api/v1/teams/$TEAM_ID/members?page=0&size=20" 200 owner
request 'Change recipient membership to EDITOR' PATCH "/api/v1/teams/$TEAM_ID/members/$MEMBER_ID" \
    200 owner '' json '{"role":"EDITOR"}'
request 'Prevent demotion of the final ADMIN' PATCH "/api/v1/teams/$TEAM_ID/members/$OWNER_ID" \
    409 owner '' json '{"role":"VIEWER"}'
request 'Prevent removal of the final ADMIN' DELETE "/api/v1/teams/$TEAM_ID/members/$OWNER_ID" 409 owner
request 'Remove the added membership' DELETE "/api/v1/teams/$TEAM_ID/members/$MEMBER_ID" 204 owner

# 3. Notebook management and cross-team sharing.
request 'Create release notebook' POST /api/v1/notebooks 201 owner '' json \
    "{\"teamId\":\"$TEAM_ID\",\"title\":\"Release notebook\",\"description\":\"Cross-functional release planning\"}"
BOOK_ID=$(identifier id)
request 'List visible notebooks filtered by owning team' GET "/api/v1/notebooks?page=0&size=20&teamId=$TEAM_ID" 200 owner
request 'Read notebook and capture ETag' GET "/api/v1/notebooks/$BOOK_ID" 200 owner
BOOK_ETAG=$(etag)
STALE_BOOK_ETAG=$BOOK_ETAG
request 'Update notebook metadata' PATCH "/api/v1/notebooks/$BOOK_ID" 200 owner "$BOOK_ETAG" json \
    '{"title":"Updated release notebook"}'
refresh_book
request 'Reject a stale notebook update' PATCH "/api/v1/notebooks/$BOOK_ID" 412 owner "$STALE_BOOK_ETAG" json \
    '{"description":"Must not overwrite"}'
request 'List grants (initially empty)' GET "/api/v1/notebooks/$BOOK_ID/permissions?page=0&size=20" 200 owner
SHARE_JSON="{\"teamId\":\"$RN_SAMPLE_SHARE_TEAM_ID\",\"accessLevel\":\"EDITOR\"}"
request 'Grant recipient team EDITOR access' PATCH "/api/v1/notebooks/$BOOK_ID/permissions" \
    200 owner "$BOOK_ETAG" json "$SHARE_JSON"
refresh_book
request 'Log in as the existing recipient' POST /api/v1/auth/login 200 none '' recipient-login
RN_RECIPIENT_TOKEN=$(field accessToken)
unset RN_SAMPLE_RECIPIENT_PASSWORD
request 'Read shared notebook as capped EDITOR' GET "/api/v1/notebooks/$BOOK_ID" 200 recipient
[[ $(field accessLevel) == EDITOR ]] || { printf 'Recipient needs current EDITOR-or-higher team membership.\n' >&2; exit 1; }
request 'Deny shared recipient permission administration' GET "/api/v1/notebooks/$BOOK_ID/permissions" 403 recipient
request 'Revoke the shared grant' DELETE "/api/v1/notebooks/$BOOK_ID/permissions/$RN_SAMPLE_SHARE_TEAM_ID" \
    204 owner "$BOOK_ETAG"
refresh_book
request 'Hide notebook from revoked recipient' GET "/api/v1/notebooks/$BOOK_ID" 404 recipient
request 'Archive the first notebook' DELETE "/api/v1/notebooks/$BOOK_ID" 204 owner "$BOOK_ETAG"
request 'Hide archived notebook detail' GET "/api/v1/notebooks/$BOOK_ID" 404 owner

# 4. Fresh active notebook before note CRUD, matching the guide's lifecycle.
request 'Create a fresh note-CRUD notebook' POST /api/v1/notebooks 201 owner '' json \
    "{\"teamId\":\"$TEAM_ID\",\"title\":\"Note CRUD playground\"}"
BOOK_ID=$(identifier id)
refresh_book
request 'Share the fresh notebook as EDITOR' PATCH "/api/v1/notebooks/$BOOK_ID/permissions" \
    200 owner "$BOOK_ETAG" json "$SHARE_JSON"
refresh_book
request 'Create a Markdown note' POST "/api/v1/notebooks/$BOOK_ID/notes" 201 owner '' json \
    '{"title":"Release tasks","content":"# Release tasks\n\n- Review the API\n- Prepare release"}'
NOTE_ID=$(identifier id)
request 'Read full note detail' GET "/api/v1/notes/$NOTE_ID" 200 owner
refresh_note
request 'List notebook note summaries' GET "/api/v1/notebooks/$BOOK_ID/notes?page=0&size=20" 200 owner
STALE_NOTE_ETAG=$NOTE_ETAG
request 'Replace complete title/content/pinned representation' PUT "/api/v1/notes/$NOTE_ID" \
    200 owner "$NOTE_ETAG" json '{"title":"Release checklist","content":"# Checklist\n\n- Complete review","pinned":false}'
refresh_note
request 'Partially update pinning' PATCH "/api/v1/notes/$NOTE_ID" 200 owner "$NOTE_ETAG" json '{"pinned":true}'
refresh_note
request 'Read recipient-scoped note ETag' GET "/api/v1/notes/$NOTE_ID" 200 recipient
RECIPIENT_NOTE_ETAG=$(etag)
request 'Edit note as shared recipient' PATCH "/api/v1/notes/$NOTE_ID" 200 recipient "$RECIPIENT_NOTE_ETAG" json \
    '{"content":"# Checklist\n\nShared review completed."}'
refresh_note
request 'Reject stale note mutation' PATCH "/api/v1/notes/$NOTE_ID" 412 owner "$STALE_NOTE_ETAG" json '{"pinned":false}'
request 'Require If-Match on note archive' DELETE "/api/v1/notes/$NOTE_ID" 428 owner
request 'Archive the CRUD note' DELETE "/api/v1/notes/$NOTE_ID" 204 owner "$NOTE_ETAG"
request 'Hide archived note detail' GET "/api/v1/notes/$NOTE_ID" 404 owner

# 5. Fresh note before tags; archived note IDs are never reused for writes.
request 'Create fresh tag/lock playground note' POST "/api/v1/notebooks/$BOOK_ID/notes" 201 owner '' json \
    '{"title":"Tag playground","content":""}'
NOTE_ID=$(identifier id)
refresh_note
STALE_NOTE_ETAG=$NOTE_ETAG
request 'Add normalized Design Review tag' POST "/api/v1/notes/$NOTE_ID/tags" \
    200 owner "$NOTE_ETAG" json '{"name":" Design Review "}'
refresh_note
request 'Duplicate normalized tag is a no-op' POST "/api/v1/notes/$NOTE_ID/tags" \
    200 owner "$NOTE_ETAG" json '{"name":"DESIGN REVIEW"}'
[[ $(etag) == "$NOTE_ETAG" ]] || { printf 'Duplicate tag unexpectedly changed ETag.\n' >&2; exit 1; }
request 'Read shared editor validator for the fresh note' GET "/api/v1/notes/$NOTE_ID" 200 recipient
RECIPIENT_NOTE_ETAG=$(etag)
request 'Shared editor adds C++ tag' POST "/api/v1/notes/$NOTE_ID/tags" \
    200 recipient "$RECIPIENT_NOTE_ETAG" json '{"name":"C++"}'
refresh_note
TAG_SEGMENT=$(python3 -c 'import urllib.parse; print(urllib.parse.quote("C++",safe=""))')
request 'Remove URI-encoded C++ association' DELETE "/api/v1/notes/$NOTE_ID/tags/$TAG_SEGMENT" \
    204 owner "$NOTE_ETAG"
refresh_note

# 6. Optional editing leases coordinate writes without replacing ETags.
request 'Read initial lock status' GET "/api/v1/notes/$NOTE_ID/lock" 200 owner
refresh_note
request 'Acquire a five-minute editing lease' POST "/api/v1/notes/$NOTE_ID/lock" 200 owner "$NOTE_ETAG"
RN_LOCK_TOKEN=$(field token)
[[ -n "$RN_LOCK_TOKEN" ]] || exit 1
LEASE_HELD=true
[[ $(etag) == "$NOTE_ETAG" ]] || { printf 'Lease acquisition unexpectedly changed note ETag.\n' >&2; exit 1; }
request 'Reject same-user acquisition without the holder token' POST "/api/v1/notes/$NOTE_ID/lock" 423 owner "$NOTE_ETAG"
request 'Reject tokenless edit while leased' PATCH "/api/v1/notes/$NOTE_ID" 423 owner "$NOTE_ETAG" json '{"pinned":true}'
request 'Holder edits with ETag plus lease token' PATCH "/api/v1/notes/$NOTE_ID" \
    200 owner "$NOTE_ETAG" json '{"pinned":true}' current
NOTE_ETAG=$(etag)
refresh_note
request 'Renew the existing lease without reissuing a token' POST "/api/v1/notes/$NOTE_ID/lock" \
    200 owner "$NOTE_ETAG" none '' current
[[ $(etag) == "$NOTE_ETAG" ]] || { printf 'Lease renewal unexpectedly changed note ETag.\n' >&2; exit 1; }
request 'Release holder lease' DELETE "/api/v1/notes/$NOTE_ID/lock" \
    204 owner "$NOTE_ETAG" none '' current
RN_OLD_LOCK_TOKEN=$RN_LOCK_TOKEN
RN_LOCK_TOKEN=''
LEASE_HELD=false
request 'Tokenless release while unlocked is a no-op' DELETE "/api/v1/notes/$NOTE_ID/lock" 204 owner "$NOTE_ETAG"
# The guide also describes stale-token fencing; demonstrate it explicitly.
request 'Retired token cannot release an unlocked generation' DELETE "/api/v1/notes/$NOTE_ID/lock" \
    409 owner "$NOTE_ETAG" none '' old
RN_OLD_LOCK_TOKEN=''

# 7. Operational and documentation endpoints from the guide (dev/test only).
request 'Application health' GET /actuator/health 200
request 'Liveness probe' GET /actuator/health/liveness 200
request 'Readiness probe' GET /actuator/health/readiness 200
request 'Approved application/build information' GET /actuator/info 200
request 'OpenAPI JSON' GET /v3/api-docs 200
request 'OpenAPI YAML' GET /v3/api-docs.yaml 200
request 'Swagger configuration' GET /v3/api-docs/swagger-config 200
request 'Swagger HTML redirect and assets' GET /swagger-ui.html 200 none '' none '' none true
request 'Anonymous sensitive Actuator request remains denied' GET /actuator/env 401
request 'No anonymous global note directory' GET /api/v1/notes 401
request 'No authenticated global note directory' GET /api/v1/notes 403 owner

FINISHED=true
printf '\n================================================================\n'
printf 'SUCCESS: all %s named requests returned their expected HTTP status.\n' "$REQUEST_COUNT"
printf 'Fixture account: %s\nFixture team: %s\nActive notebook: %s\nTag/lock playground note: %s\n' \
    "$RN_SAMPLE_EMAIL" "$TEAM_ID" "$BOOK_ID" "$NOTE_ID"
printf 'Records remain for inspection; the lease was released. No real seed notes were changed.\n'
printf 'Credentials/tokens are cleared on exit. Later owner login requires a password you supplied.\n'
