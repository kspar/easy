# Backups

**Two nightly jobs, because there are two kinds of data.** The database is one. Uploaded files are
the other, and they are not in the database — `stored_file` holds metadata and the bytes live in a
directory. Restoring only the dump gives you an article full of broken images and a database that
looks perfectly healthy.

| | installed by | takes | into |
| --- | --- | --- | --- |
| `easy-db-backup` | `roles/postgres` | a `pg_dump` of the database | `postgres_backup_dir` |
| `easy-files-backup` | `roles/core_service` | a tar of the upload directory | `easy_files_backup_dir` |

They are separate units rather than one job because they run as different users: the dump
authenticates over the unix socket as `postgres`, and uploaded files are mode 0600 owned by the core
account in a 0750 directory, which `postgres` cannot read. Merging them would mean widening those
permissions, and a backup that widens the permissions of what it copies has quietly published it.

Most of this document is about the database job, which is the older and more intricate of the two.
The file job has [its own section](#the-file-archive) and deliberately borrows this one's rules.

The environment-specific numbers — sizes, hosts, current disk — are deliberately not here; this is
the mechanism.

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

## The file archive

`easy-files-backup.sh`, written by `roles/core_service`, run nightly at 05:00 by
`easy-files-backup.timer`. It produces `files-<YYYY-MM-DD>T<HHMM>.tar.gz` in
`easy_files_backup_dir`, holding the upload directory with bare storage keys as filenames — so a
restore is `tar -xzf <archive> -C /srv/easy/files` and nothing else has to change.

It follows the rules above rather than inventing its own: written to `.partial` and renamed only
after `tar -t` reads it back, pruned only after a successful archive, aged by the timestamp in the
file name rather than mtime, and interrogable with `sudo easy-files-backup --dry-run`.

Three things specific to it:

- **Retention is a plain window, `easy_files_backup_keep_days`, default 14.** The grandfather-
  father-son scheme next door exists because deploys take extra dumps, so a count of days was an
  unbounded count of files. Nothing takes an extra file archive, so a window already has a ceiling.
- **05:00, deliberately clear of 04:00.** That is when `easy_core_stored_file_sweep_cron` fires, and
  the sweep is the one job on the host that *deletes from the directory being archived*.
- **`tar` exiting 1 is tolerated; exiting 2 is not.** This directory is live — an upload can land
  mid-walk, and GNU tar reports "file removed before we read it" as a warning. Treating that as
  fatal would mean an ordinary upload could cost a night's backup, silently.

**Why archives and not a mirror.** Keys are immutable, so a mirror would be cheaper — and would
faithfully reproduce a sweep that deleted a file it should not have, which is the failure this is
actually insuring against. Dated archives make a wrong deletion recoverable. The cost is that every
archive is a near-complete copy of the last; if this directory ever becomes large, hardlinked
snapshots are the first thing to reach for.

## What this does not protect against

**Both the dumps and the archives are on the same disk, the same filesystem and the same machine as
the data they copy.** They cover a bad migration, a bad deploy, a careless `DELETE`, a sweep that
collected a file still in use. They do not cover anything that takes the host with it, and an
attacker with root deletes them first.

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
