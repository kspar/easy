# TSL spec migration

EZ-1607 collapsed 39 TSL test types into 4. Specs already in the database still use the old ones
and no longer deserialize at all, so an exercise that opens fine today fails the moment it is
saved. These scripts pull every TSL exercise out of production, rewrite the ones that need it, and
check the result before it goes anywhere near the database.

All four steps are read-only against production. Nothing here writes to a database.

## Handling the data

**Everything under `out/exercises/` is production content** — exercise titles, and specs whose
feedback messages were written by teachers. This repo and the YouTrack instance are both public.
Keep exports, migrated specs and diffs on your machine.

`out/summary.txt` is the only file written to be shareable: counts, container images, and how
often each test type is used. Nothing in it names an exercise.

## 1. Export

Read-only. No DDL, no writes, no transaction.

```sh
psql -h <host> -U <user> -d <db> -Atq -f export.sql > /tmp/tsl-export.jsonl
```

`-Atq` is load-bearing: unaligned, tuples-only, quiet. Without them psql prints a header, a row
count, and a confirmation line for every setting, straight into the middle of the JSONL.

One line per current exercise version (`valid_to IS NULL`), with the assets nested inside it so
that file contents — which are full of newlines — stay within one JSON string on one line.

The filter is `container_image_id LIKE '%tsl%'` rather than an equality check, so it catches
`tiivad:tsl-spec` (legacy YAML) alongside `tiivad:tsl-compose`, and anything else we have
forgotten about. An exercise on an unexpected container is precisely the one a migration would
miss and a teacher would then break on their next save.

## 2. Explode into a directory per exercise

```sh
python3 explode.py --export /tmp/tsl-export.jsonl --out ./out
```

```
out/
  summary.txt                  counts + test-type histogram        shareable
  exercises/<id>/meta.json     ids, title, container, grading script
  exercises/<id>/tsl.json      the spec, byte for byte as stored
  exercises/<id>/generated_0.py, meta.txt, …
```

Stdlib only, no dependencies.

The histogram in `summary.txt` is worth reading beyond this migration: it is the first real
evidence of which test types teachers actually use, and the add-test presets in the React editor
(`web/src/features/library/tsl/tslPresets.ts`) were chosen from a guess at that distribution.
Revisit them against it.

## 3. Migrate

```sh
python3 migrate.py --export /tmp/tsl-export.jsonl --out ./migrated
```

Covers the 18 retired types the corpus actually contains — the other 21 the collapse removed were
never used by anyone. Anything unmapped is reported and left untouched rather than guessed at, and
`migrate.py` exits non-zero if it meets one.

**It preserves behaviour, it does not improve it.** Every mapping reproduces what the old compiler
emitted and the old analyser computed. The two that look like approximations and are not:

- **loop / try-except / return → keyword checks.** The old booleans were themselves computed from
  AST node types, and `KEYWORD_TO_AST_NODES` maps the same nodes to the same keywords
  (`for`→`{For, AsyncFor, comprehension}` against `contains_loop_tv`=`For|While|comprehension`).
  `try` OR `except`, not both, because a bare `try/finally` set the old flag. The one divergence is
  `async for`, which now counts as a loop and did not before.
- **calls_print → a call check on `["print"]`.** `calls_print()` was `"print" in
  calls_function_names`; `ALL_OF_THESE` over `["print"]` is the same set containment, and
  `NONE_OF_THESE` is its complement.

Polarity follows one rule taken from the old compiler, which emitted `expected_value` as
`!mustNotX` for every boolean check without exception.

Results against the current corpus: **189 exercises rewritten, 810 tests converted, 532 left
byte-identical.**

## 4. Verify

Works on any migrated tree, whether `migrate.py` produced it or someone else did.

```sh
python3 explode.py --export /tmp/tsl-export.jsonl --verify ./migrated
```

Exits non-zero and lists anything wrong:

- an exercise missing from the migration, or one that was not in the export
- a file other than `tsl.json` having been modified — generated scripts are regenerated on save,
  so editing them is at best discarded and at worst confusing
- a spec that does not parse
- a spec still containing retired test types
- duplicate test ids the migration *introduced*. Not duplicates as such: 174 of 721 production
  specs already have them and work anyway, because the compiler's only validation
  (`validateParseTree`) is never called outside its own `main()`. Flagging those would bury a real
  regression under a quarter of the corpus.

Then compile the result. This is the check that actually proves something, and it needs the merged
`:tsl` — see `Check.kt` usage in the scratch harness, or point the compiler at the tree:

```
compiled OK : 721      (was 532 before migration)
failed      : 0        (was 189)
```

## 5. Writing back

**Prefer `PUT /v2/exercises/{id}` over a SQL update.** Replacing `tsl.json` directly leaves the
old generated Python in place, because the scripts are compiled at save time and stored as
separate assets — so the exercise would keep grading against the old spec until something else
happened to re-save it. Going through the API compiles and stores both in one step, which is the
"replace and recompile" this migration needs, and it fails loudly on a spec the compiler rejects
rather than silently accepting it.

Two things to know before that run:

- **`UpdateExercise` does not catch compile failures** (EZ-1743), so a rejected spec is a 500 with
  no usable message rather than a 400 explaining itself. Worth fixing first if the batch is large.
- **Deploy the merged `:tsl` first.** The new compiler is what understands the migrated specs; the
  currently deployed one does not.

Grading keeps working throughout. Exercises hold their already-generated Python, and tiivad 0.0.33
still routes every legacy test type to its old handler — that back-compat is what makes this
migration unhurried rather than an outage. It can be dropped once this has run.
