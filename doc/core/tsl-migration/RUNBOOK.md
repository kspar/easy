# TSL migration runbook

Do-this-on-the-day sequence. `README.md` explains what each script does and why; this is the
order, the traps, and what to do when something goes wrong.

Written 2026-08-08 by kspar's Claude, from a full rehearsal against the 2026-08-07 production
export and a local core.

---

## Read this first

**Re-export. Do not reuse what is on disk.** `tsl-export.jsonl`, `out/` and `migrated/` in this
directory are from the rehearsal and are stale the moment a teacher edits an exercise. Delete them
and start from step 2. Migrating from a stale export would write an old spec over a newer one —
the one failure mode here that silently destroys someone's work.

```sh
rm -rf out migrated tsl-export.jsonl writeback.log
```

**Deploy the merged `:tsl` first.** The migrated specs only compile against the new model. Until
that build is live, every write fails — harmlessly, because `writeback.py` stops on the first one,
but you will have wasted the window. See `doc/release-procedure.md` for the deploy itself.

**Nothing is destroyed by this migration.** `PUT /v2/exercises/{id}` creates a *new*
`exercise_version` row and marks the previous one with `valid_to`; the old row and its assets stay
in the database. Verified during the rehearsal by restoring an exercise from its previous version
after a write. Rollback is possible per exercise — see the bottom of this file.

---

## The state as of the rehearsal

Numbers from 2026-08-07. If your fresh export is wildly different, something changed and is worth
understanding before you continue.

| | |
|---|---|
| TSL exercises | 723 |
| needing migration | 189 |
| already fine | 532 |
| legacy YAML (`tiivad:tsl-spec`) | 2 — skipped, no `tsl.json` to rewrite |
| tests converted | 810 |
| retired types in use | 18 of the 39 removed |

After migrating, all 721 JSON specs compiled; before, 532 did.

---

## 1. Deploy

The merged `:tsl` must be live. Confirm by saving any TSL exercise in the editor — if the save
succeeds, the deployed compiler understands the current model.

## 2. Export

```sh
psql -h <host> -U <user> -d <db> -Atq -f export.sql > tsl-export.jsonl
wc -l tsl-export.jsonl        # expect ~723, growing slowly over time
```

Read-only. `-Atq` matters — without it psql writes headers and confirmations into the JSONL.

## 3. Look before you leap

```sh
python3 explode.py --export tsl-export.jsonl --out ./out
cat out/summary.txt
```

Check `unreadable by any parser` is **0**. Anything else is new and needs a look before you go on.

`out/summary.txt` is the only shareable output here; everything under `out/exercises/` is
production content.

## 4. Migrate

```sh
python3 migrate.py --export tsl-export.jsonl --out ./migrated
```

Exits non-zero if it meets a retired type it has no mapping for — which would mean someone
authored one since the rehearsal, and needs a new mapping rather than a workaround.

It also prints a "review by hand" list. In the rehearsal that was one exercise whose feedback
messages read as the opposite of what the test asserts. That is pre-existing and preserved
deliberately; it is flagged, not fixed.

## 5. Verify before writing anything

```sh
python3 explode.py --export tsl-export.jsonl --verify ./migrated
```

Must exit 0. Then compile every migrated spec — the check that actually proves the migration is
sound, since it uses the real compiler rather than a guess about it:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew -q -I <init-script> :tsl:tslRun \
  -PtslArgs="doc/core/tsl-migration/migrated/exercises"
```

Expect `failed: 0`. (The rehearsal used a scratch harness for this; if it is gone, the equivalent
is a small JVM main that walks the tree calling `compileTSL` on each `tsl.json`.)

## 6. Dry run

```sh
python3 writeback.py --migrated ./migrated --url https://<host>/v2 --token "$TOKEN" \
  --rewrite --skip 741
```

Writes nothing. Confirms the token works, every exercise is reachable, and the count matches
step 4 — it should say **would write: 189** (188 with 741 skipped). A larger number means
`migrate.py` changed specs it should have left alone, and is a reason to stop.

**`--rewrite` is not optional for a migration.** It writes through
`PUT /v2/admin/exercises/{id}/rewrite`, which keeps the previous author and `valid_from` instead of
stamping whoever ran the script and dating every exercise today. Needs admin. The README explains
the rest of what it preserves.

**`--skip 741`**, because 741 has rendered text and no Markdown source, and `text_html` is
regenerated from `text_md` on every save. The rewrite endpoint refuses it rather than blanking
10 KB of description — so without the skip the run stops there. Six other exercises are the same
shape with an already-empty `text_html`; they pass the guard and lose nothing. What to do about 741
is EZ-1731's question.

## 7. Write a few, then the rest

```sh
python3 writeback.py --migrated ./migrated --url https://<host>/v2 --token "$TOKEN" \
  --rewrite --skip 741 --apply --limit 5
```

Then open one of those five in the editor and confirm it looks right and saves — and that it does
**not** now claim you edited it. Only then:

```sh
python3 writeback.py --migrated ./migrated --url https://<host>/v2 --token "$TOKEN" \
  --rewrite --skip 741 --apply
```

It stops at the first failure. Re-running skips whatever already carries the new spec, so fix the
cause and run the same command again.

## 8. Confirm

Re-export and re-explode:

```sh
psql -h <host> -U <user> -d <db> -Atq -f export.sql > after.jsonl
python3 explode.py --export after.jsonl --out ./after
grep "need migration" after/summary.txt      # expect 0
```

---

## When it goes wrong

**`TSL_COMPILE_FAILED` on a specific exercise.** The message names the test type and its path in
the spec. Either the deployed `:tsl` is older than you think, or `migrate.py` produced something
the compiler rejects — in which case stop, do not hand-edit the spec on the server, and fix the
mapping so the whole corpus stays consistent.

**401 / 403.** The token needs teacher or admin, and the account needs access to every exercise's
directory. An admin account is the straightforward answer.

**A run stopped halfway.** Nothing to undo. `writeback.log` lists what was written, and the script
skips already-migrated exercises anyway, so the same command resumes.

**Which exercises did the rewrite touch?** It leaves `author` and `valid_from` alone by design, so
the only trace is `valid_to`: for a rewrite, the superseded row's `valid_to` is strictly *later* than
its successor's `valid_from`, where an ordinary save leaves the two equal.

```sql
SELECT prev.exercise_id, prev.valid_to AS rewritten_at
FROM exercise_version prev
JOIN exercise_version next ON next.previous_id = prev.id
WHERE prev.valid_to > next.valid_from
ORDER BY prev.exercise_id;
```

**Rolling back one exercise.** The previous version is still in the database:

```sql
-- inspect
SELECT id, valid_from, valid_to, title FROM exercise_version
 WHERE exercise_id = <id> ORDER BY valid_from;

-- restore: make the previous version current again
BEGIN;
UPDATE exercise_version SET valid_to = now() WHERE id = <new_version_id>;
UPDATE exercise_version SET valid_to = NULL  WHERE id = <old_version_id>;
COMMIT;
```

Check it in the UI before committing. This was rehearsed against a local database, not production.

**Rolling back everything.** There is no bulk undo, and it should not be needed: grading is
unaffected either way, because tiivad 0.0.33 still routes every legacy test type to its old
handler. A half-migrated corpus is not a broken one.

---

## Afterwards

- Drop the legacy handlers from tiivad, and the back-compat that made this migration unhurried.
- Re-pick the add-test presets in `web/src/features/library/tsl/tslPresets.ts` against
  `out/summary.txt`. They were chosen from a guess at which test types teachers use; the histogram
  is the real answer, and it disagrees — `defines a function` is the most-used retired type by a
  wide margin and has no preset, while "Uses try/except" has one and five uses.
- EZ-1742 collects the model cleanups this exercise surfaced, for muuli.

## The one untested path

Everything above was rehearsed against a local core using `--dev-user`, which sends `oidc_claim_*`
headers to a core with auth disabled. The `--token` bearer path is the same code with different
headers, but it has never actually run. Step 6's dry run is where that gets proven — do not skip
it.
