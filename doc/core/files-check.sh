#!/usr/bin/env bash
#
# Checks the file storage endpoints against a running core, over HTTP.
#
#   JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:bootRun --args='--server.port=8099'
#   doc/core/files-check.sh                    # defaults to http://localhost:8099/v2
#   doc/core/files-check.sh http://host/v2
#
# Needs a core running with `easy.core.auth-enabled: false`, because it authenticates with
# `oidc_claim_*` headers — see api-testing.md. Never point it at a deployed environment: it uploads
# and deletes files.
#
# Works against either storage backend. The file-serving assertions follow redirects, so they assert
# that the bytes arrive rather than how — `local` streams a 200 from core, `s3` answers 302 to a
# public object. Section 1 additionally checks whichever shape this backend produced.
#
# **Why a shell script and not a test.** Same reason as articles-check.sh: what it checks needs a
# database, a filesystem and a running application, so a JUnit version would be tagged `db` and
# excluded by CI — coverage that looks real and never runs. EZ-1715 is the issue that gives the
# suite a database; when it lands, this is the specification to port.
#
# It cleans up after itself: every file it uploads is deleted at the end, so it can be run
# repeatedly without leaving a trail.
#
# What it covers, in the order that matters:
#   1. a file can be fetched with no account at all — the whole point, since published articles and
#      anonymous exercise embeds are readable by people with no session
#   2. the filename in the URL is decoration and a mismatch is not an error
#   3. a key that is not the right shape is refused before anything looks it up
#   4. the metadata listing is admin-only, carries `persistent`, and can be filtered on it
#   5. the size limit applies per role
#   6. delete removes the row
set -u

BASE="${1:-http://localhost:8099/v2}"

ADMIN=(-H "oidc_claim_preferred_username: kspar" -H "oidc_claim_email: kspar@test.ee" -H "oidc_claim_easy_role: admin")
STUDENT=(-H "oidc_claim_preferred_username: dev-student" -H "oidc_claim_email: s@test.ee" -H "oidc_claim_easy_role: student")
TEACHER=(-H "oidc_claim_preferred_username: dev-teacher" -H "oidc_claim_email: t@test.ee" -H "oidc_claim_easy_role: teacher")
JSON=(-H 'Content-Type: application/json')

pass=0; fail=0
created=()
tmpdir=$(mktemp -d)

check() {
  if [ "$2" = "$3" ]; then printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass + 1))
  else printf '  \033[31mFAIL\033[0m  %s — expected [%s], got [%s]\n' "$1" "$2" "$3"; fail=$((fail + 1)); fi
}
code() { curl -s -o /dev/null -m 60 -w '%{http_code}' "$@"; }
body() { curl -s -m 60 "$@"; }
# Redirect-following variants, for the file-serving assertions. What matters there is that the
# bytes arrive, and the two storage backends get you to them differently: `local` streams a 200
# straight from core, `s3` answers 302 to a public object. Asserting the *outcome* keeps one set of
# expectations for both; the hop itself is checked separately in section 1.
codeL() { curl -sL -o /dev/null -m 60 -w '%{http_code}' "$@"; }
bodyL() { curl -sL -m 60 "$@"; }
ctypeL() { curl -sL -o /dev/null -m 60 -w '%{content_type}' "$@"; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('$1','-'))"; }
errcode() { body "$@" | field code; }

# Only echoes the id. Recording it for cleanup has to happen at the call site: this runs inside a
# command substitution, so anything it appended to `created` would be appended in a subshell and
# lost — the same trap articles-check.sh fell into first time round.
upload() { # file role-headers-var -> id
  local file=$1; shift
  body "$@" -X POST "$BASE/files" -F "file=@$file" | field id
}

cleanup() {
  for id in ${created+"${created[@]}"}; do
    code "${ADMIN[@]}" -X DELETE "$BASE/files/$id" >/dev/null
  done
  rm -rf "$tmpdir"
}
trap cleanup EXIT

if [ "$(code "$BASE/")" = "000" ]; then
  echo "No core answering at $BASE — start one first (see the header of this file)." >&2
  exit 2
fi

# A real 1x1 PNG, so Tika sniffs image/png rather than falling back to octet-stream.
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\x0d\x0a-\xb4\x00\x00\x00\x00IEND\xaeB\x60\x82' > "$tmpdir/pixel.png"

UPLOAD_RESP=$(body "${TEACHER[@]}" -X POST "$BASE/files" -F "file=@$tmpdir/pixel.png")
KEY=$(printf '%s' "$UPLOAD_RESP" | field id); created+=("$KEY")
echo "uploaded as key $KEY"

echo
echo "0. what the upload tells the caller"
# The id alone cannot build a URL: the filename is sanitised server-side and the type is sniffed, so
# a caller that guessed either would be wrong exactly when it matters.
check "the sanitised filename comes back" "pixel.png" "$(printf '%s' "$UPLOAD_RESP" | field filename)"
check "and the sniffed type"              "image/png" "$(printf '%s' "$UPLOAD_RESP" | field mime_type)"

echo
echo "1. reading it, with no account at all"
check "an anonymous request is served"      "200" "$(codeL "$BASE/resource/$KEY/pixel.png")"
check "and the bytes come back"             "PNG" \
  "$(bodyL "$BASE/resource/$KEY/pixel.png" | head -c4 | tail -c3)"
check "a signed-in one works too"           "200" "$(codeL "${STUDENT[@]}" "$BASE/resource/$KEY/pixel.png")"
check "the content type is sniffed, not claimed" "image/png" "$(ctypeL "$BASE/resource/$KEY/pixel.png")"

# How it is served differs by backend, and both shapes are correct — so assert the shape that this
# backend actually produced rather than picking one. A 302 to somewhere else is the s3 backend; a
# direct 200 is local.
FIRST_HOP=$(code "$BASE/resource/$KEY/pixel.png")
if [ "$FIRST_HOP" = "302" ]; then
  echo "   (s3 backend: core redirects to the object store)"
  HDRS=$(curl -s -i -m 60 "$BASE/resource/$KEY/pixel.png")
  check "the redirect is cached hard" "yes" \
    "$(echo "$HDRS" | grep -qi 'Cache-Control: public, max-age=31536000, immutable' && echo yes || echo no)"
  # If this ever becomes a redirect to a SIGNED url, that cache header has to go with it: a
  # year-long cache of a ten-minute URL is a broken image for the rest of the year.
  check "it points off this host"     "yes" \
    "$(echo "$HDRS" | grep -qiE '^Location: https?://' && echo yes || echo no)"
else
  echo "   (local backend: core streams the bytes itself)"
  check "served directly, not redirected" "200" "$FIRST_HOP"
fi

echo
echo "1b. what may render in a browser, and what may only download"
# Objects are public and served from the store's own origin, so an uploaded page is a page on a
# domain we do not vouch for. SVG is the same class: safe in <img>, scriptable when navigated to.
disposition() { curl -sL -o /dev/null -m 60 -D - "$@" 2>/dev/null | grep -io 'content-disposition:[^;]*' | tail -1 | tr -d '\r' | awk '{print tolower($2)}'; }
check "a png renders inline" "inline" "$(disposition "$BASE/resource/$KEY/pixel.png")"

printf '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>' > "$tmpdir/x.svg"
SVG=$(upload "$tmpdir/x.svg" "${TEACHER[@]}"); created+=("$SVG")
check "an svg is forced to download" "attachment" "$(disposition "$BASE/resource/$SVG/x.svg")"

printf '<html><body><h1>not ours</h1></body></html>' > "$tmpdir/x.html"
HTML=$(upload "$tmpdir/x.html" "${TEACHER[@]}"); created+=("$HTML")
check "and so is html"               "attachment" "$(disposition "$BASE/resource/$HTML/x.html")"

echo
echo "2. the filename is decoration"
check "a different filename still resolves" "200" "$(codeL "$BASE/resource/$KEY/something-else.png")"
# Renaming a file must not break a URL already embedded in a stored article — which is why the
# filename is never looked up.

echo
echo "3. keys that are not keys"
check "an unknown but well-formed key 404s" "404" "$(code "$BASE/resource/aaaaaaaaaaaaaaaaaaaaaaaaaaa/x.png")"
check "a short key 404s"                    "404" "$(code "$BASE/resource/short/x.png")"
# 400, not 404, and from Spring rather than from us: StrictHttpFirewall rejects an encoded slash in
# the path before any handler runs. Asserted at the code it actually returns, because "not 200" would
# pass just as happily if the firewall were switched off and this reached the controller — where
# isValidStorageKey is the thing that would then have to catch it.
check "a traversal attempt is rejected"     "400" "$(code --path-as-is "$BASE/resource/..%2f..%2fetc/passwd")"

echo
echo "4. metadata"
check "a teacher is refused"   "403" "$(code "${TEACHER[@]}" "$BASE/files/metadata")"
check "a student is refused"   "403" "$(code "${STUDENT[@]}" "$BASE/files/metadata")"
check "an admin is not"        "200" "$(code "${ADMIN[@]}" "$BASE/files/metadata")"
check "the file is listed with its size and type" "yes" \
  "$(body "${ADMIN[@]}" "$BASE/files/metadata" | python3 -c "
import json,sys
f = next((f for f in json.load(sys.stdin)['files'] if f['id'] == '$KEY'), None)
print('yes' if f and f['mime_type'] == 'image/png' and f['size_bytes'] > 0 and f['persistent'] is False else 'no')")"
check "and there is no bytea-era field" "no" \
  "$(body "${ADMIN[@]}" "$BASE/files/metadata" | python3 -c "
import json,sys
f = next(f for f in json.load(sys.stdin)['files'] if f['id'] == '$KEY')
print('yes' if ('exercise_id' in f or 'article_id' in f or 'data' in f) else 'no')")"

echo
echo "5. persistent"
check "an admin can mark it"     "200" \
  "$(code "${ADMIN[@]}" "${JSON[@]}" -X PUT "$BASE/files/$KEY" -d '{"persistent":true}')"
check "a teacher cannot"         "403" \
  "$(code "${TEACHER[@]}" "${JSON[@]}" -X PUT "$BASE/files/$KEY" -d '{"persistent":true}')"
check "it shows up in the filter" "yes" \
  "$(body "${ADMIN[@]}" "$BASE/files/metadata?persistent=true" | python3 -c "
import json,sys; print('yes' if any(f['id'] == '$KEY' for f in json.load(sys.stdin)['files']) else 'no')")"
check "and not in the inverse"    "no" \
  "$(body "${ADMIN[@]}" "$BASE/files/metadata?persistent=false" | python3 -c "
import json,sys; print('yes' if any(f['id'] == '$KEY' for f in json.load(sys.stdin)['files']) else 'no')")"
check "unmarking works"          "200" \
  "$(code "${ADMIN[@]}" "${JSON[@]}" -X PUT "$BASE/files/$KEY" -d '{"persistent":false}')"
check "an unknown id is reported" "ENTITY_WITH_ID_NOT_FOUND" \
  "$(errcode "${ADMIN[@]}" "${JSON[@]}" -X PUT "$BASE/files/nosuchfilenosuchfilenosuc" -d '{"persistent":true}')"

echo
echo "6. who may upload, and how much"
check "a student may not upload" "403" \
  "$(code "${STUDENT[@]}" -X POST "$BASE/files" -F "file=@$tmpdir/pixel.png")"
# 21 MB, one byte over the teacher ceiling of 20 MB. Admins have their own, much larger, limit —
# not exercised here because pushing a gigabyte through curl is not worth the minute it takes.
dd if=/dev/zero of="$tmpdir/big.bin" bs=1m count=21 2>/dev/null
check "a teacher is capped at 20 MB" "INVALID_PARAMETER_VALUE" \
  "$(body "${TEACHER[@]}" -X POST "$BASE/files" -F "file=@$tmpdir/big.bin" | field code)"
: > "$tmpdir/empty.bin"
check "an empty file is refused"     "INVALID_PARAMETER_VALUE" \
  "$(body "${TEACHER[@]}" -X POST "$BASE/files" -F "file=@$tmpdir/empty.bin" | field code)"

echo
echo "7. delete"
GONE=$(upload "$tmpdir/pixel.png" "${TEACHER[@]}")
check "a teacher cannot delete"  "403" "$(code "${TEACHER[@]}" -X DELETE "$BASE/files/$GONE")"
check "an admin can"             "200" "$(code "${ADMIN[@]}" -X DELETE "$BASE/files/$GONE")"
check "and the file is gone"     "404" "$(code "$BASE/resource/$GONE/pixel.png")"
check "deleting it twice is reported" "ENTITY_WITH_ID_NOT_FOUND" \
  "$(errcode "${ADMIN[@]}" -X DELETE "$BASE/files/$GONE")"
# The object itself is deliberately NOT gone yet: StoredFileSweep is the only thing that removes
# anything from storage, and it collects this on its next run as an object with no row. Asserting
# that from here would mean reaching into the storage directory, which is the backend's business.

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
