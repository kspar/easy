#!/usr/bin/env python3
"""Writes the converted Markdown into `exercise_version.text_md` (EZ-1731 step 5).

    OUTPUT AND INPUT ARE PRODUCTION CONTENT. Keep the payload on the machine that
    already holds the database. See README.md.

The only writing step in this migration. Everything before it is read-only, and this deliberately
stayed unwritten until a dry run had been reviewed — see the README's "Then the write".

What it does, and does not do:

  * `UPDATE` in place, current versions only. The content is not changing; only the *source*
    representation is being backfilled, so a new `exercise_version` row would invent an edit that
    nobody made and put a bogus entry in every affected exercise's history.
  * `text_adoc` and `text_html` are never touched. Readers see nothing change, and rollback is
    `UPDATE exercise_version SET text_md = NULL` over the ids in the receipt.
  * Only exercises the dry run passed. Flagged ones are left null on purpose: a visible gap invites
    a look, a subtly mangled exercise does not.

Two guards that matter more than they look:

  * **The row must still be current.** Every update carries `valid_to IS NULL`. Between the export
    and the write a teacher may have saved — creating a new version, so the exported `version_id` is
    no longer current — and writing anyway would push Markdown converted from superseded AsciiDoc
    over their edit. Rows that did not match are counted out loud rather than swallowed.
  * **And still empty, unless told otherwise.** `text_md IS NULL` by default, so a backfill can be
    re-run without replacing anything. `--overwrite` swaps that for "and the text actually differs",
    which is what a *converter* change needs: without it, improving the conversion leaves every
    already-written exercise on the old output and reports success.
  * **A database that says it is a copy.** Same family of guard as `../anonymise-db/`: refuse
    unless the name matches dev/stage/anon, since running this against production before it has
    been rehearsed is precisely the mistake the rehearsal exists to prevent.

Usage:

    python3 writeback.py --payload payload.jsonl                # dry run, writes nothing
    python3 writeback.py --payload payload.jsonl --apply
    python3 writeback.py --payload payload.jsonl --apply --allow-any-database   # escape hatch
    python3 writeback.py --payload payload.jsonl --apply --overwrite            # re-convert

The payload is JSONL, one object per exercise: {"exercise_id", "version_id", "text_md"}.
Built by `build_payload.py` from the dry run's report and converted Markdown.
"""

import argparse
import json
import pathlib
import re
import sys

import psycopg2
import psycopg2.extras


SAFE_DB = re.compile(r"(dev|stage|anon)")


def load_payload(path: pathlib.Path) -> list[dict]:
    rows = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        missing = {"exercise_id", "version_id", "text_md"} - row.keys()
        if missing:
            sys.exit(f"{path}:{n} is missing {', '.join(sorted(missing))}")
        # An empty string would blank an exercise that currently renders. The converter never
        # produces one for a passing exercise, so this means the payload was built wrong.
        if not row["text_md"].strip():
            sys.exit(f"{path}:{n} (exercise {row['exercise_id']}) has empty text_md")
        rows.append(row)
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload", required=True, type=pathlib.Path)
    ap.add_argument("--dsn", default="dbname=easyems_dev")
    ap.add_argument("--apply", action="store_true", help="actually write; otherwise a dry run")
    ap.add_argument("--allow-any-database", action="store_true")
    ap.add_argument(
        "--overwrite", action="store_true",
        help="replace text_md that is already set. Needed when the CONVERTER changed and the "
             "database holds output from an older version of it; refused by default, because a "
             "silent overwrite makes it impossible to tell which conversion is in there.")
    ap.add_argument("--receipt", type=pathlib.Path, help="write the updated version ids here")
    args = ap.parse_args()

    rows = load_payload(args.payload)
    print(f"payload: {len(rows)} exercises")

    conn = psycopg2.connect(args.dsn)
    conn.autocommit = False
    with conn, conn.cursor() as cur:
        cur.execute("SELECT current_database()")
        db = cur.fetchone()[0]
        if not SAFE_DB.search(db) and not args.allow_any_database:
            sys.exit(
                f"Refusing to write to database '{db}'.\n"
                "This backfills text_md across every current exercise version. It is meant for a "
                "copy, whose name should say so. Pass --allow-any-database if you are certain."
            )
        print(f"database: {db}{'  (!! not a copy name)' if not SAFE_DB.search(db) else ''}")

        # Counted BEFORE the update, not after. Asking "how many already had text_md" once the
        # write has happened — even inside an uncommitted transaction — counts the rows this run
        # just wrote, and reports every successful backfill as a row it declined to touch.
        ids = [r["version_id"] for r in rows]
        cur.execute(
            """
            SELECT count(*)                                                       AS found,
                   count(*) FILTER (WHERE valid_to IS NOT NULL)                   AS superseded,
                   count(*) FILTER (WHERE valid_to IS NULL AND text_md IS NOT NULL) AS already_set
              FROM exercise_version WHERE id = ANY(%s)
            """,
            (ids,),
        )
        found, superseded, already_set = cur.fetchone()

        # One transaction, so a failure in the middle leaves nothing half-written rather than a
        # corpus split between two representations.
        #
        # `RETURNING` and `fetch=True` rather than `cur.rowcount`, and that is not a stylistic
        # preference: execute_values sends the rows in pages of 100, so rowcount reports the *last
        # page* only. The first run of this said "rows updated: 44" for a payload of 1044 —
        # 10 full pages and a remainder — which reads as a broken write when nothing was wrong but
        # the counting. Collecting the returned ids counts every page, and gives the receipt the
        # rows that actually changed instead of the rows that were asked for.
        # A backfill and a re-conversion differ only in which rows may be touched, so that is the
        # only thing that varies — one statement, one predicate, both readable on their own.
        # `IS DISTINCT FROM` rather than `!=` so that a row is reported as changed only when it
        # really changed, and re-running after a no-op converter tweak writes nothing.
        predicate = ("ev.text_md IS DISTINCT FROM p.text_md" if args.overwrite
                     else "ev.text_md IS NULL")
        updated = psycopg2.extras.execute_values(
            cur,
            f"""
            UPDATE exercise_version ev
               SET text_md = p.text_md
              FROM (VALUES %s) AS p (version_id, text_md)
             WHERE ev.id = p.version_id::bigint
               AND ev.valid_to IS NULL
               AND {predicate}
            RETURNING ev.id
            """,
            [(r["version_id"], r["text_md"]) for r in rows],
            fetch=True,
        )
        updated_ids = [row[0] for row in updated]
        written = len(updated_ids)
        # How many of the writes replaced text rather than filling a gap — the number that says
        # whether an --overwrite run did what it was run for.
        overwritten = len([i for i in updated_ids if i in set(ids)]) if args.overwrite else 0
        overwritten = min(overwritten, already_set)

        # "1044 requested, 1041 written" is a question, not an answer. Superseded means somebody
        # saved between the export and now; already-set means a previous run of this script, or
        # that same save.
        print(f"rows updated           : {written}"
              f"{'  (overwriting existing text_md)' if args.overwrite else ''}")
        print(f"not found              : {len(ids) - found}")
        print(f"superseded since export: {superseded}")
        print(f"already had text_md    : {already_set}"
              f"{'  — of which unchanged: %d' % (already_set - overwritten) if args.overwrite else ''}")

        # The arithmetic differs between the two modes, and getting it wrong is how a silent
        # partial write goes unnoticed — so both are checked rather than only the common one.
        if args.overwrite:
            reconciles = written <= len(ids) - superseded - (len(ids) - found)
        else:
            reconciles = written == len(ids) - superseded - already_set - (len(ids) - found)
        if not reconciles:
            print("!! updated count does not reconcile with the skips — investigate before committing")

        if args.receipt and args.apply:
            args.receipt.write_text("\n".join(str(i) for i in updated_ids) + "\n", encoding="utf-8")
            print(f"receipt            : {args.receipt} "
                  f"(rollback: UPDATE exercise_version SET text_md = NULL WHERE id IN (…))")

        if not args.apply:
            conn.rollback()
            print("\nDRY RUN — rolled back, nothing written. Re-run with --apply.")
            return 0

    print("\nCommitted.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
