# Staging Environment Plan

A permanent staging deployment of Lahendus with **real auth, real users, and a real executor**,
safe to point testers at and safe to redeploy from master several times a day.

Decisions already made (2026-07-31): data is a **one-time anonymised prod import** that then
drifts; CI builds artifacts and a **deploy script fetches them**; the dev Keycloak has
**registration disabled**, accounts created by an admin; the host is **greenfield**, no existing
Ansible or VM to inherit.

Done since (EZ-1724): core verifies Keycloak JWTs itself rather than trusting reverse-proxy
headers. This was deliberately landed before building staging, because it removes most of what the
Apache config would otherwise have to get right — see §2.

---

## 1. Topology

One new VM, plus the existing IDP elsewhere:

```
                    ┌──────────────────────────────────────────────┐
  browser ────────► │  staging VM                                  │
                    │                                              │
   dev.lahendus     │   Apache :443                                │
       .ut.ee ──────┼──►  vhost 1: static web dist (docroot)       │
                    │     vhost 2: OAuth2 resource server          │
   dev.core.lahendus┼──►             └─► 127.0.0.1:8080  core      │
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
| `dev.core.lahendus.ut.ee` | core API | Replaces `dev.ems.lahendus.ut.ee` |
| `dev.idp.lahendus.ut.ee` | Keycloak | Exists; only realm config changes |

`dev.ems.lahendus.ut.ee` is the historic name ("ems" was the pre-rename backend). Recommendation:
point the new name at the new VM and keep `dev.ems` as a 301 to `dev.core` for a release or two
rather than deleting the record — anything with a stale bookmark or hardcoded URL then fails loudly
in one place instead of mysteriously.

Needs from UT IT: two A records and TLS certs for both names (or ACME allowed outbound — confirm
which UT prefers, see §10).

---

## 2. Auth model

Core verifies Keycloak access tokens itself — Spring Security resource server, signature checked
against the realm's JWKS, issuer and expiry validated, claims mapped to `EasyUser` in
`core/conf/security/EasyUserJwtConverter.kt`. Apache is a plain TLS terminator and reverse proxy
with no auth role at all, and the SPA already sends `Authorization: Bearer`.

This was not true when this plan was first written (EZ-1724 changed it), and the difference is
most of why the Apache config in §4 is short. Previously core read identity *and roles* from
`oidc_claim_*` request headers and decoded the token without verifying its signature, which made
Apache the entire security boundary: it had to strip client-supplied claim headers, and core had
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

A single `pg_dump` of prod, anonymised, restored into the staging postgres once at setup. After
that staging is its own world: testers' courses and submissions accumulate and are never
overwritten by a refresh.

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

Each script **refuses to run** unless the database name contains `staging`, `stage` or `anon`.
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
  plagiarism comparison and auto-assessment worth testing on staging; `strip-submissions.sql` is
  there if the host ends up shared more widely than the team.

Because the import happens once and then drifts, both are decided once. Decide before the data
lands.

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

Because staging drifts and is never re-imported, tester-created state is unique and unrecoverable.
Nightly `pg_dump` to a second location, 7–14 days retention. This is a direct cost of the
drift choice and easy to forget.

Also: never place a `core/src/test/resources/application.yaml` on this host and never run
`./gradlew test` there. `InitTestDatabase` calls Liquibase `dropAll()`. It is not in the bootJar
and `assertDisposableDatabase()` fails closed on non-local hosts and non-`_test` database names
(see DEVELOPMENT.md §4), so this is defence in depth rather than a live risk — but the host should
not have the test source set on it at all, which the artifact-based deploy in §8 gives us for free.

---

## 4. Apache

### 4.1 Web vhost — `dev.lahendus.ut.ee`

Plain static hosting of the built `dist/`, plus an SPA fallback so deep links work — and one
required header:

```apache
DocumentRoot /srv/easy/web/current
FallbackResource /index.html

# config.json carries this environment's backend and realm (EZ-1726). It MUST NOT be cached:
# the app requests it with `cache: 'no-store'`, but a caching layer in front would hand a
# freshly deployed dist the previous environment's backend URL. That failure presents as
# "staging is talking to production", which is not where anyone looks first.
<Files "config.json">
    Header set Cache-Control "no-store"
</Files>
```

`mod_headers` has to be enabled for that (`a2enmod headers`).

No auth on this vhost. The SPA does the OIDC dance itself via keycloak-js and holds the token.

### 4.2 API vhost — `dev.core.lahendus.ut.ee`

No mod_auth_openidc, no `AuthType`, no claim-header plumbing. Core does the verifying:

```apache
ProxyPass        /v2 http://127.0.0.1:8080/v2
ProxyPassReverse /v2 http://127.0.0.1:8080/v2
```

`Authorization` passes through untouched, which is all core needs. Two things that follow from
this being a dumb proxy, both good:

- The two `permitAll()` anonymous-autoassess endpoints
  (`/*/unauth/exercises/*/anonymous/autoassess` and `.../details`) need no special-casing in Apache
  — Spring decides. Under an authenticating vhost they would each have needed their own
  `<Location>` with `Require all granted` or the "try an exercise without logging in" flow would
  break on staging only.
- Cross-origin preflight `OPTIONS` requests, which carry no `Authorization` header, are no longer a
  problem. An authenticating Apache would have 401'd them, and every API call would have failed
  looking exactly like a CORS bug in core.

### 4.3 CORS

Web and API are on different origins here, so core has to allow the web origin. Since EZ-1727 that
is config rather than a hardcoded list, so staging's `application.yaml` needs:

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

Staging runs the same code as prod, which means it has the same outbound reach into real systems.
This list is the actual "non-destructive" work — a separate database is the easy half.

| Risk | Path | Treatment |
| --- | --- | --- |
| **Grades written into real Moodle** | `POST /courses/{id}/moodle/grades` (`SyncMoodleAllGrades.kt:22`) — a **manual** endpoint, plus the cron | Pinning the cron is **not enough**. Point `easy.core.moodle-sync.grades.url` at a dead local address *and* pin the cron. A tester clicking "sync grades" must not reach the real gradebook |
| **Real Moodle read + student invites** | `SyncMoodleAllStudents.kt:22`, `moodle-sync.users.url` | Same: dead URL + pinned cron. A read is harmless, but it pulls real names back into the anonymised DB |
| **Mass account deletion, in the DB and in Keycloak** | `DeleteInactiveUsers` (`core/src/main/kotlin/core/ems/cron/delete_inactive_users.kt`) deletes students idle 2y / teachers 5y, then deletes them from Keycloak via the admin API | Imported `last_seen` values are historical, so the **first cron run would delete a large slice of the imported data and hit the configured Keycloak**. Pin `easy.core.keycloak.cron` to the never-date, and give staging's Keycloak client a service account **without** user-delete permission |
| **Email to real people** | `SendMailService`, `easy.core.mail.*` | Run a local catch-all (mailpit) and point `spring.mail` at it, so email stays testable but cannot escape. Simpler alternative: `mail.user.enabled: false` and `mail.sys.enabled: false` |
| **Wrong IDP** | `easy.core.keycloak.base-url` | Must be the dev IDP. Combined with the delete cron above, a copy-paste of prod's value here is the worst single mistake available on this host |

The never-date trick for crons is already used in `core/src/test/resources/application.yaml.sample`:
`"0 0 5 31 2 ?"` — February 31st, which never occurs.

Leave the *internal* crons on (`pending-access.clean`, `exercise-index-normalisation`,
`anonymous-submissions-to-keep`, statistics) — staging is the right place to find out they misbehave.

Set `easy.core.auth-enabled: true` (real auth) and `easy.web.base-url: https://dev.lahendus.ut.ee`
so email links point at staging.

---

## 6. Executor

Real executor: `aae/server.py` under gunicorn, grading in Docker containers on the same host.

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
  on the same VM as the staging DB means a container escape reaches that DB. Given the data is
  anonymised and backed up, that's a reasonable trade for one-VM simplicity — but if the
  anonymisation review in §3.3 decides to keep `teacher_inline_comment` rows, revisit it.
- Executor calls are unauthenticated (`callExecutor` in `core/src/main/kotlin/core/aas/executor_utils.kt:132`
  posts with no credentials), so 5111 must be loopback-only too.

---

## 7. Dev Keycloak realm

Changes on the existing `dev.idp.lahendus.ut.ee`:

- **Registration disabled**, admin-created accounts only (as decided). Removes the "anyone with a
  UT account wanders into staging" problem entirely.
- A client for the SPA: `lahendus.ut.ee` equivalent with redirect URIs and web origins for
  `https://dev.lahendus.ut.ee`. Public client, PKCE.
- **`easy_role` claim mapped** into the token — core derives all authorization from it
  (`mapRoleStringsToRoles`). Without it, every login is role-less.
- A confidential client for core's admin operations, with a service account scoped to *read* users
  and **not** delete them (§5).
- Document the "create a user whose username matches an imported teacher" recipe from §3.4 so
  testers can self-serve realistic access without an ad-hoc SQL grant each time.

---

## 8. Build and deploy

### 8.1 Artifacts from CI

Extend `.github/workflows/main.yml`. On master (and `workflow_dispatch`), after the existing gates
pass, publish:

- `core-<sha>.jar` — from `./gradlew bootJar`. Environment-agnostic: all config is the external
  `application.yaml`.
- `web-<sha>.tar.gz` — from `npm run build`. Also environment-agnostic since EZ-1726.

Both artifacts are now genuinely environment-neutral, which is the property that makes
artifact-based deploys worth the trouble: the build staging exercised is the same build that later
goes to production, byte for byte.

That was not true when this plan was written. `web/src/config.ts` read `import.meta.env.VITE_*` at
**build** time, so a dist was pinned to one API URL and one realm, and CI would have needed a matrix
producing one dist per environment. The SPA now fetches `/config.json` at boot instead.

Two consequences for the deploy:

- Step 4 below writes the environment's `config.json` into the unpacked dist. That file — four keys,
  `emsRoot` plus the three keycloak values — is the only environment-specific artefact on the web
  side. See `web/README.md`.
- Apache must serve `config.json` with `Cache-Control: no-store`. The app already fetches it that
  way, but a caching layer in front is the one thing that can defeat the whole scheme: a stale
  `config.json` silently points a fresh deploy at the previous backend.

(`config.emsRoot` is used as a plain prefix in `fetch()` calls — `web/src/api/client.ts` — so an
absolute cross-origin URL works with no code change.)

For storage, GitHub Actions artifacts plus `gh run download` needs no new infrastructure and reuses
the `gh` auth everyone already has. 90-day retention is fine for staging. If artifacts need to
outlive that or become prod-promotable, switch to prereleases tagged `staging-<sha>`.

### 8.2 The deploy script

`deploy/deploy-staging.sh <sha|latest>` in this repo, run by any team member from their laptop.
Requires only `gh` auth and SSH to the host — no local JDK or Node, which is the main win over
building on the server.

```
1  resolve <sha> → CI run, fail loudly if that run wasn't green
2  gh run download → jar + dist tarball
3  scp to /srv/easy/releases/<sha>/
4  ssh: unpack dist, write this environment's config.json into it (see §8.1),
      symlink /srv/easy/web/current → releases/<sha>/web
5  ssh: systemctl restart easy-core        # Liquibase migrates on startup
6  poll until core answers, then print the deployed sha
7  keep the last N releases; symlink flip means rollback is step 4 with an older sha
```

Two systemd units: `easy-core.service` (`java -jar`, `Restart=on-failure`, config path via
`--spring.config.location`) and `easy-executor.service` (gunicorn as the non-root user from §6).

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

### 8.4 Staging as the release gate

Each environment keeps its own `application.yaml` outside the repo, and Spring **fails fast** on an
unresolved `@Value` placeholder. So a config key added in a release that nobody wrote down takes the
environment down on restart. That makes a staging deploy the natural place to catch it — the config
table in `doc/release-procedure.md` gains a third environment, and staging finds the missing row
before prod does. Worth adding staging to that doc as part of this work.

---

## 9. OS upkeep

Greenfield, so this is a small amount of setup done once:

- `unattended-upgrades` for security updates, with a defined reboot window.
- Note the interaction: a reboot mid-grading kills running Docker containers. Submissions in flight
  fail rather than corrupt (the scheduler retries), so a nightly window is fine — just don't put it
  mid-morning.
- Everything reproducible in one Ansible playbook from the start — packages, users, Apache vhosts,
  postgres, Docker, the two systemd units, mailpit, the backup cron. Not because staging needs
  Ansible, but because writing it here means prod's rebuild is a known quantity later, and the
  answer to "what did we configure on that box" is a file.

---

## 10. Open questions

1. **TLS certs** — UT-issued certs, or is outbound ACME allowed for Let's Encrypt? Affects renewal
   automation.
2. **How much of `teacher_inline_comment` / `teacher_activity` survives anonymisation?** Grading-UI
   testing wants it; it's the most sensitive content in the DB. This is the one anonymisation call
   that needs a human decision, and it's easier to make once than to revisit (§3.3).
3. **Which container images does prod actually have?** Needed before auto-assessment works on
   staging; enumerate from prod's `container_image` table (§6).
4. **VM sizing** — the executor's `workers = 30` in `aae/gunicorn-conf.py.sample` plus concurrent
   Docker builds is the driver, not core. Staging can start much smaller, but pick a number.
5. **Who gets SSH?** Deploy needs it, which makes SSH access the real deploy permission.

---

## 11. Phasing

| Phase | Outcome |
| --- | --- |
| 1 | VM provisioned via Ansible; DNS + TLS; Apache with both vhosts; postgres. Nothing deployed |
| 2 | Core deployed from a CI artifact with a **migrated-but-empty** DB; login works end to end against the dev realm. Proves the auth chain (§2, §4) before any real data exists |
| 3 | Anonymisation script rewritten and reviewed; prod dump imported; backups running |
| 4 | Executor + base images; auto-assessment verified on a real imported exercise |
| 5 | `deploy/deploy-staging.sh` documented; whole team can deploy |
| 6 | Automatic deploy on green master; staging added to `doc/release-procedure.md` |

Phase 2 before phase 3 is the point worth keeping: the environment that can't yet leak anything is
the one to make mistakes in.
