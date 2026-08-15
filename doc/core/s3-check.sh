#!/usr/bin/env bash
#
# Does object storage actually work in a DEPLOYED environment?
#
#   doc/core/s3-check.sh https://dev.ems.lahendus.ut.ee/v2 https://dev.lahendus.ut.ee
#
# Companion to files-check.sh, not a replacement. That one runs against a local core with
# `auth-enabled: false` and proves the endpoints are correct. This one runs against a real
# environment, where authentication is real, and proves that *this* environment's bucket, credentials
# and proxying are wired up — which is the part no amount of local testing can tell you.
#
# Three sections, each skipped cleanly when its prerequisite is missing, so a partial run is honest
# rather than falsely green:
#
#   A. the bucket           needs AWS_PROFILE and BUCKET
#   B. the serve path       needs nothing at all
#   C. the full cycle       needs a teacher/admin bearer token
#
# The token is minted, not pasted. A dedicated Keycloak client with a service account
# (`easy-dev-test-runner` on dev) holds the long-lived half; this script exchanges its secret for a
# ten-minute access token on each run. That way the durable credential is a rotatable secret sitting
# in the login keychain rather than a bearer token lying around in a shell history.
#
# Store the secret once, the same way ansible/run.sh stores the become password:
#
#   security add-generic-password -a "$USER" -s easy-dev-test-token -T /usr/bin/security -U -w
#
# Then just:
#
#   AWS_PROFILE=easy-dev-test BUCKET=lahendus-dev-files \
#     doc/core/s3-check.sh https://dev.ems.lahendus.ut.ee/v2 https://dev.lahendus.ut.ee
#
# EASY_TOKEN still overrides, for a token obtained some other way. The client, keychain item and
# token endpoint are overridable too — see the variables below — so this is not dev-only by
# construction, though pointing section C at production would still be a bad idea.
#
# The service account needs three claims or core answers 401: preferred_username (free),
# email and easy_role (a JSON array of student/teacher/admin). Hardcode the latter two as mappers on
# the client's dedicated scope; a service account has no email of its own.
#
# NEVER point section C at production. It uploads a file and deletes it again; the upload is
# harmless but it is still a write, and a deployed production is not a test fixture.
set -u

API="${1:?usage: s3-check.sh <api-base-with-/v2> [web-origin]}"
WEB="${2:-}"
TOKEN="${EASY_TOKEN:-}"
PROFILE="${AWS_PROFILE:-}"
BUCKET="${BUCKET:-}"

KEYCHAIN_ITEM="${EASY_TOKEN_KEYCHAIN:-easy-dev-test-token}"
TOKEN_CLIENT="${EASY_TOKEN_CLIENT_ID:-easy-dev-test-runner}"
TOKEN_URL="${EASY_TOKEN_URL:-https://easy-idp-dev.cloud.ut.ee/auth/realms/master/protocol/openid-connect/token}"

# Mint one if we were not handed one. Deliberately quiet about everything except success or the
# IdP's own error string: the secret must not reach a terminal, a log or a transcript, and neither
# should the token — printing it would undo the point of it being short-lived.
if [ -z "$TOKEN" ] && command -v security >/dev/null 2>&1; then
  _secret=$(security find-generic-password -a "$USER" -s "$KEYCHAIN_ITEM" -w 2>/dev/null || true)
  if [ -n "$_secret" ]; then
    TOKEN=$(curl -s -m 30 -d grant_type=client_credentials -d "client_id=$TOKEN_CLIENT" \
              --data-urlencode "client_secret=$_secret" "$TOKEN_URL" \
            | python3 -c "
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('', end=''); raise SystemExit
if 'access_token' in d:
    print(d['access_token'], end='')
else:
    print('', end='')
    sys.stderr.write('  token request refused: %s - %s\n' % (d.get('error'), d.get('error_description')))
")
    [ -n "$TOKEN" ] && echo "minted a token for $TOKEN_CLIENT (short-lived, never printed)"
  fi
  unset _secret
fi

pass=0; fail=0; skip=0
tmpdir=$(mktemp -d)
created=()

check() {
  if [ "$2" = "$3" ]; then printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass + 1))
  else printf '  \033[31mFAIL\033[0m  %s — expected [%s], got [%s]\n' "$1" "$2" "$3"; fail=$((fail + 1)); fi
}
skipped() { printf '  \033[33mSKIP\033[0m  %s — %s\n' "$1" "$2"; skip=$((skip + 1)); }
code() { curl -s -o /dev/null -m 30 -w '%{http_code}' "$@"; }
codeL() { curl -sL -o /dev/null -m 30 -w '%{http_code}' "$@"; }

cleanup() {
  for id in ${created+"${created[@]}"}; do
    [ -n "$TOKEN" ] && code -H "Authorization: Bearer $TOKEN" -X DELETE "$API/files/$id" >/dev/null
  done
  rm -rf "$tmpdir"
}
trap cleanup EXIT

printf '\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\x0d\x0a-\xb4\x00\x00\x00\x00IEND\xaeB\x60\x82' > "$tmpdir/px.png"

# --- A. the bucket ------------------------------------------------------------------------------
echo
echo "A. the bucket itself"
if [ -z "$PROFILE" ] || [ -z "$BUCKET" ]; then
  skipped "bucket checks" "set AWS_PROFILE and BUCKET"
else
  BASE_OBJ="https://$BUCKET.s3.$(aws --profile "$PROFILE" configure get region).amazonaws.com"
  PROBE="s3checkPROBEs3checkPROBEaa"
  if aws --profile "$PROFILE" s3api put-object --bucket "$BUCKET" --key "$PROBE" \
       --body "$tmpdir/px.png" --content-type image/png >/dev/null 2>&1; then
    check "our own credentials can write" "yes" "yes"
  else
    check "our own credentials can write" "yes" "no"
  fi
  check "an object is readable with no credentials"  "200" "$(code "$BASE_OBJ/$PROBE")"
  # The omission that IS the security model: keys are unguessable, and a public listing hands them out.
  check "the bucket refuses to list itself"          "yes" \
    "$(curl -s -m 30 "$BASE_OBJ/" | grep -q "AccessDenied" && echo yes || echo no)"
  check "and refuses an anonymous write"             "403" \
    "$(code -X PUT --data-binary @"$tmpdir/px.png" "$BASE_OBJ/anon-probe.png")"
  aws --profile "$PROFILE" s3api delete-object --bucket "$BUCKET" --key "$PROBE" >/dev/null 2>&1
fi

# --- B. the serve path, with no session ---------------------------------------------------------
echo
echo "B. the serve path, unauthenticated"
# 404 and not 401 is the whole point: published articles and anonymous embeds are readable by people
# with no account, so their images have to be too. A 401 here means the permitAll matcher is missing.
check "an unknown key is 404, not 401"       "404" "$(code "$API/resource/aaaaaaaaaaaaaaaaaaaaaaaaaaa/x.png")"
check "a malformed key is refused"           "404" "$(code "$API/resource/short/x.png")"
check "a traversal attempt is refused"       "400" "$(code --path-as-is "$API/resource/..%2f..%2fetc/passwd")"

if [ -z "$WEB" ]; then
  skipped "the web origin" "pass it as the second argument"
else
  # Content stores a RELATIVE url, so on an environment where web and API are separate origins the
  # web vhost has to proxy /v2/resource/ to core. Without it an <img> gets the SPA's index.html —
  # a 200 full of HTML, which renders as a broken image and looks nothing like a proxy problem.
  WEBCODE=$(code "$WEB/v2/resource/aaaaaaaaaaaaaaaaaaaaaaaaaaa/x.png")
  check "the web origin proxies to core, not the SPA" "404" "$WEBCODE"
  check "and returns no HTML body"                    "0" \
    "$(curl -s -m 30 -o /dev/null -w '%{size_download}' "$WEB/v2/resource/aaaaaaaaaaaaaaaaaaaaaaaaaaa/x.png")"
fi

# --- C. the full cycle --------------------------------------------------------------------------
echo
echo "C. upload, serve, delete"
if [ -z "$TOKEN" ]; then
  skipped "the whole write path" "no token — store the client secret in keychain item '$KEYCHAIN_ITEM'"
  echo "         (this is the half that exercises the environment's own S3 credentials)"
else
  AUTH=(-H "Authorization: Bearer $TOKEN")

  # An identity that has never signed in has no `account` row, and stored_file.created_by_id is a
  # foreign key to it — so the first upload fails on fk_stored_file_owner with a 500, *after* the
  # object has already been written to S3. The SPA calls this at login; a service account never
  # logs in, so the script has to. Idempotent, so running it every time costs nothing.
  CHECKIN=$(code "${AUTH[@]}" -H 'Content-Type: application/json' -X POST "$API/account/checkin" \
              -d '{"first_name":"Storage","last_name":"Check"}')
  check "the caller has an account row" "200" "$CHECKIN"

  KEY=$(curl -s -m 60 "${AUTH[@]}" -X POST "$API/files" -F "file=@$tmpdir/px.png" \
        | python3 -c "import json,sys; print(json.load(sys.stdin).get('id','-'))" 2>/dev/null)
  if [ "$KEY" = "-" ] || [ -z "$KEY" ]; then
    check "upload returns a key" "yes" "no"
  else
    created+=("$KEY")
    check "upload returns a key"                 "yes" "yes"
    check "and it is 27 characters"              "27"  "${#KEY}"
    # 302 rather than 200 is how you know it is the s3 backend and not a local directory.
    check "core redirects rather than streaming" "302" "$(code "$API/resource/$KEY/px.png")"
    check "the redirect is cached hard"          "yes" \
      "$(curl -s -i -m 30 "$API/resource/$KEY/px.png" | grep -qi 'max-age=31536000' && echo yes || echo no)"
    check "following it gets the bytes"          "200" "$(codeL "$API/resource/$KEY/px.png")"
    check "the content type survives"            "image/png" \
      "$(curl -sL -o /dev/null -m 30 -w '%{content_type}' "$API/resource/$KEY/px.png")"
    check "anonymous readers get it too"         "200" "$(codeL "$API/resource/$KEY/px.png")"
    if [ -n "$PROFILE" ] && [ -n "$BUCKET" ]; then
      check "the object really is in the bucket" "0" \
        "$(aws --profile "$PROFILE" s3api head-object --bucket "$BUCKET" --key "$KEY" >/dev/null 2>&1; echo $?)"
    fi
  fi
fi

echo
echo "$pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ]
