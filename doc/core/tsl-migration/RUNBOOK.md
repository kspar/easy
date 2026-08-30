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
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew -q :tsl:compileSpecTree \
  -PspecTree=doc/core/tsl-migration/migrated/exercises
```

Expect `failed: 0`, and a non-zero exit if anything did not compile, so this can gate the rest.

**Run it against the un-migrated tree too, and read that number first.** A harness that quietly
compiled nothing reports zero failures and looks exactly like a success; the before number is what
makes the after number mean anything. Against the 2026-08-12 dev corpus:

```sh
./gradlew -q :tsl:compileSpecTree -PspecTree=doc/core/tsl-migration/out/exercises   # 532 OK, 189 failed
./gradlew -q :tsl:compileSpecTree -PspecTree=doc/core/tsl-migration/migrated/exercises   # 721 OK, 0 failed
```

### `failed: 0` and a non-zero exit are not contradictory

The task counts three things and throws on the sum of two of them:

| | |
|---|---|
| compiled OK | the spec compiled and produced Python |
| failed | the spec did not compile |
| **not Python** | it compiled, and the result is not valid Python |

So a tree can report `failed: 0` and still exit non-zero because of the third row. **Read all three.**

The third row is not something a migration causes. It means a spec's stored content produces invalid
Python when interpolated — most often an argument holding an unbalanced bracket, because arguments are
emitted raw and by design so that `5` is an int and `[1, 2]` is a list. Such an exercise has never
graded: every submission to it fails with a syntax error, and this gate is simply the first thing
that looked.

Treat a non-zero exit as a stop **only after checking which row caused it**. A `failed` count is the
migration's problem; a `not Python` count is a content defect that predates it and wants fixing at
the source, not by hand-editing a spec on the server.

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

## Recompiling is no longer a migration

`writeback.py` exists because re-saving every exercise was the only way to get a fresh compile, and
re-saving means a version per exercise — which is why so much of this runbook is about attribution
surviving. **A pure recompile no longer needs any of it.**
`POST /v2/admin/exercises/tsl/recompile` regenerates the scripts in place and creates no version.
It touches no `tsl.json` either — with one deliberate exception: `"normalize_specs": true` rewrites
a spec **that strict JSON rejects** (a wui-era raw newline inside a string, EZ-1813) into the strict
re-serialisation of what the compiler already reads, so the React editor can finally open it. A spec
that parses strictly is never rewritten, whatever its formatting. See `doc/core/api-testing.md`.

Reach for this runbook when the **specs** change — a retired test type, a model migration — and for
the recompile endpoint when only the **compiler** has.

## Measuring what an emitter change does

`compileSpecTree` now takes `-PspecDump=<dir>` and writes every generated script out, and
`semdiff.py` compares two such directories **by AST rather than by text** — so a change of quote
style, which rewrites all 720 files, reports as zero.

```sh
./gradlew -q :tsl:compileSpecTree -PspecTree=<corpus> -PspecDump=/tmp/before
# change the emitter
./gradlew -q :tsl:compileSpecTree -PspecTree=<corpus> -PspecDump=/tmp/after
python3 doc/core/tsl-migration/semdiff.py /tmp/before /tmp/after
```

**Run this for any change to `python_ast.kt` or `python_classes.kt`.** On 2026-08-16 the obvious fix
to `PyStr` — escape everything properly — measured as changing the meaning of **18 of 720** live
exercises: specs store `\n` and rely on the generated literal turning it into a newline, so "proper"
escaping would have put a literal backslash-n into the middle of students' feedback. The fix that
shipped changes 2, and both restore characters that were being silently dropped. That number is not
obtainable by reading the emitter.

## Afterwards

- **Exercise 741 is still on the old model** wherever this has been run, so it is the one exercise
  that 400s when saved — as all 189 did before. Nothing regressed; it is simply the last one, and it
  needs a `text_md` before it can go through the API at all.
- Drop the legacy handlers from tiivad, and the back-compat that made this migration unhurried.
- Re-pick the add-test presets in `web/src/features/library/tsl/tslPresets.ts` against
  `out/summary.txt`. They were chosen from a guess at which test types teachers use; the histogram
  is the real answer, and it disagrees — `defines a function` is the most-used retired type by a
  wide margin and has no preset, while "Uses try/except" has one and five uses.
- EZ-1742 collects the model cleanups this exercise surfaced, for muuli.

## Run history

**dev, 2026-08-12** — the whole thing, end to end, against `easyems_dev` on a core running
`99ea668e`. 721 exercises exported, 189 needing migration, 810 tests converted; 532 compiled before
and 721 after; **188 written**, 741 skipped. Afterwards: 720 on the new model, 1 (741) left, and the
post-migration corpus compiles 720 with 741 the only failure.

Attribution held across all 188: no author changed, none became the admin who ran it, every
`valid_from` moved by exactly 1 ms, and nothing lost `text_adoc` or text. Eight teachers' names and
dates from 2023-09-06 to 2026-01-30 survived untouched.

Two things that only showed up against the real thing, both now fixed:

- **`writeback.py` crashed on the first non-strict spec.** It compared the live spec with
  `json.loads`, which 36 of the dev specs are rejected by — the exact failure `summary.txt` warns
  about, in the one script that had not been taught about it. It now uses `explode.py`'s
  `parse_spec`. It died before writing anything, so the cost was a dry run.
- **The dev realm issues 60-second access tokens.** Shorter than a full run by two orders of
  magnitude, so a pasted token cannot work; raised to 10 minutes on the realm for the run. A full
  `--apply` takes about 3m15s, and the dry run 2m15s, so 10 minutes covers either but not both.

Timings, for planning a production window: ~135s of GETs for 721 exercises, plus roughly 0.35s per
write including `--delay`.

## The bearer path

Proven on the dev run above — `--token` against a real `auth-enabled: true` core, which until then
had never actually run. Before that, everything had been rehearsed with `--dev-user` against a local
auth-disabled core, and the note here said so.

What is still worth doing rather than assuming: step 6's dry run, because it is the cheapest place
to find out the token is wrong, expired, or short of `easy_role`.
