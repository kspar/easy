# deploy

Deploying a CI-built release to dev. The reasoning behind all of it is in
`doc/dev-environment.md` §8; this is the operating manual.

There are two paths, and the automatic one is the normal one.

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
deploy/deploy-dev.sh latest        # newest green CI run on master
deploy/deploy-dev.sh 1a2b3c4       # a specific commit, full or short sha
deploy/deploy-dev.sh 1a2b3c4 --dry-run
```

**A manual deploy is not sticky.** The timer compares against `current-sha` on every tick, so if
you hand-deploy something older than the tip of `dev-releases`, the next tick puts the newer one
back. To pin the host, stop the timer first:

```sh
ssh easycoredev 'sudo systemctl stop easy-autodeploy.timer'
```

The two implementations are separate on purpose — one pushes from a laptop, one pulls on the host —
but they write the same on-host layout, and that layout is a contract between them. Change both
together; it is written out at the top of
`ansible/roles/core_autodeploy/templates/easy-autodeploy.py.j2`.

Needs `gh` (authenticated), `jq`, and SSH to the dev host — no JDK, no Node. The jar and the
dist come from the CI run that gated that commit, so the build dev exercises is byte-for-byte
the one that can later go to production.

**SSH access is the deploy permission.** There is no other gate; anyone who can reach the host can
restart core on it.

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

Then set `SSH_TARGET` in `deploy/dev/dev.env`. It ships as a placeholder and the script
refuses to run until it is real.

## Values still to confirm

`deploy/dev/config.json` is written from the plan, not from a live IdP — `dev.idp.lahendus.ut.ee`
currently resolves to `proxy.hpc.ut.ee`, which answers `tlsv1 unrecognized name`, so the dev realm
could not be inspected. Check all three before the first login attempt:

- **`keycloak.url`** — the `/auth/` path prefix is Keycloak 16-and-earlier. Keycloak 17+ dropped it,
  so a rebuilt dev IdP most likely wants `https://dev.idp.lahendus.ut.ee/` with no suffix.
- **`keycloak.realm`** — `master` mirrors production. The dev realm may not be called that.
- **`keycloak.clientId`** — needs a public client with PKCE whose redirect URIs and web origins
  cover `https://dev.lahendus.ut.ee` (§7).

`emsRoot` is an absolute cross-origin URL, which requires `dev.ems.lahendus.ut.ee` to exist (it has
no A record yet) and requires `easy.core.cors.allowed-origins` on the host to contain
`https://dev.lahendus.ut.ee` (§4.3).

## Rollback

Same command with an older sha. Releases stay on the host — `KEEP_RELEASES` of them — so a rollback
to one still present needs neither a download nor a surviving CI run.

A rollback across a Liquibase migration does **not** roll the schema back. Migrations are
forward-only through the `SpringLiquibase` bean; going back over one needs the nightly dump (§3.5).

## What a deploy does

1. Resolves the sha to a CI run and **refuses anything that isn't green**
2. Downloads both artifacts (skipped if the host already has that release)
3. Uploads to `/srv/easy/releases/<sha>/`
4. Unpacks the dist, writes `config.json` into it and verifies all four keys parse
5. Renames both symlinks into place — built beside and `mv -T`'d over, since `ln -sfn` unlinks
   first and a request landing in that window 404s
6. `sudo systemctl restart easy-core` — Liquibase migrates on startup
7. Polls the public API until it answers, then confirms the unit is active
8. Prunes old releases, keeping the newest `KEEP_RELEASES`

Step 7 waits for an HTTP **401**, which is the healthy answer: core has no unauthenticated health
endpoint, so Spring Security answers a bare `/v2/` that way once its filter chain is up. A 502 or no
answer at all means core is down. On timeout the script prints the last 40 journal lines.

## Not done yet

Automatic deploy on green master (§8.3) is deliberately phase two, and is one more caller of this
script rather than a second mechanism.
