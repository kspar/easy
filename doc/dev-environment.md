# Dev Environment Plan

A permanent dev deployment of Lahendus with **real auth, real users, and a real executor**,
safe to point testers at and safe to redeploy from master several times a day.

Decisions already made (2026-07-31): data is a **one-time anonymised prod import** that then
drifts; CI builds artifacts and a **deploy script fetches them**; the dev Keycloak has
**registration disabled**, accounts created by an admin; the host is **greenfield**, no existing
Ansible or VM to inherit.

Done since (EZ-1724): core verifies Keycloak JWTs itself rather than trusting reverse-proxy
headers. This was deliberately landed before building dev, because it removes most of what the
proxy config would otherwise have to get right — see §2.

---

## 0. Rules

**If you are changing something on dev and reading nothing else, read this.** Each line is
the whole rule; the linked section has the reasoning.

Two are enforced — the code stops you:

- **Auth stays on.** `easy.core.auth-enabled: false` makes core trust `oidc_claim_*` headers, i.e.
  anyone is admin. Core refuses to start with it false on a non-loopback address. (§2)
- **Anonymisation only runs on a dev-named database.** The scripts refuse anything not matching
  `dev`/`stage`/`anon`. Aimed at prod they would rename every user. (§3.2)

The rest are on you:

- **Never run `./gradlew test` on the host, and never put `core/src/test/resources/application.yaml`
  there.** It runs Liquibase `dropAll()`. (§3.5)
- **Nightly `pg_dump`.** The data drifts and cannot be regenerated from prod. (§3.5)
- **Moodle sync must not reach real Moodle.** Dead URLs, pinned crons, an empty-means-unrestricted
  `course-allowlist`, and anonymisation clearing every `moodle_short_name`. Four locks because the
  obvious ones miss the real path: **grades have no cron and need no endpoint** — ordinary grading
  pushes them, so a tester submitting one solution is enough. (§5)
- **A production import brings production's `executor` rows with it.** They must be deleted before
  core is allowed to start, or a tester's submission is dispatched to a production grader.
  `import-prod-dump.yml` does it; a hand-run `pg_restore` does not. (§3.6, §5)
- **`keycloak.cron` pinned to the never-date, and dev's Keycloak service account has no
  user-delete permission.** Otherwise the first run deletes a slice of the import, in the DB and
  the IdP. (§5)
- **`keycloak.base-url` is the dev IdP.** Pasting prod's value here is the worst mistake available
  on this host. (§5)
- **Mail goes to a local catch-all**, so it stays testable and cannot escape. (§5)
- **Dev realm: registration disabled, accounts admin-created, `easy_role` mapped.** (§7)
- **Core, postgres and the executor bind loopback only; the executor runs non-root.** (§2, §6)
- **The proxy serves `config.json` with `Cache-Control: no-store`.** A cached one points a fresh
  deploy at the previous environment's backend. (§4.1)

Not a rule but the usual first stumble: dev needs **its own `application.yaml`** (four keys are
new in v4.0 — see `doc/release-procedure.md`) **and its own `config.json`**. Every environment
keeps its own config outside the repo; the prohibition above is about *test* config specifically.

---

## 1. Topology

One new VM, plus the existing IDP elsewhere:

```
                    ┌──────────────────────────────────────────────┐
  browser ────────► │  dev VM                                  │
                    │                                              │
   dev.lahendus     │   nginx :443                                 │
       .ut.ee ──────┼──►  vhost 1: static web dist (docroot)       │
                    │     vhost 2: OAuth2 resource server          │
   dev.ems.lahendus ┼──►             └─► 127.0.0.1:8080  core      │
       .ut.ee       │                        │                     │
                    │                        ├─► 127.0.0.1:5432    │
                    │                        │      postgres       │
                    │                        └─► 127.0.0.1:5111    │
                    │                               executor       │
                    │                                 └─► docker   │
                    └──────────────────────────────────────────────┘
                                    │ OIDC / admin REST
                                    ▼
                        dev.idp.lahendus.ut.ee (existing, separate host)
```

### Domains

| Host | Serves | Notes |
| --- | --- | --- |
| `dev.lahendus.ut.ee` | web dist | Already in core's CORS allowlist — see §4.3 |
| `dev.ems.lahendus.ut.ee` | core API | Already resolves to the VM |
| `dev.idp.lahendus.ut.ee` | Keycloak | CNAME to `easy-idp-dev.cloud.ut.ee`, its own VM — see §7 |

**Decided 2026-08-03: keep `dev.ems`, drop the planned `dev.core`.** This document previously
recommended renaming to `dev.core.lahendus.ut.ee`, on the grounds that "ems" is the pre-rename
backend name. Three facts argue the other way:

- `dev.ems.lahendus.ut.ee` **already resolves to 193.40.11.202**, and `dev.core` has no A record at
  all. Using the existing name removes a dependency on UT IT from the critical path.
- **Production still serves `ems.lahendus.ut.ee`** (a CNAME to `lahendus.ut.ee`). Dev is meant
  to be the release gate, so it should mirror production's names rather than invent a third
  convention that exists nowhere else.
- Renaming is not blocked by this. If `ems` → `core` is worth doing, it is worth doing in both
  environments at once, as its own piece of work — and doing it on dev first would only prove
  that a name production does not use works.

So: no new A records needed, and TLS for two names that already point here.

---

## 2. Auth model

Core verifies Keycloak access tokens itself — Spring Security resource server, signature checked
against the realm's JWKS, issuer and expiry validated, claims mapped to `EasyUser` in
`core/conf/security/EasyUserJwtConverter.kt`. The proxy is a plain TLS terminator and reverse proxy
with no auth role at all, and the SPA already sends `Authorization: Bearer`.

This was not true when this plan was first written (EZ-1724 changed it), and the difference is
most of why the proxy config in §4 is short. Previously core read identity *and roles* from
`oidc_claim_*` request headers and decoded the token without verifying its signature, which made
the proxy the entire security boundary: it had to strip client-supplied claim headers, and core had
to be unreachable directly or anyone could assert `oidc_claim_easy_role: admin`.

Two things still worth doing, now as defence in depth rather than as the only line of defence:

1. **Keep core on loopback.** `server.address: 127.0.0.1` is already the sample default. Firewall
   the box to 443 + SSH.
2. **Never set `easy.core.auth-enabled: false` here.** That installs `DummyZeroAuthFilter`, which
   trusts `oidc_claim_*` headers verbatim — the old behaviour, retained deliberately for local dev
   and curl-based API testing (`doc/core/api-testing.md`). On a reachable host it is an open door.

Core needs the realm's JWKS URL in its config; see the `spring.security.oauth2.resourceserver`
block in `core/src/main/resources/application.yaml.sample` for why both `jwk-set-uri` and
`issuer-uri` are set rather than `issuer-uri` alone.

---

## 3. Data

### 3.1 What the import is

A single `pg_dump` of prod, anonymised, restored into the dev postgres once at setup. After
that dev is its own world: testers' courses and submissions accumulate and are never
overwritten by a refresh.

**The database is no longer the whole of the data.** Since EZ-1571 uploaded files live in an S3
bucket and `stored_file` holds only metadata, so a dump carries the rows and not the bytes. Dev has
its own bucket — it must, because the nightly sweep deletes objects with no row in *this* host's
database, and an imported database does not know about anything production uploaded since. Expect
imported content to have broken images, and see `doc/core/s3-setup.md`.

### 3.2 How the anonymisation runs

Three SQL scripts in `doc/core/anonymise-db/`, run against the restored copy — see the README
there for the runbook and the full per-table table. In short: `anonymise.sql` is required,
`strip-teacher-feedback.sql` is recommended, `strip-submissions.sql` is optional.

They replaced `anonymise-dump.py` (EZ-1725), which rewrote dump *text* and located the account
rows by matching an exact `COPY public.account (...)` header. That header listed six columns and
`account` now has thirteen, so it raised `ValueError` before touching anything — confirmed against
a real `pg_dump`. It also had a hard ceiling of exactly 3190 accounts, `PSEUDO_PAIRS` being set to
the whole `11 colours × 290 birds` product with no slack.

Operating on the database instead means column order cannot break it, and each pass ends with
assertions that print `0` when they hold.

Each script **refuses to run** unless the database name contains `dev`, `stage` or `anon`.
Pointed at production, `anonymise.sql` would rename every real user and delete every live
invitation, so that guard is the difference between a scripted mistake and a catastrophe.

Anonymise after restore and before anything but your own session can reach the database.

### 3.3 The decision that needs a human

Most of the per-table calls are obvious and are made in `anonymise.sql`: Moodle usernames nulled,
pending invitations and live invite tokens deleted, client error reports dropped, pseudonyms
regenerated and remapped through `stats_submission` (which stores pseudonyms rather than account
ids, so it goes stale otherwise).

Two are trade-offs rather than clear calls, which is why they are separate scripts:

- **Teacher feedback** (`teacher_activity`, `teacher_inline_comment`) is the most sensitive content
  in the database, and pseudonymising the account it points at does not anonymise it: "you have
  failed this three times now, come see me" is about a real person and identifiable to anyone who
  knows the course and the dates. Against that, teacher grading UI is exactly what wants realistic
  feedback threads. `strip-teacher-feedback.sql` keeps the grades and drops only the prose.
- **Student submissions** carry name headers and comments. Keeping them is what makes grading,
  plagiarism comparison and auto-assessment worth testing on dev; `strip-submissions.sql` is
  there if the host ends up shared more widely than the team.

Because the import happens once and then drifts, both are decided once. Decide before the data
lands.

**Decided 2026-08-10: `anonymise.sql` only.** Teacher feedback and student submissions both stay, on
the grounds that grading UI, plagiarism comparison and auto-assessment are most of what dev exists to
test and none of them are worth much against placeholder text. The consequence is written down rather
than waved past: this host holds real teacher prose about real students *and* executes arbitrary
student code in Docker containers on the same VM (§6), so a container escape reaches that data. That
trade was acceptable for a database of pseudonymised grades; it is a thinner margin now. If dev is
ever shared past the team, `strip-teacher-feedback.sql` and `strip-submissions.sql` are still there
and still work — on the current schema, which is what the database will have by then.

### 3.4 Usernames, and how testers get in

The dev Keycloak has registration disabled and accounts created by an admin. That gives a clean
model:

- Restore preserves `account.username`. Those rows are **inert** — nobody can log into them,
  because no matching user exists in the dev realm.
- A tester logs in with an admin-created dev-realm account. On first login core creates a *new*
  account row for them, with no courses.
- To test as a teacher with real course data, an admin creates a dev-realm user whose **username
  matches** a teacher row in the imported data. That tester then inhabits that (anonymised)
  teacher's courses. This is the deliberate, auditable way to get realistic access, and it is the
  reason to keep usernames rather than scramble them.

Roles come from the `easy_role` claim, so the dev realm needs that claim mapped for each account —
one of the realm-config items in §7.

### 3.5 Backups are now mandatory

Because dev drifts and is never re-imported, tester-created state is unique and unrecoverable.
Nightly `pg_dump` to a second location, 7–14 days retention. This is a direct cost of the
drift choice and easy to forget.

**Half done, 2026-08-10.** `ansible/roles/postgres` now installs `easy-db-backup.timer` — 03:30
nightly, `Persistent=true` so a host that was down still takes the missed dump, 14 days retention,
pruning only after a dump succeeds so a week of failures cannot quietly eat the history it exists to
protect. The dump is written to `.partial` and renamed only once `pg_restore --list` has parsed it,
because a half-written file that looks like a backup is worse than an obviously missing one.

Nothing scheduled was running until then: `/srv/easy/db-dumps` existed and held two dumps somebody
had taken by hand, which is the state that looks like a working backup and is not one.

**Still on-host only**, which is the half that is missing. This protects against the failure dev
will actually have — a bad import, a changeset that eats a column, someone clearing a table — and
not against losing the VM. `postgres_backup_dir` is a plain directory so that copying it elsewhere
is a later addition rather than a rewrite.

Also: never place a `core/src/test/resources/application.yaml` on this host and never run
`./gradlew test` there. `InitTestDatabase` calls Liquibase `dropAll()`. It is not in the bootJar
and `assertDisposableDatabase()` fails closed on non-local hosts and non-`_test` database names
(see DEVELOPMENT.md §4), so this is defence in depth rather than a live risk — but the host should
not have the test source set on it at all, which the artifact-based deploy in §8 gives us for free.

### 3.6 What the import brings that has to be cut

`ansible/import-prod-dump.yml` is the runbook above made executable — backup, stop core, drop,
restore, cut, anonymise, start, re-register, verify — and it exists because three of those steps are
not obvious from "restore a dump and anonymise it".

A dump is the whole database, including rows that describe the *environment* rather than its content:

- **`executor` and `executor_container_image`.** Production's graders, with production's URLs. Core
  reconciles this table into its scheduler on a timer (`syncExecutorsFromDB`), so a dev tester's
  submission would be POSTed to a production executor — unauthenticated, since `callExecutor` sends
  no credentials. The playbook deletes them between the restore and core's first start, then re-runs
  the executor role's `register_in_db.yml` to put dev's own row back. That file was split out of the
  role for this: re-registering should not mean rebuilding four Docker images.
- **`course.moodle_short_name` and the `moodle_sync_*` flags.** Production's links to production's
  Moodle courses. Cut by `anonymise.sql` rather than by the playbook, because they belong to the
  same pass that removes the other identifiers — but they are on this list, not the privacy list:
  a shortname is not personal data, it is what makes a course *reachable*. Left in place, the
  student-sync cron would iterate every real course, and — since grades are pushed by ordinary
  grading rather than by a cron — a tester submitting one solution would write into a real
  gradebook. `doc/core/anonymise-db/README.md` has the full argument.
- **`databasechangelog`.** Production's — and this turned out to be the interesting one. See below.

The testdata rows themselves go: exercise 9001 and the three test accounts are replaced by real
ones, which is the point. `grading_check_exercise` in the dev inventory named 9001, though, and
pointing it at an imported exercise would have been worse than leaving it broken — `grading-check.yml`
rewrites its target's grading script and switches on anonymous submission, so it would have quietly
vandalised somebody's coursework and made it gradeable without a token. The check now creates and
finds its own fixture exercise by title, at whatever id the sequences give it, and the inventory
names no id at all.

#### The testdata changesets were never dev-only

Restoring production's `databasechangelog` was expected to mark the `testdata-*` changesets as
already run. It did the opposite: **production had never run them.** They are part of the same
changelog as the schema (`changelog.xml` includes `changesets/testdata.xml` unconditionally) and were
added after production's last deploy, so Liquibase treated them as pending and tried to apply
fixtures at hardcoded ids in the 9000s to a database now full of imported rows. Two were skipped by
their own preconditions — production has a `kspar` account — and `testdata-exercises` failed on a
duplicate `exercise_version` id. A failed changeset means core does not start, which is what dev did,
176 restarts deep.

**The same would have happened on the next production deploy.** Dev was supposed to be the release
gate and this is the first thing it caught, which is an argument for the whole environment.

Fixed at the source: every changeset in `testdata.xml` now declares `context="testdata"`, and
`DatabaseConf.kt` activates that context only when `easy.core.db.test-data` is true, defaulting to
false. The context it passes otherwise is a non-empty placeholder rather than the empty string,
because Liquibase reads "no contexts given" as "run everything" — which is the behaviour being
prevented. `application.yaml.sample` sets the key to true, so a local database still seeds itself.

Dev's own database was unblocked by marking the nine outstanding changesets `MARK_RAN`, which is what
their preconditions would have done had they matched. The deployed jar predates the fix; once a build
carrying it is deployed, those rows are inert.

#### And the migration was destroying teacher feedback

The second thing the import caught, and the more expensive one. `teacher_activity` arrived with
several thousand rows, most of them carrying `feedback_adoc`. After core migrated the schema
forward: the same rows, the same grades, and **zero** feedback texts. The migration reported success.

Three changesets in `v4.xml`, each defensible alone:

1. `220226-1` copies `feedback_adoc` and `feedback_html` into a new jsonb `feedback` column.
2. `220226-2` drops both source columns.
3. `260225-1` drops `feedback` and adds empty `feedback_md` / `feedback_html` — commented
   *"JSONB was never deployed to prod"*.

That comment is true, and it is the bug. It reads as "no deployed database has this column, so
dropping it loses nothing", but Liquibase applies every pending changeset in order: a database that
had never run step 1 runs all three on the next deploy, so the data goes into the jsonb column and is
then dropped with it. The only databases safe from this were the ones that had *already* run step 1 —
the developer laptops the reasoning was formed on.

**This would have hit production on its next deploy**, on exactly those rows. Dev existing is the
only reason it was found first, and finding it cost nothing but a restore.

`260225-1` now copies the jsonb values into the text columns before dropping, and `220226-1` widens
its predicate so a row with rendered HTML and no source is not silently lost either. Both carry
`<validCheckSum>ANY</validCheckSum>`, because dev and every local database already ran the old
bodies. Dev's own feedback was restored from the dump, which was still the only remaining copy —
the decision in §3.3 was to keep it, and a migration is not entitled to overrule that.
- **`system_configuration`.** Production's settings, kept deliberately: they are what production runs
  with, and dev is meant to be the release gate.

The ordering constraint that follows from all this: **anonymise before core ever starts.** A dump is
behind master by definition — dev runs master, production does not — so the restored schema is
production's, and core's first start is what migrates it forward. Anonymising first means no core
process ever connects to real names. The cost is that `anonymise.sql` has to tolerate a schema older
than the one it was written against, which it now does: the 14.x production schema had
`teacher_activity.feedback_adoc` rather than `feedback_md` and no `teacher_inline_comment` table at
all, and the script's closing report named both. It aborted *after* the anonymisation had committed,
so psql exited non-zero with none of its assertions printed — which reads as "the anonymisation
failed" when in fact only the receipt for it did.

One thing the playbook cannot do for you: it refuses to run twice without `-e import_confirmed=true`.
dev is imported once and then drifts, so a second import is not a refresh, it is deleting everything
testers have built since the first one.

---

## 4. The reverse proxy

**nginx, decided 2026-08-04 — this section used to say Apache.** Apache was the plan's choice because
`mod_auth_openidc` made it the security boundary. EZ-1724 moved JWT verification into core, so the
proxy authenticates nothing and what is left is TLS termination, a static directory and one
`proxy_pass`. nginx is the simpler of the two to write and read for that job.

The trade, stated so nobody is surprised by it: **production runs Apache/2.4.52**, so until the same
role reaches production, dev and production differ in the component terminating TLS. That is a
narrow class of difference now that the proxy does nothing clever, but it is not zero — and dev is
supposed to be the release gate. The intent is that the nginx role replaces prod's hand-built Apache
when these playbooks get there, rather than the two diverging permanently.

Built by `ansible/roles/nginx`. Certificates come from Let's Encrypt over HTTP-01 with
`certbot certonly --webroot` — deliberately not the nginx plugin, which would rewrite the site config
the role owns. One certificate carries both names as SANs, so there is one expiry to watch, and a
renewal hook reloads nginx (without it, renewal succeeds quietly and nginx keeps serving the old
certificate until something happens to reload it — possibly after it expired).

### 4.1 Web vhost — `dev.lahendus.ut.ee`

Static hosting of the built `dist/`, plus an SPA fallback so deep links work, plus one required
header:

```nginx
root /srv/easy/web/current;

# config.json carries this environment's backend and realm (EZ-1726). It MUST NOT be cached: the app
# requests it with `cache: 'no-store'`, but a caching layer in front would hand a freshly deployed
# dist the previous environment's backend URL. That failure presents as "dev is talking to
# production", which is not where anyone looks first.
location = /config.json {
    add_header Cache-Control "no-store" always;
    try_files $uri =404;
}

location / {
    try_files $uri $uri/ /index.html =404;
}
```

Two things about that last line, both learned by getting them wrong:

- **The trailing `=404` matters.** Without it, a request that falls through to `/index.html` when
  that file does not exist — the state of the host until the first deploy — re-enters the same
  location and nginx gives up with "rewrite or internal redirection cycle". That is a 500 that reads
  as a broken proxy rather than as an empty docroot.
- **`http2 on;` is nginx 1.25.1 and later.** Ubuntu 24.04 ships 1.24, where HTTP/2 is a parameter on
  the `listen` line instead. Using the wrong form is a hard startup failure, so the role asks nginx
  its version and templates accordingly — which also keeps it usable against the older nginx on an
  older Ubuntu.

No auth on this vhost. The SPA does the OIDC dance itself via keycloak-js and holds the token.

### 4.2 API vhost — `dev.ems.lahendus.ut.ee`

No auth module, no claim-header plumbing. Core does the verifying:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

No CORS headers here: that is core's job and configurable per environment since EZ-1727. Adding them
at the proxy as well would send them twice, which browsers reject outright.

`Authorization` passes through untouched, which is all core needs. Two things that follow from
this being a dumb proxy, both good:

- The two `permitAll()` anonymous-autoassess endpoints
  (`/*/unauth/exercises/*/anonymous/autoassess` and `.../details`) need no special-casing in the proxy
  — Spring decides. Under an authenticating vhost they would each have needed their own
  `<Location>` with `Require all granted` or the "try an exercise without logging in" flow would
  break on dev only.
- Cross-origin preflight `OPTIONS` requests, which carry no `Authorization` header, are no longer a
  problem. An authenticating proxy would have 401'd them, and every API call would have failed
  looking exactly like a CORS bug in core.

### 4.3 CORS

Web and API are on different origins here, so core has to allow the web origin. Since EZ-1727 that
is config rather than a hardcoded list, so dev's `application.yaml` needs:

```yaml
easy:
  core:
    cors:
      allowed-origins: "https://dev.lahendus.ut.ee"
```

The key is required — the context will not start without it. Core logs the list it parsed at
startup, which is worth knowing because the failure mode otherwise is a browser-side CORS error
with nothing at all in the server log.

If you later decide to serve web and API from one origin, empty is the tighter setting: browsers
send no preflight, and nothing needs allowing.

---

## 5. Things that must be neutered before a single tester logs in

Dev runs the same code as prod, which means it has the same outbound reach into real systems.
This list is the actual "non-destructive" work — a separate database is the easy half.

| Risk | Path | Treatment |
| --- | --- | --- |
| **Grades written into real Moodle** | **There is no grades cron.** `syncSingleGradeToMoodle` is called from ordinary grading — `submissions.kt:138`, `TeacherPostGrade.kt:63`, `TeacherRetryAutoassess.kt:105` — as well as the manual `POST /courses/{id}/moodle/grades` | Pinning crons does **nothing** here, and neither does guarding the endpoint: any tester submitting or grading anything would write to the gradebook. Three locks, none sufficient alone — a dead `grades.url`; `easy.core.moodle-sync.course-allowlist`, enforced immediately before the request is built so it covers the grading paths; and anonymisation clearing every `moodle_short_name`, so no course is linked at all |
| **Real Moodle read + student invites** | `SyncMoodleAllStudents.kt:22`, `moodle-sync.users.url`, and a cron that iterates **every** course with a shortname — on a restored dump, every real course | Dead URL, pinned cron, allowlist, and cleared shortnames as above. A read is not harmless: it writes real names and usernames back into a database we deliberately anonymised |
| **Mass account deletion, in the DB and in Keycloak** | `DeleteInactiveUsers` (`core/src/main/kotlin/core/ems/cron/delete_inactive_users.kt`) deletes students idle 2y / teachers 5y, then deletes them from Keycloak via the admin API | Imported `last_seen` values are historical, so the **first cron run would delete a large slice of the imported data and hit the configured Keycloak**. Pin `easy.core.keycloak.cron` to the never-date, and give dev's Keycloak client a service account **without** user-delete permission |
| **Email to real people** | `SendMailService`, `easy.core.mail.*` | Run a local catch-all (mailpit) and point `spring.mail` at it, so email stays testable but cannot escape. Simpler alternative: `mail.user.enabled: false` and `mail.sys.enabled: false` |
| **Wrong IDP** | `easy.core.keycloak.base-url` | Must be the dev IDP. Combined with the delete cron above, a copy-paste of prod's value here is the worst single mistake available on this host |
| **Student code sent to production's graders** | the `executor` table, restored from the production dump. `syncExecutorsFromDB` picks the rows up on a timer; `callExecutor` sends no credentials | Not a config key — this one arrives *in the data*, which is why it was missed when this table was first written. Delete production's executor rows before core starts and register dev's own. `import-prod-dump.yml` does both (§3.6) |

The never-date trick for crons is already used in `core/src/test/resources/application.yaml`:
`"0 0 5 31 2 ?"` — February 31st, which never occurs. (That file used to be a gitignored
`.sample`; it is committed as of EZ-1715, so it can be read straight from the repo.)

Leave the *internal* crons on (`pending-access.clean`, `exercise-index-normalisation`,
`anonymous-submissions-to-keep`, statistics) — dev is the right place to find out they misbehave.

Set `easy.core.auth-enabled: true` (real auth) and `easy.web.base-url: https://dev.lahendus.ut.ee`
so email links point at dev.

---

## 6. Executor

**Done, 2026-08-10 — `ansible/roles/executor` builds all of this, and `ansible/grading-check.yml`
proves it works.** What follows is the plan it was built from, kept because the reasoning still
holds; what actually happened is at the end of the section.

Real executor: `aae/server.py` under gunicorn, grading in Docker containers on the same host.

- **Needs Python >= 3.9.** `aae/requirements.txt` was unusable on a fresh install until EZ-1720 —
  flask 1.1.1 resolved against a modern Jinja2 and failed to import, and docker 4.0.2 imports the
  `distutils` module Python 3.12 removed. It now pins flask 3.1.3 and docker 7.2.0, which need
  3.9+. A greenfield Ubuntu is fine; check `python3 --version` before assuming.

- **Base images must exist locally.** `containers.py` builds `FROM {base_image_name}` and notes the
  image "must already exist" — it does not pull. Exercises reference `container_image` rows that
  came in with the import, so mirror prod's image list onto this host or auto-assessment fails on
  exactly the exercises testers will try first. Enumerate `container_image` after import and
  build/pull each.
- **Register it in the DB.** Insert an `executor` row with `base_url = http://127.0.0.1:5111` and
  `executor_container_image` rows for each image. The `mock-executor` row from the testdata
  changeset (id 9001) won't be present in a prod dump, and shouldn't be added here.
- **Drop the sudo.** `aae/start-executor.sh` runs `sudo gunicorn3` with a
  `# TODO: privilege escalation should be a privilege not be taken for granted`. Under systemd,
  run as a dedicated `easy-executor` user in the `docker` group instead. Same access, no blanket
  root, and it fixes that TODO on the way past.
- **Accept, but write down, the co-location risk.** This host executes arbitrary student code. Being
  on the same VM as the dev DB means a container escape reaches that DB. Given the data is
  anonymised and backed up, that's a reasonable trade for one-VM simplicity — but if the
  anonymisation review in §3.3 decides to keep `teacher_inline_comment` rows, revisit it.
- Executor calls are unauthenticated (`callExecutor` in `core/src/main/kotlin/core/aas/executor_utils.kt:132`
  posts with no credentials), so 5111 must be loopback-only too.

### What it took, in the end

Four things the plan did not anticipate, all found by building it:

- **`doc/aae/dockerfiles/pygrader` had stopped being buildable.** `FROM python:3` drifted to 3.14,
  where `numpy~=1.23.4` has no wheels and the source build fails; `imgrec` builds `FROM pygrader`
  and went with it. Now pinned to `python:3.10`, which is what `tiivad` already used. Nobody
  changed the file — the tag moved underneath it, which is the failure mode of an unpinned base.
- **A failed image build used to cost the whole tier.** The role installs and starts the service
  *before* building images, carries build failures to an assertion at the very end, and registers
  only the images that actually exist — so one unbuildable image no longer means no executor, and
  never means core being told about an image it cannot grade with.
- **Core does not notice executor rows changing underneath it.** `addExecutorsFromDB` only added;
  an executor whose row was deleted stayed in the map and threw `NoSuchElementException` out of
  `getExecutorMaxLoad` every scheduling cycle, while a newly inserted one was never picked up. That
  is exactly what provisioning does, so it now reconciles in both directions on a timer —
  `syncExecutorsFromDB` (EZ-1709 follow-up). The role restarts core as well, for the builds that
  predate the fix.
- **The testdata exercises could never have graded.** All seven referenced a container image called
  `mock` that no Dockerfile provides, and their grading script was `echo "mock grading"`, which
  produces no `grade:` line and so parses as a failure. They are repointed to a real image, and
  `grading-check.yml` gives exercise 9001 a script that actually grades.

Verified end to end on 2026-08-10: the executor graded a submission 100 directly, and core graded
one 100 through the anonymous auto-assess path. `./run.sh grading-check.yml` re-runs both.

### The images stopped being built here (2026-08-21, EZ-1781)

A fifth thing, found later and worth adding to the four above: **this role could not deploy a version
bump.** It only built images that were *missing*, so changing `silmused==1.7.4` to `1.7.11` updated
the Dockerfile on the host, reported `changed`, and left grading on 1.7.4 — for a fortnight, because
nothing recorded what was installed. `b3607bf8` made a changed build context trigger a rebuild, which
fixed the symptom.

The cause was that the host built at all. Building here meant a version bump needed somebody with a
shell, every host produced its own subtly different image, and rollback did not exist:
`docker build -t` moves the tag in place, so the previous image survived only as untagged layers.

So the versions live in `doc/aae/pins/<environment>.yml`, CI builds and verifies the images once and
publishes them to GHCR under a tag it never overwrites, and `roles/executor_images` pulls them —
smoke-checking each one before it goes live and grading a synthetic submission after, reverting if
that fails. `doc/aae/grading-images.md` is the reference.

Two consequences for this document. §6's "the four grading images built from `doc/aae/dockerfiles`" is
no longer what happens. And **the graders now follow `master` rather than `dev-releases`**, so "what is
dev running" has two answers — deliberately, because promoting `dev-releases` needs push access and
the people who bump a grading library do not have it.

Open question #3 — *which container images does prod actually have?* — is still open, and now has a
better answer available: pull the digest dev has been grading with rather than rebuilding from a
version number.

---

## 7. Dev Keycloak realm

**Done, 2026-08-08. The full procedure is `doc/idp-setup.md`** — this section is now a summary and a
record of what the open question turned out to be.

The realm was **not** on disk, because it was never on disk. The old install's `keycloak.conf`
pointed at `jdbc:postgresql://localhost:5432/cloakdb`, and no postgres was installed on that host at
all: the 2026-08-07 home-directory restore carried the Keycloak distribution and the theme, and
nothing else. There was no dump, so the realm was **rebuilt from scratch** rather than restored.

The other finding was that the name in every config in this repo had not been right.
`dev.idp.lahendus.ut.ee` was a CNAME to `proxy.hpc.ut.ee`, which has never served this IdP, so
everything moved to **`easy-idp-dev.cloud.ut.ee`**, the VM's own name. On **2026-08-21** the alias was
repointed at the VM and everything moved back: `dev.idp.lahendus.ut.ee` is what Keycloak's `hostname`
is set to, which makes it the `issuer` in every token core validates. `doc/idp-setup.md` §5 has both
halves, and §5.1 the order to apply such a change in — the issuer moving signs everyone out.

What is running: Keycloak 25.0.2 behind nginx with a Let's Encrypt certificate, on its own postgres,
built by `ansible/roles/keycloak` and applied with `./run.sh site.yml --limit easyidpdev`.

The realm, as decided here and built there:

- **Registration disabled**, admin-created accounts only. Removes the "anyone with a UT account
  wanders into dev" problem entirely.
- `lahendus.ut.ee` — public client, PKCE, redirect URIs and web origins for
  `https://dev.lahendus.ut.ee`.
- **`easy_role` mapped** into the token, as **client** roles on that client rather than realm roles:
  a realm-role mapper would also emit `default-roles-master` and friends, and `mapRoleStringsToRoles`
  throws on the first role it does not recognise, rejecting the token outright.
- `easy-core` — confidential, service account holding `view-users` and **not** `manage-users` (§5).
  Note the admin client in the `master` realm is `master-realm`, not the `realm-management` every
  guide names.
- Three test accounts, one per role. **Every user needs an email address**: core rejects a token
  without one, so an account created without it logs in fine and then fails every API call.
- The "create a user whose username matches an imported teacher" recipe from §3.4 is written up in
  `doc/idp-setup.md` §4.6.

**Verified end to end**, which is the part worth trusting: a real token for `dev-teacher` is accepted
by core at `POST /v2/account/checkin` (200), and a malformed one is refused on the same endpoint
(401) — so the 200 means verification happened rather than being skipped.

**Use `https://dev.idp.lahendus.ut.ee/idp-admin/` for admin work**, not `/auth/admin/` directly.
Because we kept the `master` realm, every application user is a user in the realm whose admin console
that is, and Keycloak's answer to "signed in, but not an admin" is a blank page with two spinners
rather than a message. `/idp-admin/` checks first and either sends you through or says why not. It is
one more thing a dedicated realm would make unnecessary — `doc/idp-setup.md` §4.6.

One bug surfaced on the way and is fixed: `easy_core_idp_base_url` must be the **origin only**,
because `delete_inactive_users.kt` appends `/auth` itself. The old value ended in `/auth`, so every
admin-API call would have gone to `/auth/auth/...`. It was invisible because the cron that drives
those calls is pinned to the never-date. See `doc/idp-setup.md` §6.1.

---

## 8. Build and deploy

### 8.1 Artifacts from CI

**Done.** `.github/workflows/main.yml` publishes, on master, `releases/*` and `workflow_dispatch`:

- `core-<sha>.jar` — the Boot jar `./gradlew build` already produces. Environment-agnostic: all
  config is the external `application.yaml`.
- `web-<sha>.tar.gz` — from `npm run build`. Also environment-agnostic since EZ-1726.

Each job publishes after its own gates, so a jar can exist for a run whose web job failed. The
deploy script gates on the **run's** conclusion rather than on an artifact existing, which is the
check that matters.

Two details worth knowing:

- The jar is found by pattern and the `-plain` one excluded. The Boot plugin emits both; the plain
  jar holds classes without dependencies and dies with "no main manifest attribute" on the server.
  Matching by pattern also keeps `version` in `core/build.gradle.kts` something CI has no opinion
  about.
- **`config.json` is deleted from the dist before packing.** `web/public/config.json` holds the
  *production* IdP and API as local-dev defaults, so an artifact carrying it is one forgotten deploy
  step away from dev quietly talking to production — the §4.1 failure, and not one anybody
  debugs quickly. Without the file the app renders its "Configuration error" page instead, and the
  deploy writes the environment's own copy.

Both artifacts are now genuinely environment-neutral, which is the property that makes
artifact-based deploys worth the trouble: the build dev exercised is the same build that later
goes to production, byte for byte.

That was not true when this plan was written. `web/src/config.ts` read `import.meta.env.VITE_*` at
**build** time, so a dist was pinned to one API URL and one realm, and CI would have needed a matrix
producing one dist per environment. The SPA now fetches `/config.json` at boot instead.

Two consequences for the deploy:

- Step 4 below writes the environment's `config.json` into the unpacked dist. That file — four keys,
  `emsRoot` plus the three keycloak values — is the only environment-specific artefact on the web
  side. See `web/README.md`.
- The proxy must serve `config.json` with `Cache-Control: no-store`. The app already fetches it that
  way, but a caching layer in front is the one thing that can defeat the whole scheme: a stale
  `config.json` silently points a fresh deploy at the previous backend.

(`config.emsRoot` is used as a plain prefix in `fetch()` calls — `web/src/api/client.ts` — so an
absolute cross-origin URL works with no code change.)

For storage, GitHub Actions artifacts plus `gh run download` needs no new infrastructure and reuses
the `gh` auth everyone already has. 90-day retention is fine for dev. If artifacts need to
outlive that or become prod-promotable, switch to prereleases tagged `dev-<sha>`.

### 8.2 The deploy script

**Written.** `deploy/deploy-dev.sh <sha|latest>`, run by any team member from their laptop;
`deploy/README.md` is the operating manual and lists what the host must already have. Requires only
`gh` auth and SSH — no local JDK or Node, which is the main win over building on the server. It does
the seven steps above, plus:

- **Refuses a run that isn't green**, and says which conclusion it saw.
- **Skips the download when the host already has that release**, which is what makes rollback work
  after the 90-day artifact expiry.
- **Validates `config.json` after writing it** — all four keys, parsed — before flipping anything.
- **Renames the symlinks into place** rather than `ln -sfn`, which unlinks first; a request landing
  in that window 404s.
- **Waits for an HTTP 401** from the public API. Core has no unauthenticated health endpoint — the
  only `permitAll()` routes need a real exercise id — so 401 is what a live filter chain returns,
  and it proves the proxy, core and Spring Security all at once. That reads as a bug unless you know
  it, hence the comment at the poll. `systemctl is-active` covers the case where nginx itself
  produced the 401.
- Everything is checked **before** the symlink flip, so a bad artifact or a bad config leaves the
  previous release serving. Verified against a Linux container: missing `application.yaml` and an
  incomplete `config.json` both abort with `current` untouched.

Two systemd units the host needs: `easy-core.service` (`java -jar`, `Restart=on-failure`, config
path via `--spring.config.location`) and `easy-executor.service` (gunicorn as the non-root user from
§6). Deploy also needs passwordless `sudo systemctl restart easy-core`.

The environment's own files live in `deploy/dev/` — `config.json` and `dev.env`. Neither is
secret; the secrets stay in `/srv/easy/conf/application.yaml`, which no deploy touches. `SSH_TARGET`
ships as a placeholder and the script refuses to run until the VM is picked.

A plain `java -jar` is all this needs. Worth knowing that it wasn't always: until EZ-1729 the
bootJar could not start at all, because JRuby could not find the asciidoctor gems inside Boot's
nested jar layout, and the deploy script would have needed a `-Djarmode=tools ... extract` step to
work around it. Removing asciidoctor fixed that and took the artifact from ~84 MB to ~50 MB.

Migrations are forward-only through the `SpringLiquibase` bean in `core/conf/DatabaseConf.kt`, which
applies pending changesets and never drops. A rollback of the jar does **not** roll back the schema —
so a rollback across a migration boundary needs the nightly dump. Normal for Liquibase, worth
knowing before the first bad deploy rather than during it.

### 8.3 Automatic deployment from master

Deliberately phase two, on the same script: a workflow job on green master that SSHes in with a
deploy key and runs it. Manual deploy stays available and stays the override. Getting the manual
path solid first means the automatic one is one extra caller, not a second mechanism.

### 8.4 Dev as the release gate

Each environment keeps its own `application.yaml` outside the repo, and Spring **fails fast** on an
unresolved `@Value` placeholder. So a config key added in a release that nobody wrote down takes the
environment down on restart. That makes a dev deploy the natural place to catch it — the config
table in `doc/release-procedure.md` gains a third environment, and dev finds the missing row
before prod does. Worth adding dev to that doc as part of this work.

---

## 9. OS upkeep

Greenfield, so this is a small amount of setup done once:

- `unattended-upgrades` for security updates, with a defined reboot window.
- Note the interaction: a reboot mid-grading kills running Docker containers. Submissions in flight
  fail rather than corrupt (the scheduler retries), so a nightly window is fine — just don't put it
  mid-morning.
- Everything reproducible in one Ansible playbook from the start — packages, users, nginx vhosts,
  postgres, Docker, the two systemd units, mailpit, the backup cron. Not because dev needs
  Ansible, but because writing it here means prod's rebuild is a known quantity later, and the
  answer to "what did we configure on that box" is a file.

---

## 10. Open questions

1. ~~**TLS certs** — UT-issued certs, or is outbound ACME allowed for Let's Encrypt?~~ **Answered
   (2026-08-01): ACME works on this network.** The old dev host serves a Let's Encrypt cert issued
   6 Jul 2026, SANs `dev.lahendus`, `dev.ems.lahendus`, `dev.aas.lahendus` — so certbot renews from
   inside UT's network today, and nothing needs requesting from UT IT. Those SANs are also the two
   names dev now uses, which is part of why the `dev.core` rename was dropped (§1): the
   certificate story for `dev.lahendus` + `dev.ems.lahendus` is already a solved problem, and a new
   name would have needed both an A record and a fresh SAN.
2. **How much of `teacher_inline_comment` / `teacher_activity` survives anonymisation?** Grading-UI
   testing wants it; it's the most sensitive content in the DB. This is the one anonymisation call
   that needs a human decision, and it's easier to make once than to revisit (§3.3).
3. **Which container images does prod actually have?** Needed before auto-assessment works on
   dev; enumerate from prod's `container_image` table (§6).
4. **VM sizing** — the executor's `workers = 30` in `aae/gunicorn-conf.py.sample` plus concurrent
   Docker builds is the driver, not core. Dev can start much smaller, but pick a number.
5. **Who gets SSH?** Deploy needs it, which makes SSH access the real deploy permission.

---

## 11. Phasing

| Phase | Outcome |
| --- | --- |
| 1 | VM provisioned via Ansible; DNS + TLS; nginx with both vhosts; postgres. Nothing deployed. **Done 2026-08-04**; the executor followed on 2026-08-10, mailpit is still outstanding |
| 2 | Core deployed from a CI artifact with a **migrated-but-empty** DB; login works end to end against the dev realm. Proves the auth chain (§2, §4) before any real data exists. **Half done 2026-08-04**: deployed, serving, 42 tables migrated on first start — login blocked on §7, there is no IdP to log in to |
| 3 | Anonymisation script rewritten and reviewed; prod dump imported; backups running. **Done 2026-08-10.** `import-prod-dump.yml` runs the whole sequence; `anonymise.sql` was fixed to tolerate the older schema a production dump carries (§3.6); the nightly dump exists and is on-host only (§3.5) |
| 4 | Executor + base images; auto-assessment verified on a real imported exercise. **Executor and all four images done 2026-08-10**, and grading verified end to end by `ansible/grading-check.yml` — which now creates its own fixture exercise rather than borrowing testdata's 9001, since the import removed it |
| 5 | `deploy/deploy-dev.sh` documented; whole team can deploy. **Done 2026-08-04** — first real deploy succeeded, `SSH_TARGET` set. "Whole team" still means one account: the host has `kspar` and the break-glass `ubuntu`, and adding a deployer means `hardening_ssh_users` plus `easy_core_deploy_users` |
| 6 | Automatic deploy on green master; dev added to `doc/release-procedure.md` |

Phase 2 before phase 3 is the point worth keeping: the environment that can't yet leak anything is
the one to make mistakes in.
