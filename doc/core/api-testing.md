# Calling core directly, and A/B-ing a backend change against a running instance

Two things that are not obvious from reading the code, both useful for checking a backend
change actually behaves as intended rather than merely compiling.

For the frontend equivalent, see `doc/web/browser-testing.md`.

## Authenticating without Keycloak

`application.yaml` ships with `easy.core.auth-enabled: false`, and `SecurityConf` reads that
to install `DummyZeroAuthFilter` instead of building a JWT resource server. That filter builds an
`EasyUser` straight from request headers, so locally you can be anyone with curl:

```sh
curl -s \
  -H "oidc_claim_preferred_username: dev-student" \
  -H "oidc_claim_email: student@test.ee" \
  -H "oidc_claim_easy_role: student" \
  http://localhost:8080/v2/student/courses
```

`preferred_username`, `email` and `easy_role` are all required — omit any one and the filter
skips authentication entirely and you get a 401. `oidc_claim_given_name` and
`oidc_claim_family_name` are optional. `easy_role` is comma-separated for multiple roles
(`teacher,admin`); surrounding whitespace is trimmed, and a value carrying no role at all
(`,`) is treated as missing, so you get the 401 rather than a user with no permissions. An
`easy_role` naming something unmappable (`wizard`) is also a 401, with the reason in core's log.

**Keep these values ASCII.** `StrictHttpFirewall` refuses any request whose header value contains a
control character, and a non-ASCII name becomes one on the way in: request headers are decoded as
ISO-8859-1, so the UTF-8 bytes of `Ü` (`0xC3 0x9C`) arrive as `Ã` followed by U+009C, a C1 control.
The request is refused with a bare 400 that says nothing about names. That is the whole of EZ-1434,
which was originally worked around by disabling the firewall's header-value check globally — a
production control switched off for a testing convenience. The names here are arbitrary, so a test
user called `Ulo` proves everything one called `Ülo` would; if you genuinely need a non-ASCII display
name, exercise it through a JWT claim against a real IdP instead (`core/dev-idp/`).

`web/vite.config.ts` fabricates these same headers from the SPA's bearer token when proxying
`/v2`, so browser-based local dev works against an auth-disabled core with no IdP — that file is
where to look if the header names ever drift.

These headers are a **local-dev mechanism only**. Deployed environments set
`easy.core.auth-enabled: true`, and core then verifies the Keycloak access token itself against
the realm's JWKS (`core/conf/security/EasyUserJwtConverter.kt`), ignoring these headers entirely.
Apache in front of core is a plain reverse proxy. Core used to trust `oidc_claim_*` headers set by
mod_auth_openidc in production too, which is why this file previously described them as the
production mechanism — see EZ-1724.

Test accounts from the test data: `dev-student` (student), `dev-teacher` (teacher), `kspar`
(all three roles).

> Because this is a header, anything that can reach a core running with `auth-enabled: false`
> can impersonate any user. It's a local-dev-only setting — never expose such an instance,
> and never set it on a deployed environment.
>
> Core enforces this rather than trusting the warning: with auth disabled it refuses to start
> unless `server.address` is a loopback address. If you get an `IllegalStateException` on
> startup saying so, add `server.address: 127.0.0.1` to your `application.yaml`.

## Authenticating against a deployed environment

The header trick above does not work anywhere real — deployed environments verify a Keycloak token
against the realm's JWKS and ignore headers entirely. For scripted checks against dev, the answer is
a **service-account client**: the durable credential is a rotatable client secret, and what it mints
is a ten-minute token. A long-lived bearer token would be the wrong trade — it is the whole
credential, it cannot be rotated, and there is nothing to revoke.

Dev has `easy-dev-test-runner` (client authentication on, *Service accounts roles* on, standard flow
off — it is not a login client). Its secret lives in the login keychain, the same convention
`ansible/run.sh` uses for the become password:

```sh
security add-generic-password -a "$USER" -s easy-dev-test-token -T /usr/bin/security -U -w
curl -s -d grant_type=client_credentials -d client_id=easy-dev-test-runner \
  --data-urlencode "client_secret=$(security find-generic-password -a "$USER" -s easy-dev-test-token -w)" \
  https://dev.idp.lahendus.ut.ee/auth/realms/master/protocol/openid-connect/token
```

`s3-check.sh` does this itself, so normally you never touch it.

**Three claims or nothing.** `EasyUserJwtConverter` requires `preferred_username`, `email` and
`easy_role`, and treats a verified token missing any of them as an *invalid* token — a 401 that
reads like a bad secret rather than a misconfigured mapper. A service account has no email, so both
`email` and `easy_role` want **hardcoded-claim mappers** on the client's dedicated scope;
`easy_role` must be JSON type with an array value, drawn from exactly `student`, `teacher`, `admin`.

**And it has to check in before it can own anything.** A brand-new identity has no `account` row.
`stored_file.created_by_id` is a foreign key to it, so the first upload fails on
`fk_stored_file_owner` — with a 500, and *after* the object has already been written to S3, leaving
an orphan for the sweep. The SPA calls `POST /v2/account/checkin` at login; a service account never
logs in, so a script has to. It needs `{"first_name": …, "last_name": …}` and is idempotent.

## A/B-ing old code against new

The most convincing check for a behavioural change is running both versions against the same
database at once. If a core is already running on 8080 with the unmodified code, **leave it
running** and start the patched build alongside it:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:bootRun --args='--server.port=8081'
```

Then probe the same endpoint on both ports and compare status codes. This turns "I believe
this fixes it" into a table:

```
  exact case                                old:200   new:200
  UPPERCASED                                old:400   new:200
```

Why a second instance on the same database is safe here:

- The destructive crons (`pending-access.clean`, `keycloak` inactive-user deletion,
  `exercise-index-normalisation`) are all pinned to `0 0 5 31 2 ?` — **February 31st**, a
  date that never occurs. Check `application.yaml` before relying on this; the Moodle user
  sync is a real daily `0 5 4 * * *`, though its URL is an unconfigured placeholder.
- The `testdata.xml` changesets guard on `sqlCheck` preconditions, so a second startup marks
  them as ran instead of re-inserting.
- Boot takes ~10s. The log line to wait for is `Started EasyCoreAppKt`, not
  `Started ...Application`.

Kill your own instance by PID when done. Don't kill the one you didn't start.

## Exercising a destructive path reversibly

To test something that deletes or mutates, insert a throwaway row with a deliberately
distinctive key, act on that, then assert on both the row *and* its neighbours:

```sh
# a pending access whose invite id has mixed case on purpose
INSERT INTO student_moodle_pending_access (course_id, moodle_username, email, created_at, invite_id)
VALUES (9003, 'moodle-casetest', 'casetest@test.ee', now(), 'CaseTest123');
```

Then join via `CASETEST123`, confirm the row is gone — and separately confirm the four real
pending students on that course are still there, that no pre-existing access row was
modified, and that no stray join-table rows appeared. The collateral check is the point: a
query that deletes too much passes the narrow assertion.

Pick a fixture that makes the side effects no-ops where you can. Joining as a student who
already has access to the course means the `insertIgnore` does nothing, so the only mutation
left is the one under test.

## The checks that used to be scripts

`articles-check.sh` and `files-check.sh` lived in this directory until 2026-08-16. They are now
`ArticleApiTest`, `FileApiTest` and `StorageServiceContractTest`, and the scripts were deleted in the
same commit.

That was always the plan and the scripts said so in their own headers. The argument for keeping them
was that both need a running application, that CI ran `-PexcludeTags=db`, and that a JUnit version
would therefore be written, tagged, and never run — a script that is honestly manual beats a test
that looks like coverage and is skipped. **EZ-1715 removed the premise.** CI has a database,
`./gradlew build` runs everything, and nothing is tagged out.

Keeping both would have been the worst of the three options: two specifications of the same rules,
one of which drifts silently because nobody runs it on a schedule.

| was | is | runs |
| --- | --- | --- |
| `articles-check.sh`, 26 curl assertions | `core/ems/service/article/ArticleApiTest.kt`, 18 tests | every push |
| `files-check.sh`, 34 curl assertions | `core/ems/service/file/FileApiTest.kt`, 19 tests | every push |
| `files-check.sh` against whichever backend | `StorageServiceContractTest`, 15 runs over both | every push, MinIO for the S3 half |

Those counts come from `core/build/test-results/test/*.xml`, not from counting `@Test` — the storage
one is 9 annotations and 15 runs, and an annotation count would understate it while looking like a
measurement. The `curl` figures are `grep -c '^\s*check '` against the scripts at their last commit.

Three things the port gained, all of them cache or ordering behaviour a script running against a
long-lived core could not see: that an anonymous reader and a signed-in non-admin get **byte-identical**
article payloads (they share one cache entry, so if they ever differ, request order becomes the
access control); that an admin reading first does not leave the Markdown source in the entry the next
anonymous caller reads; and that publishing takes effect immediately.

One thing it deliberately did not gain: **whether a stored object is actually readable by an
anonymous caller.** That is a bucket policy, set out of band per environment, and asserting it
against MinIO would answer a question about MinIO.

### `s3-check.sh` stays a script permanently

It asks whether *this environment's* bucket, credentials and proxying are wired up, which is
unanswerable from CI by construction. It belongs to EZ-1710's post-deploy story.

```sh
AWS_PROFILE=easy-dev-test BUCKET=lahendus-dev-files \
  doc/core/s3-check.sh https://dev.ems.lahendus.ut.ee/v2 https://dev.lahendus.ut.ee
```

Its three sections skip cleanly when their prerequisites are missing, so a partial run reports as
partial rather than as green. The upload section needs `EASY_TOKEN`, because a deployed environment
has real authentication and the `oidc_claim_*` trick does not work there. `s3-setup.md` covers
building the bucket in the first place.

**Do not run it, or anything like it, in CI.** It would need a core started with
`auth-enabled: false`, which would make the one code path that must never run anywhere real
load-bearing for the release gate. That is also why the tests above authenticate with
`core/testing/Auth.kt` — a real `EasyUser` in the security context — rather than with `oidc_claim_*`
headers.

### Worth copying if you write another

- **Assert the absence of things as well as their presence.** That the public article payload has no
  `text_md` and no username is half of what makes it correct.
- **Assert that two different failures answer identically where that is the design.** A draft and a
  nonexistent id return the same error on purpose; a test that only checks "not 200" would not
  notice them drifting apart.
- **Assert the request, not only the result.** `AutoGradeIntegrationTest` checks what core sends the
  executor, because a grading request carrying the wrong solution still produces a perfectly good
  grade — it would be the student's result that was wrong.
- **A structural guard and a behavioural one catch different things.** Widening the file-serving
  permitAll pattern from `/*/resource/*/*` to `/*/resource/**` was measured: `EndpointSecuritySurfaceTest`
  passes, and only `FileApiTest`'s "a deeper path is not public" notices.

**What a script cannot replace.** `RichTextColumnsTest` is deliberately not one of these: it guards
the list of columns the stored-file sweep scans, and getting that wrong deletes files that are in
use. So it is written to need no database — reflection over the Exposed table objects rather than a
query against `information_schema` — precisely so that it runs on every push. `StoredFileSweepTest`
is the other half, guarding what the sweep then *does*.

## Recompiling TSL exercises after a compiler change

A TSL exercise is compiled **once, at save time**, and the result is stored as a `generated_0.py`
asset. So a fix to the compiler reaches nothing that already exists — every exercise keeps grading
with the output of whichever compiler was running the day a teacher last pressed save. EZ-1774 is
the demonstration: a defect that made every check dictionary unusable sat in the compiler for nine
days, and fixing it did nothing for the exercises already compiled by it.

```sh
# What would change. Writes nothing.
curl -s -X POST "$BASE/admin/exercises/tsl/recompile" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $EASY_TOKEN" -d '{}' | jq

# Do it.
curl -s -X POST "$BASE/admin/exercises/tsl/recompile" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $EASY_TOKEN" \
  -d '{"apply": true}' | jq '{scanned, changed, unchanged, failed}'

# One exercise, or a few.
  -d '{"apply": true, "exercise_ids": [1001, 1002]}'
```

Admin only. `apply` defaults to false and the response is the same shape either way, so the dry run
is a rehearsal of the write rather than a different code path describing it.

**What it deliberately does not do is create a version.** Re-saving through `PUT /v2/exercises/{id}`
or the admin `rewrite` variant both add an `exercise_version` row, and a compiler fix is not an edit
by the teacher — a version chain full of entries nobody authored is a worse record of what teachers
did than one with gaps. This replaces the generated assets on the existing `automatic_exercise` row
and leaves `tsl.json`, the version chain and every asset the teacher wrote alone.

Three other properties worth knowing, each pinned by a test that was made to fail on purpose:

- **Current versions only** (`valid_to IS NULL`). A superseded version's stored script is the record
  of what that version generated; rewriting it would be editing history to say something that was
  never true, and it can never run again anyway.
- **A spec that no longer compiles keeps the script it has.** Reported as `FAILED` with the reason.
  Grading with a stale script is bad; grading with none is worse, and a bulk run could otherwise
  reach that state for hundreds of exercises at once.
- **Identical output writes nothing**, including the `meta.txt` timestamp — otherwise every run
  would report every exercise as changed and the dry run would stop being worth reading.

## What this doesn't cover

Nothing here exercises real Keycloak or token verification (`EasyUserJwtConverter`) — the
production auth path is entirely bypassed. It validates service and query behaviour.
