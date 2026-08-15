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
(`teacher,admin`).

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

## Checks kept as scripts

`articles-check.sh` and `files-check.sh` in this directory are the pattern written down: a run of
curl calls against a local core, asserting one endpoint family end to end, cleaning up after itself
so it can be run repeatedly.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:bootRun --args='--server.port=8099'
doc/core/articles-check.sh
doc/core/files-check.sh
```

`files-check.sh` runs against either storage backend and says which it found; `s3-setup.md` in this
directory covers pointing a core at a real bucket.

They exist because of what they check. `articles-check.sh` checks whether an unpublished article is
invisible to non-admins and to the internet — a rule that lives in a SQL predicate.
`files-check.sh` checks that an uploaded file is fetchable with no session at all, which is a
filesystem, a database and a security filter chain agreeing. Both need a running application, and CI
runs `./gradlew :core:test -PexcludeTags=db`, so the JUnit version of either would be written,
tagged, and then never run. A script that is honestly manual beats a test that looks like coverage
and is skipped.

**What a script cannot replace.** `RichTextColumnsTest` is deliberately *not* one of these: it
guards the list of columns the stored-file sweep scans, and getting that wrong deletes files that are
in use. So it is written to need no database — reflection over the Exposed table objects rather than
a query against `information_schema` — precisely so that it runs on every push. When a check has to
run, that is the shape to reach for; a script is for what genuinely cannot.

**EZ-1715 is the issue that gives the suite a database.** When it lands, these scripts are the
specification to port, not work to redo.

Worth copying if you write another: assert the *absence* of things as well as their presence (that
the public payload has no `text_md` and no username is half of what makes it correct), and assert
that two different failures answer identically where that is the design — a draft and a nonexistent
id return the same error on purpose, and a test that only checks "not 200" would not notice them
drifting apart.

## What this doesn't cover

Nothing here exercises real Keycloak or token verification (`EasyUserJwtConverter`) — the
production auth path is entirely bypassed. It validates service and query behaviour.
