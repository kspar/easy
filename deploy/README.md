# deploy

Deploying a CI-built release. `deploy/deploy.sh <env> <sha|latest>`, where the environment is a
directory beside this file — `dev/` and `prod/` today, each holding that environment's `config.json`
and `<env>.env`. The reasoning behind the dev half is in `doc/dev-environment.md` §8 and the
production half in `doc/production-update.md`; this is the operating manual for both.

**The environment has no default and never will.** Same rule as `ansible/`, for the same reason: an
omitted environment should fail at the command line rather than resolve to whichever one somebody
hardcoded. `deploy/deploy.sh 1a2b3c4` is a usage error, not a deploy.

On dev there are two paths and the automatic one is the normal one. On production there is one, and
it asks first.

> **This covers core and web only.** Since EZ-1781 the grading images are on their own track: they
> are published to GHCR by CI and pulled by a timer on the host, following `master` rather than
> `dev-releases`. So "what is dev running" has two answers, and this file only answers one of them —
> `doc/aae/grading-images.md` has the other.
>
> They are decoupled because promoting `dev-releases` needs push access, and the people who bump a
> grading library do not have it. Coupling the two would have meant a core dev in the loop for every
> version bump, which is the thing that change existed to remove.

## Automatic: push to `dev-releases`

```sh
git push origin master:dev-releases     # dev updates itself within a couple of minutes
```

A timer on the dev host polls GitHub every minute for the newest **green** CI run on
`dev-releases`, and installs it if it differs from what is live. Nothing else is needed: no SSH, no
laptop, no command.

**Pull, not push, and that is the point.** The alternative — a deploy job in the workflow that
SSHes in — needs a private key for the dev host stored in GitHub, on a *public* repository,
where anyone with repo admin or a compromised Action inherits a shell on the box. Polling costs a
minute of latency and nothing else: there is no inbound access, and the only credential involved is
a read-only token that lives on the host and can do nothing but read CI artifacts of a public repo.

`ansible/roles/core_autodeploy` installs it. Watch it work:

```sh
ssh easycoredev 'systemctl list-timers easy-autodeploy.timer --no-pager'
ssh easycoredev 'sudo journalctl -u easy-autodeploy.service -n 40 --no-pager'
ssh easycoredev 'cat /srv/easy/current-sha'
```

The branch is `dev-releases` rather than `releases` because `releases/*` is for real version
branches — CI builds those too — and a name that says which environment it feeds cannot be mistaken
for one of them.

**Promoting a commit master already built does not wait for CI again.** The timer resolves what
`dev-releases` points at and asks whether a green run exists for *that commit*, from any branch —
the sha identifies the tree, and which ref pointed at it while the runner worked says nothing about
whether it passed. So the useful order is: push to master, let CI go green, then promote, and the
deploy lands on the next tick.

Note this is separate from CI *starting* a run: pushing a sha to a second branch is a new push
event, so Actions builds it again regardless. **That duplicate run is deliberate, and it is not on
the deploy's critical path** — it costs CI minutes and delays nothing.

It stays because it is the only thing standing behind a commit that reaches `dev-releases` without
going through master — a quick fix pushed straight at the branch, which is exactly the moment
nobody is being careful. Without the trigger such a commit is never built, and the timer would sit
there reporting "no green CI run exists for it yet" once a minute while whoever pushed it waited
for a deploy that could not come. Paying for a redundant build of the normal path is the cheaper
side of that trade.

**It needs a token, once.** `/etc/easy/github-token` is created as a placeholder, and until a real
value is in it every tick exits having said so in the journal. A fine-grained PAT scoped to
`kspar/easy` with **Actions: Read-only** is the least it can be — artifact downloads require
authentication even for a public repo, which is the only reason a token exists at all:

```sh
ssh easycoredev 'sudo tee /etc/easy/github-token >/dev/null'   # paste, then Ctrl-D
```

No restart needed; the next tick picks it up. Those PATs expire — when it does, the timer logs the
failure every minute, which is at least loud.

## Manual: a specific commit

Still the way to deploy something that is not the tip of `dev-releases` — a rollback, or something
off master:

```sh
deploy/deploy.sh dev latest        # newest green CI run on master
deploy/deploy.sh dev 1a2b3c4       # a specific commit, full or short sha
deploy/deploy.sh dev 1a2b3c4 --dry-run
```

**A manual deploy is not sticky, whatever you deploy.** The timer asks what `dev-releases` points at
and compares it to `current-sha` — a plain equality check, with no notion of which commit is newer.
So *any* hand-deployed commit that is not the tip of that branch is replaced within a tick, including
one straight off master: `deploy/deploy.sh dev latest` was undone about a minute later on 2026-08-21,
which reads as the deploy having silently failed. To pin the host, stop the timer first:

```sh
ssh easycoredev 'sudo systemctl stop easy-autodeploy.timer'
```

**Expect a screenful of `rm: cannot remove …: Permission denied` while it prunes**, and ignore it —
the deploy itself is fine. Releases the timer created are owned by `easy-autodeploy` with no group
write, so the invoking user cannot delete them and the prune loop's exit status is not checked. That
is EZ-1784; until it is fixed, `KEEP_RELEASES` is only enforceable for releases a human placed.

The two implementations are separate on purpose — one pushes from a laptop, one pulls on the host —
but they write the same on-host layout, and that layout is a contract between them. Change both
together; it is written out at the top of
`ansible/roles/core_autodeploy/templates/easy-autodeploy.py.j2`.

Needs `gh` (authenticated), `jq`, and SSH to the host — no JDK, no Node. The jar and the
dist come from the CI run that gated that commit, so the build dev exercises is byte-for-byte
the one that goes to production.

**SSH access is the deploy permission.** There is no other gate; anyone who can reach the host can
restart core on it.

## Production

```sh
deploy/deploy.sh prod 1a2b3c4              # the only form; see below
deploy/deploy.sh prod 1a2b3c4 --dry-run    # resolve and download, touch nothing remote
```

**`deploy/prod/` is not in the repo.** `deploy/dev/` is — dev is the environment a fresh checkout is
meant to be able to deploy to, and production is deliberately not. What it holds is the SSH target,
the release branch production is allowed to run, and its hostnames; the same reasoning as
`ansible/inventories/production/group_vars/`, and the same asymmetry. Ask kspar, or write the two
files from the description below.

Being precise about what is actually secret: `config.json` is fetched by every browser that loads
the site, so its four values are public by design and withholding the file protects nothing. It is
withheld anyway, so the rule is "production's deploy directory is not in git" rather than a per-file
judgement somebody has to re-make each time a key is added.

```
deploy/prod/prod.env       SSH_TARGET, REMOTE_ROOT, CORE_SERVICE, DEPLOY_BRANCH, WEB_URL,
                           HEALTH_URL, KEEP_RELEASES, plus the two below
deploy/prod/config.json    emsRoot and the three keycloak values, and NO "environment" key —
                           absent means production, which is what leaves the site unbadged (EZ-1733)
```

Same script and the same seven steps. Three differences, all in `prod/prod.env`:

- **No `latest`.** The script refuses it for any environment that asks for confirmation. `latest`
  resolves to whatever the branch points at right now, which is the correct affordance for a host
  that redeploys several times a day and the wrong one for a host that is deployed on purpose.
- **It asks.** It prints the commit, the run URL, the target and the service, and waits for the
  environment name to be typed back. Everything before that prompt is read-only, so it is the last
  point where stopping costs nothing. `--yes` skips it, for a scripted rollback where the decision
  was already made out loud.
- **It dumps the database first.** `PRE_RESTART_DUMP=true` starts `easy-db-backup.service` — the
  nightly backup unit from `roles/postgres` — after every check has passed and immediately before
  the restart that runs the migrations. It is a oneshot, so the deploy waits for a dump that has
  been written *and* verified. Liquibase is forward-only: this is the only way back across a
  migration, and production had no backups at all before 2026-08.

Deliberately **no polling autodeploy on production**. The dev timer re-asserts what `dev-releases`
points at within a minute, which is exactly right there and turns a rollback here into a rollback
plus a remembered `systemctl stop`. Production's deploys are named by a person.

`DEPLOY_BRANCH` is a `releases/*` branch rather than `master`, so a hotfix is a deliberate push to a
named branch. CI builds `releases/*`.

## What CI publishes

`.github/workflows/main.yml`, on master, `releases/*` and `workflow_dispatch` only:

| Artifact | Contents |
| --- | --- |
| `core-<sha>` | `core-<sha>.jar` — the Boot jar. Every setting is external, so it is environment-agnostic |
| `web-<sha>` | `web-<sha>.tar.gz` — the Vite dist, **with `config.json` removed** |

Artifacts expire after 90 days. Each job publishes its own after its own gates, so a jar can exist
for a run whose web job failed — the deploy script gates on the *run's* conclusion, not on the
artifact existing.

`config.json` is stripped on purpose. `web/public/config.json` holds the **production** IdP and API
as local-dev defaults, and an artifact carrying those is one forgotten step away from dev
silently talking to production. Without the file the app renders a "Configuration error" page
instead, and the deploy writes the environment's own copy from `deploy/dev/config.json`.

That copy also carries `"environment": {"label": "DEV", …}`, which is what puts the badge, the
tab-title prefix and the orange favicon on this deployment (EZ-1733). Production's config.json must
**not** have the key — absent means production, so nothing there needs changing to stay unmarked.

## Before the first deploy

**`ansible/` builds all of this.** `./run.sh site.yml` against the dev inventory produces a host
this script can deploy to; there is nothing here to do by hand. The list below is what it sets up, so
that a deploy failing on a missing precondition is diagnosable rather than mysterious.

```
/srv/easy/conf/application.yaml   core's config — written by Ansible, never touched by a deploy
/srv/easy/conf/secrets.yaml       credentials — created on the host, Ansible never reads them
/srv/easy/releases/               one directory per deployed commit
/srv/easy/core/current.jar        symlink the systemd unit runs
/srv/easy/web/current             symlink nginx's root points at
```

- **The config** comes from `roles/core_config`, which also refuses to write a dangerous one — §5 of
  the plan lists five ways a correctly deployed host can damage real systems, and each is an
  assertion in that role. The deploy script checks only that the file exists.
- **`easy-core.service`** and the directory tree come from `roles/core_service`.
- **nginx and TLS** come from `roles/nginx` (§4).
- **postgres** comes from `roles/postgres`, which also tells the database the password the host
  generated for itself.
- **`easy-executor.service`** — still to write; gunicorn as a non-root `easy-executor` user in the
  `docker` group (§6, replacing the `sudo gunicorn3` in `aae/start-executor.sh`).

**Who can deploy.** Two things, both from `roles/core_service`: membership of the `easy-deploy` group,
which owns write access to the release tree, and a passwordless `sudo` grant for exactly two commands
— restarting core, and reading its log when a deploy fails. Both are driven by
`easy_core_deploy_users` in the environment's inventory. SSH access is the real permission, so adding
someone means adding them to `hardening_ssh_users` as well.

Worth knowing about the split: core *reads* the jar, a deploy *replaces* it, and those are separate
privileges on purpose. The deploy account cannot read `secrets.yaml`, and the service account cannot
write the release tree.

Then set `SSH_TARGET` in the environment's `<env>.env`. It ships as a placeholder and the script
refuses to run until it is real.

**Production needs one more grant than dev**: the sudoers line for
`systemctl start easy-db-backup.service`, so a deploy can take its own restore point. It comes from
`roles/core_service` for every environment, so there is nothing extra to do — but a deploy that
fails at the dump with a password prompt means that role has not been re-run since the grant was
added.

## The IdP values in `deploy/dev/config.json`

All confirmed against the live dev IdP on 2026-08-08, and `doc/idp-setup.md` is where they come from.
Two of the three are not what a Keycloak guide would tell you, so they are worth knowing before
"fixing" one:

- **`keycloak.url`** keeps the `/auth/` prefix Keycloak dropped in the Quarkus rewrite. The IdP is
  configured to serve it (`http-relative-path`) because core hardcodes it too — removing it means
  changing core, both `config.json` files and production together.
- **`keycloak.realm`** is `master`, mirroring production. Not best practice, kept deliberately so dev
  is a release gate; `doc/idp-setup.md` §4.1 has the consequences.
- **`keycloak.clientId`** is `lahendus.ut.ee`, a public PKCE client whose redirect URIs and web
  origins cover `https://dev.lahendus.ut.ee`.

The host in the first two is `dev.idp.lahendus.ut.ee` since 2026-08-21 — it and `keycloak_hostname`
in the dev inventory must always name the same host, or the browser logs in against one IdP and core
validates against another. `doc/idp-setup.md` §5.1 is the order to change it in.

`idpAdminUrl` points the SPA's admin link at `/idp-admin/`, the gate page in front of the Keycloak
console, on the same host.

`emsRoot` is an absolute cross-origin URL — web and API are separate names on dev — so it also
requires `easy_core_cors_allowed_origins` on the host to contain `https://dev.lahendus.ut.ee`. It
does; the failure mode if it ever stops is a browser CORS error with nothing in the server log.

## Rollback

Same command with an older sha. Releases stay on the host — `KEEP_RELEASES` of them — so a rollback
to one still present needs neither a download nor a surviving CI run.

A rollback across a Liquibase migration does **not** roll the schema back. Migrations are
forward-only through the `SpringLiquibase` bean; going back over one needs a dump (§3.5).

On production that dump exists by construction: `PRE_RESTART_DUMP=true` means the deploy that
applied the migration took one first, so the restore point is dated a few seconds before the change
it undoes. On dev the newest nightly is as close as it gets.

## What a deploy does

1. Resolves the sha to a CI run and **refuses anything that isn't green**
2. Downloads both artifacts (skipped if the host already has that release)
3. Uploads to `/srv/easy/releases/<sha>/`
4. Unpacks the dist, writes `config.json` into it and verifies all four keys parse
5. Renames both symlinks into place — built beside and `mv -T`'d over, since `ln -sfn` unlinks
   first and a request landing in that window 404s
6. Dumps the database, where the environment asks for it — after every check that can fail, before
   the restart that migrates
7. `sudo systemctl restart easy-core` — Liquibase migrates on startup
8. Polls the public API until it answers, then confirms the unit is active
9. Prunes old releases, keeping the newest `KEEP_RELEASES`

Step 8 waits for an HTTP **401**, which is the healthy answer: core has no unauthenticated health
endpoint, so Spring Security answers a bare `/v2/` that way once its filter chain is up. A 502 or no
answer at all means core is down. On timeout the script prints the last 40 journal lines.

Worth knowing that this check meant less on production before v4.0: the Apache vhost ran
`mod_auth_openidc` and answered 401 itself, so it would have passed over a dead core. `systemctl
is-active` is what covered that, and still does.

## Not done yet

Automatic deploy on green master (§8.3) is deliberately phase two, and is one more caller of this
script rather than a second mechanism.
