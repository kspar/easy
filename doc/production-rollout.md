# Unattended production updates — `easy-rollout`

How production (and dev) update themselves, what has to be true before they do, what happens when
it goes wrong, and what a person does about it. The program is
`ansible/roles/core_rollout/files/easy_rollout.py`; its header is the short version of §2.

`deploy/README.md` is the manual for the on-host layout and for `deploy/deploy.sh`, which remains the
manual path. This document is about the automatic one.

## 1. What it is, in one paragraph

A timer on the core host asks GitHub what the environment's release branch points at. When that is
not what is live, and CI is green for it, and every gate in §3 passes, it takes a database dump,
boots the new release against a copy of that dump to see whether it starts at all, then installs
it, restarts core, and runs the whole application through its paces as a student and a teacher —
including a solution graded by the executor. If any of that fails after core was touched, it puts
the previous release back, restores the database if it has to, stops itself, and tells a person.
If it fails before core was touched, nothing changed and it tells a person. A commit that failed
once is never tried again without a person saying so.

Pull, not push, like dev always was: nothing in GitHub can reach the host, and the only credentials
on the host are ones it needs regardless. The release layout on disk is the one `deploy.sh` writes,
so a person can always take over with the tools they already have.

## 2. The flow

```
every tick (5 min prod, 1 min dev)
  paused?  ─────────────────────────── yes → nothing (say why in the journal)
  record what dev is running          (GET dev's /.well-known/easy-release; the soak's evidence)
  branch head == current-sha? ─────── yes → steady, silent
  head failed before? ──────────────── yes → nothing; `easy-rollout forget <sha>` to allow a retry
  green CI run for head? ───────────── no  → wait; WARN once a day after `stuck_after_hours`
  gates (§3) ───────────────────────── unmet → remember why; `easy-rollout status` shows it
  ROLLOUT
    1 preflight        disk, postgres, core healthy NOW, previous release intact on disk
    2 baseline smoke   the whole suite against what is live — if production already fails its own
                       tests, a failure after the deploy could not be attributed; abort
    3 fetch            CI artifacts into releases/<sha>/ (skipped if already there)
    4 dump             `systemctl start easy-db-backup.service`; a new dump must appear
    5 rehearsal        restore that dump into `easyems_rehearsal`; boot the NEW jar on 127.0.0.1:8091
                       with a config where every integration points at nowhere (§5); wait for it
                       to answer or die; count applied changesets; drop the scratch database
    6 activate         flip both symlinks, restart core
    7 health           401 from /v2/ through the public vhost, unit active
    8 smoke            the whole suite against the new release, up to 3 attempts a minute apart
    9 finish           current-sha, DEPLOYED, prune, INFO notification with the commit list
  failure at 6–8 → ROLLBACK: previous symlinks, restart, health, smoke
                   → old jar will not start AND the release migrated → restore the step-4 dump
                   → mark sha failed, PAUSE, CRITICAL notification
  failure at 1–5 → production untouched: mark sha failed, WARN (CRITICAL if it was the baseline),
                   not paused — a fixed commit should deploy on its own at the next window
```

One record per rollout under `/srv/easy/rollout/rollouts/<time>-<sha>.json` plus a `.log`, every
step with its duration and outcome. The notification carries the same table.

## 3. The gates

All in `roles/core_rollout/defaults/main.yml`, all overridable per environment.

| Gate | Production default | Dev | Why |
| --- | --- | --- | --- |
| Window | Tue, Thu 04:00–05:30 Europe/Tallinn | always | nobody is submitting; a bad outcome is found on a working day |
| Freeze periods | none — add exam sessions | none | dates when nothing rolls out whatever the window says |
| CI age | ≥ 6 h | 0 | time for a bad commit to be noticed and reverted on master |
| Soak on dev | seen live on dev ≥ 12 h | off | dev is the proving ground; the evidence is dev's own `current-sha`, published at `/.well-known/easy-release` |
| On master | required | required | a commit pushed straight at the branch is a hotfix nobody reviewed |
| Gap since last rollout | ≥ 20 h | 0 | two pushes in one window do not restart production twice |
| Stuck alarm | after 96 h, daily | same | the branch moved and nothing happened — a pipeline problem, not a production one |

**`easy-rollout deploy-now <sha|head>` skips the scheduling gates and never the checks.** CI must be
green; the baseline smoke, the dump, the rehearsal, the health check and the post-deploy smoke all
still run. It is for the hotfix at noon that cannot wait for Thursday, and it is consumed by the
rollout it triggers.

## 4. The smoke suite

`easy_smoke.py`, runnable on its own as `easy-smoke` on the host. It is the manual checklist from the
v4.0 runbook (log in as both roles, open a course, submit and watch it grade, check the versions)
plus what only a machine would bother with (every asset in index.html, the commit stamped in the
bundle, the `no-store` on config.json, certificate expiry).

| Group | Checks |
| --- | --- |
| web | `/` is html with scripts; `/config.json` 200 + `no-store` + right `emsRoot`/realm; every `<script>`/`<link>` 200 with the right type; the bundle contains the deployed commit; a deep route returns the SPA |
| tls | certificates of web, API and IdP hosts have ≥ 21 days left; HSTS present (warn) |
| core | `/v2/` → 401; `POST /v2/unauth/statistics/common` answers (a real database read) |
| idp | discovery + JWKS; a password-grant token for the smoke student and the smoke teacher |
| student | checkin; the smoke course is listed; the smoke exercise is listed, AUTO and open; details load; **the known-good solution is graded full marks** — through core, the executor, the grading image and back; **the known-bad solution is graded below full marks** |
| teacher | checkin; `/v2/versions` reports the deployed commit, every executor reachable, grading images present; the student's submission appears in the teacher's view |
| thonny | the token and logout endpoints `thonny-easy` hardcodes answer; the student token works on the plugin's first call; `/auth/js/keycloak.js` served (warn only — EZ-1803) |
| executor | `/v1/version` directly, where the host can reach it |

The bad-solution check is not decoration. A suite that only ever submits the right answer passes
against an executor that grades everything 100, and that is exactly the kind of broken that looks
fine from outside.

**Warnings do not fail the suite; every other check does.** Failures are retried up to three times
a minute apart after a deploy (caches warm, executors wake); the baseline run gets one attempt.

**What it writes:** submissions as the smoke student, into the smoke course, and nothing else. Two per
run, four per rollout. Nobody else sees the course.

## 5. The rehearsal

Step 5 is the part of this that no manual deploy ever did, and the part most worth having: the two
migration bugs found by importing a production dump into dev in 2026-08 (`doc/release-procedure.md`)
would both have failed here, minutes before production was touched, instead of taking production
down on the first start of v4.0.

`easy-rollout-db rehearsal-create <dump>` restores the dump just taken into `easyems_rehearsal`,
owned by a role of that name with a fresh random password. `rehearsal-config` derives a config from
production's `application.yaml` (`easy_rehearsal_config.py`), and this is the part to understand
before trusting it: core sends mail, writes grades into Moodle, deletes idle accounts from Keycloak,
deletes files from storage and grades queued submissions through the executor. The transform points
every one of those at nowhere — mail relay and Moodle at the discard port, allowlist naming a course
that does not exist (empty means *unrestricted*), storage local in a scratch directory with the
sweep in report-only mode, every cron pinned to a date that never comes, the auto-assess poller
stalled, YouTrack off, the real secrets file not imported and every secret present by name with a
worthless value. Then `problems()` checks each of those independently, and the rollout re-reads the
file and checks again before starting the JVM. The test suite puts each one back and confirms it is
noticed.

The JVM runs as `easy-rollout` on loopback port 8091 for at most 20 minutes. It either answers 401 —
migrations applied, context started — or exits, and its last log lines go into the notification. The
changeset count before and after tells the rollout whether the release changed the schema, which is
what decides the database question in a rollback.

## 6. Rollback, and the database

A rollback is the previous release's symlinks and a restart — the same thing `deploy.sh <old sha>`
does. Liquibase is forward-only, so the schema stays at the new release's version. Usually that is
fine: migrations here are additive and the old jar runs against the new schema. When it is not, the
old jar fails health, and `core_rollout_restore_db` decides:

- `auto` (production): restore the dump from step 4 **only if** the release changed the schema (or
  that could not be determined). The dump was taken seconds before the restart, in a window nobody
  is meant to be using, so what it loses is minutes of a maintenance window.
- `never` (dev): report `DOWN` and leave it to a person.
- `always`: restore whenever the old jar fails health.

`easy-rollout-db restore` stops core, **renames** the broken database to `easyems_pre_restore_<time>`
rather than dropping it, recreates and restores, starts core. Drop the renamed one by hand once sure.

Whatever the outcome, a rollout that touched core and failed **pauses** all further automatic
rollouts (`/srv/easy/rollout/pause`, with the reason) and marks the commit failed. This is the
answer to the objection in `doc/production-update.md` §1 — that a timer re-asserts the branch tip
and undoes a rollback a minute later: this one does not, by construction.

## 7. Who is told

Three channels, per severity (`core_rollout_notify_channels`); every one best-effort, and no
notification failure ever fails a rollout — the record on disk is the thing of record.

| Severity | When | Default channels |
| --- | --- | --- |
| INFO | deployed; the commit list is in the body | mail |
| WARN | aborted before touching production; branch stuck for days | mail |
| CRITICAL | rolled back; rolled back but smoke still fails; DOWN; baseline smoke fails; the machinery itself crashed (`OnFailure=`) | mail, webhook, YouTrack issue |

Mail goes through the relay core uses (`easy_core_mail_*`), to `easy_core_mail_sys_to` — **which
production has not set** (`doc/production-update.md` §6 item 8). Set it, or the rollout is mute.
The webhook URL, the mail credentials and the YouTrack token are files under `/etc/easy/`, created
as placeholders by the role and never read by it. A severity with no configured channel is logged as
having reached nobody.

## 8. Operating it

On the core host, as anyone in the `easy-deploy` group:

```
easy-rollout status              what is live, what is waiting and why, recent history
easy-rollout check               what the next tick would decide, without acting
easy-rollout pause "reason"      stop automatic rollouts (also what a failed rollout does to itself)
easy-rollout resume              allow them again
easy-rollout deploy-now <sha>    skip the scheduling gates for one commit; never the checks
easy-rollout forget <sha>        allow a commit that failed to be attempted again
easy-rollout smoke               run the suite now against what is live
easy-rollout rollback <sha>      put a release that is on disk back, by hand — pauses first
easy-smoke --json                the suite alone, machine-readable
journalctl -u easy-rollout       what every tick said
```

**Before any manual deploy with `deploy.sh prod`: `easy-rollout pause`.** Otherwise the next tick
sees `current-sha` disagree with the branch and, if the gates allow, puts the branch tip back. The
better manual deploy is `git push origin <sha>:prod-releases` — it goes through every check.

**After a CRITICAL:** read the record named in the message, `easy-rollout status`, look at core's
log. Decide whether the commit is wrong (fix on master, push, the fix deploys at the next window
after `resume`) or the machinery is (fix, then `forget` and `resume`). Do not `resume` without
understanding why it paused; it will happily roll out the next commit.

## 9. Setup, once per environment

1. **The IdP**: the `easy-smoke` client and the two accounts — `doc/idp-setup.md` §4.8.
2. **The smoke course**: log in to the web UI as `easy-smoke-teacher`, create a course named
   `Smoke (automatic checks)`, add `easy-smoke-student` to it, and create one exercise in it:
   Python, auto-graded, open, no deadline, whose grader gives full marks to a program that prints
   exactly `Hello, smoke!` and less to anything else — a TSL spec with a single stdout check is
   enough. Change `core_rollout_smoke_good_solution` / `_bad_solution` if the exercise differs.
   Note the course id and the *course exercise* id from the URL.
3. **Inventory**: the `core_rollout_*` values in the environment's group_vars (production's are
   gitignored — the block in `ansible/inventories/production/hosts.example.yml` lists them; dev's
   are committed). `easy_core_mail_sys_to` must be set for anyone to hear from it.
4. **Converge**: `site.yml` against the environment. It installs the account, the grants, the helper,
   the units, and creates the placeholder credential files.
5. **Credentials on the host** (the role reports which are still placeholders):
   `/etc/easy/github-token` (a fine-grained PAT, Actions: read — shared with autodeploy's file
   name), `/etc/easy/smoke-secrets.json` (the client secret and the two passwords),
   optionally `/etc/easy/rollout-webhook-url`, `/etc/easy/rollout-youtrack-token`,
   `/etc/easy/rollout-mail-auth`.
6. **Prove it before trusting it**: `easy-rollout smoke` must pass; `easy-rollout check` must say
   something sensible. On a first production install, `easy-rollout pause "first install"` before
   the converge and `resume` only after both do.
7. **Then watch the first real rollout happen** from the journal, even though the point is that
   nobody has to.

Dev first, always. Dev runs the identical machinery from `dev-releases`, so every promotion to dev
exercises the dump, the rehearsal and the rollback before production depends on them. A change to
this role goes to dev, deploys itself there, and only then belongs on production.

## 10. What it does not do

- **Browser-driven checks.** No Chromium on the host. A Playwright run with a real login against
  the smoke account could run from GitHub Actions on a schedule; it is not part of a rollout.
- **Grading images.** They are promoted to production by hand (`doc/aae/grading-images.md`) and the
  suite only checks that grading works with whatever is live.
- **Off-site backups, or `/srv/easy/files`.** A rollback restores the database from the on-host
  dump; the local file store is not backed up by anything in this repo (`doc/backups.md`).
- **The IdP and the executor host.** It deploys core and web. The suite proves both of the others
  work; it does not change them.
- **Config changes.** `application.yaml` comes from `roles/core_config` and a release that needs a
  new key fails in the rehearsal with the key's name — which is the right place to find out, and a
  person then adds it and pushes again.
