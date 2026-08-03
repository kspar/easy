# deploy

Deploying a CI-built release to staging. The reasoning behind all of it is in
`doc/staging-environment.md` §8; this is the operating manual.

```sh
deploy/deploy-staging.sh latest        # newest green CI run on master
deploy/deploy-staging.sh 1a2b3c4       # a specific commit, full or short sha
deploy/deploy-staging.sh 1a2b3c4 --dry-run
```

Needs `gh` (authenticated), `jq`, and SSH to the staging host — no JDK, no Node. The jar and the
dist come from the CI run that gated that commit, so the build staging exercises is byte-for-byte
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
as local-dev defaults, and an artifact carrying those is one forgotten step away from staging
silently talking to production. Without the file the app renders a "Configuration error" page
instead, and the deploy writes the environment's own copy from `deploy/staging/config.json`.

## Before the first deploy

The script assumes a host the plan's phase 1 has already built. It needs:

```
/srv/easy/conf/application.yaml      core's config — out of repo, never touched by a deploy
/srv/easy/releases/                  one directory per deployed commit
/srv/easy/core/current.jar           symlink the systemd unit runs
/srv/easy/web/current                symlink Apache's DocumentRoot points at
```

- **`/srv/easy/conf/application.yaml`** — start from `core/src/main/resources/application.yaml.sample`
  and work through §5 of the plan, which lists everything that has to be neutered before a tester
  logs in. `doc/release-procedure.md` lists the keys v4.0 added. The script checks the file exists
  and stops before restarting if it doesn't, but it cannot check the contents.
- **`easy-core.service`** — `java -jar /srv/easy/core/current.jar
  --spring.config.location=/srv/easy/conf/application.yaml`, `Restart=on-failure`.
- **`easy-executor.service`** — gunicorn as a non-root `easy-executor` user in the `docker` group
  (§6; this replaces the `sudo gunicorn3` in `aae/start-executor.sh`).
- **Passwordless `sudo systemctl restart easy-core`** for whoever deploys — the only sudo the script
  uses, besides `journalctl` when a deploy fails.
- **Apache** serving `/srv/easy/web/current` with `FallbackResource /index.html`, and `config.json`
  with `Cache-Control: no-store` (§4.1).

Then set `SSH_TARGET` in `deploy/staging/staging.env`. It ships as a placeholder and the script
refuses to run until it is real.

## Values still to confirm

`deploy/staging/config.json` is written from the plan, not from a live IdP — `dev.idp.lahendus.ut.ee`
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
