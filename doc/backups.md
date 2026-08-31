# Database backups

What `roles/postgres` installs, what it keeps, and what it does not protect against. The
environment-specific numbers — sizes, hosts, current disk — are deliberately not here; this is the
mechanism.

## What runs

`easy-db-backup.sh`, written by `roles/postgres`, run two ways:

- **`easy-db-backup.timer`**, nightly at 03:30.
- **`deploy/deploy.sh`**, which starts the same unit before it restarts core on an environment with
  `PRE_RESTART_DUMP=true`. Liquibase migrations are forward-only, so the dump taken here is the only
  way back across one.

Both produce `<db>-<YYYY-MM-DD>T<HHMM>.dump` in `postgres_backup_dir`.

## What a dump is

```
pg_dump --format=custom --compress=9 --no-owner --no-privileges
```

`--no-owner --no-privileges` because a real restore is usually into a cluster that has never heard
of these roles.

Two properties worth knowing, both deliberate:

- **It is written to `.partial` and renamed only after it verifies.** `pg_restore --list` parses the
  whole table of contents, which a truncated or half-written archive fails. The rename is atomic
  within the filesystem, so the directory holds finished dumps and nothing else — a backup that
  looks fine and is not is the failure this avoids.
- **Pruning happens only after a dump succeeds.** Pruning first, or unconditionally, means a week of
  failures quietly eats the history it was supposed to protect.

## What is kept

Grandfather-father-son, counted in backups rather than measured in days:

| | default | keeps |
| --- | --- | --- |
| `postgres_backup_keep_all_days` | 3 | every dump younger than this, whatever bucket it falls in |
| `postgres_backup_keep_daily` | 5 | the newest dump of each of the last 5 days |
| `postgres_backup_keep_weekly` | 2 | the newest dump of each of the last 2 ISO weeks |
| `postgres_backup_keep_monthly` | 2 | the newest dump of each of the last 2 months |

**Why a count and not a window.** This was `keep_days: 14`, which assumed one dump a day. With a
dump before every deploy, a fortnight is however many deploys happened in a fortnight — so the
archive grew fastest exactly when the system was being changed most, and on a small disk it filled
the host it was protecting. A count has a ceiling you can do arithmetic on.

**Why the buckets overlap.** Today's dump is usually the daily, the weekly and the monthly at once,
so the real file count sits well below the sum. `--dry-run` names every role a dump satisfies —
`recent+daily+weekly+monthly` — so this is visible rather than folklore.

**Why `keep_all_days` exists at all.** Without it the buckets discard the pre-deploy dump, which is
the one a bad release needs: it is taken on a day that already has a nightly, and the daily bucket
keeps only the newest dump per day. That is not hypothetical — it happened the first time the
buckets ran, and three dumps from one day collapsed to one.

The script cannot distinguish a deploy dump from a nightly: `deploy.sh` starts the same systemd
unit, and the sudoers grant names that unit exactly. Keeping recent dumps of every kind is cruder
than tagging deploys, and needs no change to the unit, the deploy script or sudoers.

Buckets come from the timestamp in the file name, not from mtime — a copied or restored file gets a
new mtime and would otherwise misreport its own age.

## Asking before trusting

```sh
sudo easy-db-backup --dry-run
```

Prints what it would keep, with the reason for each, and what it would remove. Deletes nothing.
Something that removes backups should be possible to interrogate before it is believed.

## What this does not protect against

**The dumps are on the same disk, the same filesystem and the same machine as the database.** They
cover a bad migration, a bad deploy, a careless `DELETE`. They do not cover anything that takes the
host with it, and an attacker with root deletes them first.

Off-site copies are tracked separately. The parts that matter there are the ones easily defaulted
wrongly: credentials that cannot delete what they wrote, encryption before the data leaves the host,
and a restore rehearsed on a schedule — an untested off-site backup is a belief, not a backup.

## Restoring

Nothing here is a restore procedure, because a restore should be rehearsed rather than read. The one
rehearsal worth copying: restore into a scratch database on the same host and compare row counts
against the live one, table by table, before believing the archive.

```sh
sudo -u postgres createdb restorecheck
sudo -u postgres pg_restore -d restorecheck -j 2 <dump>
# compare counts, then:
sudo -u postgres dropdb restorecheck
```
