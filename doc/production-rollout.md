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
every tick (1 min prod, 30 s dev)
  paused?  ─────────────────────────── yes → nothing (say why in the journal)
  record what dev is running          (GET dev's /.well-known/easy-release; the soak's evidence)
  branch head == current-sha? ─────── yes → steady, silent
  head failed before? ──────────────── yes → nothing; `easy-rollout forget <sha>` to allow a retry;
                                             reminded daily after `stuck_after_hours`
  GitHub unreachable? ──────────────── log; WARN once a day while it lasts; never a crash
  green CI run for head? ───────────── no  → wait; WARN once a day after `stuck_after_hours`
  gates (§3) ───────────────────────── unmet → remember why; `easy-rollout status` shows it
  ROLLOUT
    1 preflight        disk, postgres, core healthy NOW, previous release intact on disk
    2 baseline smoke   the whole suite against what is live, two attempts — if production already
                       fails its own tests, a failure after the deploy could not be attributed; abort
    3 fetch            CI artifacts into releases/<sha>/, all or nothing (skipped if already there)
    4 dump             `systemctl start easy-db-backup.service`; a new dump must appear
    5 rehearsal        restore that dump into `easyems_rehearsal`; boot the NEW jar on 127.0.0.1:8091
                       as the unprivileged rehearsal account, with a config where every integration
                       points at nowhere (§5); wait for it to answer or die; count applied
                       changesets; drop the scratch database
    6 activate         flip both symlinks, restart core
    7 health           401 from /v2/ through the public vhost, unit active — and no waiting out
                       the timeout for a unit that is failed or crash-looping
    8 smoke            the whole suite against the new release, up to 3 attempts a minute apart
    9 mark             current-sha, DEPLOYED — the last step whose failure rolls back
   10 prune            old releases; INFO notification with the commit list. Never a rollback reason.
  failure at 6–9 → ROLLBACK: previous symlinks, current-sha back, restart, health, smoke
                   → the old jar's UNIT does not start (not merely "the URL is quiet") AND the
                     release migrated or that is unknown → restore the step-4 dump (§6)
                   → mark sha failed, PAUSE, CRITICAL notification
  failure at 1–5 → production untouched, and it depends on why:
                   the commit's fault (rehearsal died, config key missing, baseline smoke fails
                     against a working production) → mark sha failed, WARN; not paused — a fixed
                     commit deploys on its own at the next window
                   not the commit's fault (GitHub, disk, the backup unit, smoke unconfigured)
                     → retry after `min_retry_gap_hours`, WARN once a day while it recurs
  SIGTERM, or the unit's time budget nearly spent → treated as a failure of the current step:
                   rollback if production was touched, record, pause, notify — then exit
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
| Soak on dev | live on dev ≥ 12 h **in one stretch, and still there** | off | dev is the proving ground; the evidence is dev's own `current-sha`, published at `/.well-known/easy-release`, sampled every tick. A commit dev rolled back from has not soaked, whatever the calendar says |
| On master | required | required | a commit pushed straight at the branch is a hotfix nobody reviewed |
| Gap since last rollout | ≥ 20 h | 0 | two pushes in one window do not restart production twice |
| Gap since a retryable failure | ≥ 6 h | 0 | a window is not spent re-dumping the database ten times |
| Stuck alarm | after 96 h, daily | same | the branch moved and nothing happened — a pipeline problem, not a production one |

**`easy-rollout deploy-now <sha|head>` skips the scheduling gates and never the checks.** CI must be
green; the baseline smoke, the dump, the rehearsal, the health check and the post-deploy smoke all
still run. It is for the hotfix at noon that cannot wait for Thursday. `head` is resolved to a
commit id when the command is typed, the override names that commit only, applies only while the
branch points at it, and expires after 24 hours — so one left behind cannot fire on whatever gets
pushed next week. A commit marked failed is not overridden either; `forget` it first.

## 4. The smoke suite

`easy_smoke.py`, runnable on its own as `easy-smoke` on the host. It is the manual checklist from the
v4.0 runbook (log in as both roles, open a course, submit and watch it grade, check the versions)
plus what only a machine would bother with (every asset in index.html, the commit stamped in the
bundle, the `no-store` on config.json, certificate expiry).

| Group | Checks |
| --- | --- |
| web | `/` is html with scripts; `/config.json` 200 + `no-store` + `emsRoot` is exactly this API's origin plus `/v2` + right realm; `/version.json` names the deployed commit; every `<script>`/`<link>` 200 with the right type; a deep route returns the SPA |
| tls | certificates of web, API and IdP hosts have ≥ 21 days left; HSTS present (warn) |
| core | `/v2/` → 401; `POST /v2/unauth/statistics/common` with no body answers (a real database read) |
| idp | discovery + JWKS, issuer equal to what core validates; a password-grant token for the smoke student and the smoke teacher |
| student | checkin; the smoke course is listed; the smoke exercise is listed, AUTO and open; details load; **the known-good solution is graded full marks** — through core, the executor, the grading image and back; **the known-bad solution is graded below full marks**. Each submission carries a per-run nonce and the run reads back its own, so two overlapping runs cannot grade each other's work |
| teacher | checkin; `/v2/versions` reports the deployed commit and every executor reachable; grading images listed (warn — an old aae reports none); **this run's** submission appears, with its grade, in the teacher's latest-submissions view |
| thonny | the token and logout endpoints `thonny-easy` hardcodes answer; the student token works on the plugin's first call; `/auth/js/keycloak.js` served (warn only — EZ-1803) |
| executor | `/v1/version` directly, where the host can reach it |

The bad-solution check is not decoration. A suite that only ever submits the right answer passes
against an executor that grades everything 100, and that is exactly the kind of broken that looks
fine from outside.

**Warnings do not fail the suite; every other check does.** Failures are retried up to three times
a minute apart after a deploy (caches warm, executors wake); the baseline run gets two.

**What it writes:** as the smoke student, two submissions per attempt into the smoke course — up to
six per run, and a rollout runs it up to three times (baseline, after deploy, after a rollback).
Each account's `checkin` updates that account's name, email and last-seen, as any login does. A
grading failure the suite reports is one core has already mailed the system address about, as it
would for any student. Nothing else, and nobody else sees the course.

## 5. The rehearsal

Step 5 is the part of this that no manual deploy ever did, and the part most worth having: the two
migration bugs found by importing a production dump into dev in 2026-08 (`doc/release-procedure.md`)
would both have failed here, minutes before production was touched, instead of taking production
down on the first start of v4.0.

`easy-rollout-db rehearsal-create <dump>` restores the dump just taken into `easyems_rehearsal`,
owned by a role of that name with a fresh random password, and rewrites the restored `executor`
rows to a discard address — the executors are data, not config, and would otherwise ride in as
real machines. `rehearsal-config` derives a config from production's `application.yaml`
(`easy_rehearsal_config.py`), and this is the part to understand before trusting it: core sends
mail, writes grades into Moodle, deletes idle accounts from Keycloak, deletes files from storage,
grades queued submissions through the executor and honours bearer tokens from the IdP. The
transform points every one of those at nowhere — mail relay, Moodle, the IdP's JWKS and issuer and
Keycloak's admin base all at the discard port, allowlist naming a course that does not exist (empty
means *unrestricted*), storage local in a scratch directory with the sweep in report-only mode,
every cron pinned to a date that never comes, every fixed-delay poller stalled, YouTrack off, the
real secrets file not imported and every secret present by name with a worthless value. Then
`problems()` checks each of those the transform sets, and the rollout re-reads the file and checks
again before starting the JVM. The test suite puts each one back and confirms it is noticed.

One honest limit: Spring runs a fixed-delay job once at startup whatever its delay, so the grading
poller, the observer sweep, the statistics push and the executor sync each run one time in every
rehearsal — against an empty queue, a scratch database and executor rows that point at nowhere.
That is why the executor rewrite above exists.

The JVM runs as `easy-rehearsal`, a system account with no sudo and one writable directory, in a
transient unit with the same confinement as core's own, on loopback port 8091 for at most 20
minutes. It either answers 401 — migrations applied, context started — or exits, and its last log
lines go into the notification. The changeset count before and after tells the rollout whether the
release changed the schema, which is what decides the database question in a rollback; a count
that cannot be taken is recorded as *unknown*, never as zero.

## 6. Rollback, and the database

A rollback is the previous release's symlinks and a restart — the same thing `deploy.sh <old sha>`
does — and `current-sha` is written back at once, so it always names what is live. Liquibase is
forward-only, so the schema stays at the new release's version. Usually that is fine: migrations
here are additive and the old jar runs against the new schema. When it is not, the old jar's
**unit** fails to start — and only then, never merely because the public URL is quiet (that is
nginx, DNS or a slow warm-up, and a restore would throw data away to fix none of them) — does
`core_rollout_restore_db` decide:

- `auto` (production): restore the dump from step 4 **only if** the release changed the schema (or
  that could not be determined). The dump was taken seconds before the restart, in a window nobody
  is meant to be using, so what it loses is minutes of a maintenance window.
- `never` (dev): report `DOWN` and leave it to a person.
- `always`: restore whenever the old jar's unit fails after a rollback.

`easy-rollout-db restore` stops core, ends any session still attached, **renames** the broken
database to `<db>_pre_restore_<time>` rather than dropping it, recreates it with the same locale,
restores, and starts core — and starts core on whatever database exists if any of that fails, so a
failed restore is a broken database with a running service rather than a stopped one. Drop the
renamed database by hand once sure.

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
| WARN | aborted before touching production (once a day while a retryable reason recurs); branch stuck for days; GitHub unreachable for hours; state file reset | mail |
| CRITICAL | rolled back; rolled back but smoke still fails; DOWN; the machinery itself crashed (`OnFailure=`) | mail, webhook — plus a YouTrack issue where that channel is added |

Mail goes through the relay core uses (`easy_core_mail_*`), to `easy_core_mail_sys_to`, which is
not a role default and has to be set in the environment's group_vars — a rollout with nowhere to
send mail is mute. The webhook URL, the mail credentials and the YouTrack token are files under
`/etc/easy/`, created as placeholders by the role and never read by it. YouTrack is opt-in — add
`youtrack` to the `critical` list in `core_rollout_notify_channels` — and only ever filed with a
visibility group (the instance has guest access; the role refuses the channel without one). A severity with no configured channel is logged as having reached nobody, and
**`easy-rollout notify-test`** sends one message at each severity and reports which channels
delivered — run it as part of setup, and after changing any of them.

## 8. Operating it

On the core host. The first group needs nothing but the state directory and runs as anyone in the
`easy-deploy` group; the second needs the GitHub token or the smoke credentials, which only the
rollout account can read, so those run as it — the deploy group is granted exactly that:

```
easy-rollout status              what is live, what is waiting and why, recent history
easy-rollout pause "reason"      stop automatic rollouts (also what a failed rollout does to itself)
easy-rollout resume              allow them again
easy-rollout forget <sha>        allow a commit that failed to be attempted again
easy-rollout deploy-now <sha>    skip the scheduling gates for one commit; never the checks
easy-rollout notify-test         one message per severity; which channels delivered

sudo -u easy-rollout easy-rollout check            what the next tick would decide, without acting
sudo -u easy-rollout easy-rollout deploy-now head  resolves head to a commit id first
sudo -u easy-rollout easy-rollout smoke            run the suite now against what is live
sudo -u easy-rollout easy-rollout rollback <sha>   put a release that is on disk back, by hand —
                                                   pauses first, marks the current one failed
sudo journalctl -u easy-rollout                    what every tick said
```

`touch /srv/easy/rollout/pause` pauses too — presence is what counts, the reason is for the reader.

**Before any manual deploy with `deploy.sh prod`: `easy-rollout pause`.** Otherwise the next tick
sees `current-sha` disagree with the branch and, if the gates allow, puts the branch tip back.
`deploy.sh` refuses to run against a host whose rollout timer is active and not paused, and says
so. The better manual deploy is `git push origin <sha>:prod-releases` — it goes through every
check — and the better manual rollback is `easy-rollout rollback`, which pauses and records itself.

**After a CRITICAL:** read the record named in the message, `easy-rollout status`, look at core's
log. Decide whether the commit is wrong (fix on master, push, the fix deploys at the next window
after `resume`) or the machinery is (fix, then `forget` and `resume`). Do not `resume` without
understanding why it paused; it will happily roll out the next commit.

## 9. Setup, once per environment

1. **The IdP**: the `easy-smoke` client and the two accounts — `doc/idp-setup.md` §4.8.
2. **The smoke course**: as an **admin** (only admins create courses), create a course named
   `Smoke (automatic checks)`, add `easy-smoke-teacher` as its teacher and `easy-smoke-student` as
   its student. Then, as the smoke teacher, create one exercise in it: Python, auto-graded, open,
   no deadline, whose grader gives full marks to a program that prints exactly `Hello, smoke!` and
   less to anything else — a TSL spec with a single stdout check is enough. The suite appends a
   comment line to each solution it submits; a stdout check does not see it. Change
   `core_rollout_smoke_good_solution` / `_bad_solution` if the exercise differs. Note the course id
   and the *course exercise* id from the URL.
3. **Inventory**: the `core_rollout_*` values in the environment's group_vars (production's are
   gitignored — the block in `ansible/inventories/production/hosts.example.yml` lists them; dev's
   are committed). `easy_core_mail_sys_to` must be set for anyone to hear from it.
4. **Converge**: `site.yml` against the environment. It installs the two accounts (`easy-rollout`,
   which deploys, and `easy-rehearsal`, which only ever runs the jar under test), the grants, the
   helper, the units, and creates the placeholder credential files.
5. **Credentials on the host** (the role reports which are still placeholders):
   `/etc/easy/github-token` (a fine-grained PAT, Actions: read — shared with autodeploy's file
   name), `/etc/easy/smoke-secrets.json` (the client secret and the two passwords),
   optionally `/etc/easy/rollout-webhook-url`, `/etc/easy/rollout-youtrack-token`,
   `/etc/easy/rollout-mail-auth`.
6. **Prove it before trusting it**: `sudo -u easy-rollout easy-rollout smoke` must pass;
   `sudo -u easy-rollout easy-rollout check` must say something sensible; `easy-rollout notify-test`
   must reach somebody at CRITICAL. On a first production install, `easy-rollout pause "first
   install"` right after the converge (the converge creates the directory) and `resume` only after
   all three do. Until the token is real, every tick says so and exits; nothing pages.
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
