#!/usr/bin/env bash
#
# Checks the article endpoints against a running core, over HTTP.
#
#   JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:bootRun --args='--server.port=8099'
#   doc/core/articles-check.sh                    # defaults to http://localhost:8099/v2
#   doc/core/articles-check.sh http://host/v2
#
# Needs a core running with `easy.core.auth-enabled: false`, because it authenticates with
# `oidc_claim_*` headers — see api-testing.md. Never point it at a deployed environment: it creates
# and deletes articles.
#
# **Why this is a shell script and not a test.** What it checks is a visibility rule that lives in a
# SQL predicate (`CachingService.selectLatestArticleVersion`), so exercising it needs a database and
# a running application. CI runs `./gradlew :core:test -PexcludeTags=db`, so a JUnit test of this
# would be written, tagged `db`, and then never run — which is worse than an honest script, because
# it looks like coverage. EZ-1715 is the issue that gives the suite a database; when it lands, this
# is the specification to port.
#
# It cleans up after itself: every article it creates is unpublished and deleted at the end, so it
# can be run repeatedly against a dev database without leaving a trail.
#
# What it covers, in the order that matters:
#   1. a draft is invisible to non-admins, and invisible in the same way a nonexistent article is
#   2. a published article is readable with no account at all
#   3. the public payload carries the rendered HTML but neither the Markdown source nor a username
#   4. the listing is admin-only
#   5. a PUT without text_md cannot blank an article
#   6. delete refuses a published article, and otherwise removes everything
#   7. an alias may contain a hyphen but not be all digits (EZ-1762)
set -u

BASE="${1:-http://localhost:8099/v2}"

ADMIN=(-H "oidc_claim_preferred_username: kspar" -H "oidc_claim_email: kspar@test.ee" -H "oidc_claim_easy_role: admin")
STUDENT=(-H "oidc_claim_preferred_username: dev-student" -H "oidc_claim_email: s@test.ee" -H "oidc_claim_easy_role: student")
TEACHER=(-H "oidc_claim_preferred_username: dev-teacher" -H "oidc_claim_email: t@test.ee" -H "oidc_claim_easy_role: teacher")
JSON=(-H 'Content-Type: application/json')

pass=0; fail=0
created=()

check() {
  if [ "$2" = "$3" ]; then printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass + 1))
  else printf '  \033[31mFAIL\033[0m  %s — expected [%s], got [%s]\n' "$1" "$2" "$3"; fail=$((fail + 1)); fi
}
code() { curl -s -o /dev/null -m 20 -w '%{http_code}' "$@"; }
body() { curl -s -m 20 "$@"; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('$1','-'))"; }
errcode() { body "$@" | field code; }

# Only echoes the id. Recording it for cleanup has to happen at the call site: this runs inside a
# command substitution, so anything it appended to `created` would be appended in a subshell and
# lost — which is exactly how the first version of this script leaked an article per run.
new_article() { # title published -> id
  body "${ADMIN[@]}" "${JSON[@]}" -X POST "$BASE/articles" \
    -d "{\"title\":\"$1\",\"text_md\":\"Hello **world**\",\"published\":$2}" | field id
}

cleanup() {
  for id in ${created+"${created[@]}"}; do
    # Delete refuses a published article, so unpublish first — which is also the flow a human takes.
    code "${ADMIN[@]}" "${JSON[@]}" -X PUT "$BASE/articles/$id" \
      -d '{"title":"tmp","text_md":"tmp","published":false}' >/dev/null
    code "${ADMIN[@]}" -X DELETE "$BASE/articles/$id" >/dev/null
  done
}
trap cleanup EXIT

if [ "$(code "$BASE/")" = "000" ]; then
  echo "No core answering at $BASE — start one first (see the header of this file)." >&2
  exit 2
fi

PUB=$(new_article "Check published" true); created+=("$PUB")
DRAFT=$(new_article "Check draft" false);  created+=("$DRAFT")
echo "using articles $PUB (published) and $DRAFT (draft)"

echo
echo "1. a draft is admin-only"
check "student is refused"                 "ENTITY_WITH_ID_NOT_FOUND" "$(errcode "${STUDENT[@]}" "$BASE/articles/$DRAFT")"
check "teacher is refused"                 "ENTITY_WITH_ID_NOT_FOUND" "$(errcode "${TEACHER[@]}" "$BASE/articles/$DRAFT")"
check "admin is not"                       "200"                      "$(code "${ADMIN[@]}" "$BASE/articles/$DRAFT")"
check "a published one is readable"        "200"                      "$(code "${STUDENT[@]}" "$BASE/articles/$PUB")"
# The point of filtering in the query rather than throwing: one code path, so these cannot drift.
check "and answers as a missing id does"   "$(errcode "${STUDENT[@]}" "$BASE/articles/99999999")" \
                                           "$(errcode "${STUDENT[@]}" "$BASE/articles/$DRAFT")"

echo
echo "2. no account at all"
check "published is readable"              "200"                      "$(code "$BASE/unauth/articles/$PUB")"
check "a draft is not"                     "ENTITY_WITH_ID_NOT_FOUND" "$(errcode "$BASE/unauth/articles/$DRAFT")"
check "the authenticated route still 401s" "401"                      "$(code "$BASE/articles/$PUB")"

echo
echo "3. the public payload"
ANON=$(body "$BASE/unauth/articles/$PUB")
has() { echo "$ANON" | python3 -c "import json,sys; print('yes' if $1 else 'no')"; }
check "no Markdown source"    "no"  "$(has "'text_md' in json.load(sys.stdin)")"
check "no username"           "no"  "$(has "'id' in json.load(sys.stdin)['author']")"
check "no published flag"     "no"  "$(has "'published' in json.load(sys.stdin)")"
check "the byline survives"   "yes" "$(has "bool(json.load(sys.stdin)['author']['given_name'])")"
check "the rendered html too" "yes" "$(has "'<strong>' in (json.load(sys.stdin)['text_html'] or '')")"
check "an admin still gets the source" "yes" \
  "$(body "${ADMIN[@]}" "$BASE/articles/$PUB" | python3 -c "import json,sys; print('yes' if json.load(sys.stdin).get('text_md') else 'no')")"

echo
echo "4. the listing is admin-only"
check "teacher is refused" "403" "$(code "${TEACHER[@]}" "$BASE/articles")"
check "admin is not"       "200" "$(code "${ADMIN[@]}" "$BASE/articles")"
check "and it carries drafts" "yes" \
  "$(body "${ADMIN[@]}" "$BASE/articles" | python3 -c "
import json,sys
print('yes' if any(not a['published'] for a in json.load(sys.stdin)['articles']) else 'no')")"

echo
echo "5. a PUT cannot blank an article"
check "text_md omitted is refused" "INVALID_PARAMETER_VALUE" \
  "$(errcode "${ADMIN[@]}" "${JSON[@]}" -X PUT "$BASE/articles/$PUB" -d '{"title":"Check published","published":true}')"
check "and the text is still there" "yes" \
  "$(body "${ADMIN[@]}" "$BASE/articles/$PUB" | python3 -c "import json,sys; print('yes' if json.load(sys.stdin)['text_html'] else 'no')")"

echo
echo "6. aliases"
check "a hyphenated alias is accepted" "200" \
  "$(code "${ADMIN[@]}" "${JSON[@]}" -X POST "$BASE/articles/$PUB/aliases" -d '{"alias":"check-how-to"}')"
check "and resolves anonymously"       "200" "$(code "$BASE/unauth/articles/check-how-to")"
# EZ-1762: an all-digit alias would shadow the article whose id it matches, since the read path
# tries the alias before parsing the segment as an id.
check "an all-digit alias is refused"  "400" \
  "$(code "${ADMIN[@]}" "${JSON[@]}" -X POST "$BASE/articles/$PUB/aliases" -d '{"alias":"2023"}')"

echo
echo "7. delete"
check "a published article is refused" "ARTICLE_PUBLISHED" "$(errcode "${ADMIN[@]}" -X DELETE "$BASE/articles/$PUB")"
code "${ADMIN[@]}" "${JSON[@]}" -X PUT "$BASE/articles/$PUB" \
  -d '{"title":"Check published","text_md":"x","published":false}' >/dev/null
check "unpublished, it goes"           "200" "$(code "${ADMIN[@]}" -X DELETE "$BASE/articles/$PUB")"
check "and is gone"                    "ENTITY_WITH_ID_NOT_FOUND" "$(errcode "${ADMIN[@]}" "$BASE/articles/$PUB")"
check "with its alias, now reusable"   "200" \
  "$(code "${ADMIN[@]}" "${JSON[@]}" -X POST "$BASE/articles/$DRAFT/aliases" -d '{"alias":"check-how-to"}')"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
