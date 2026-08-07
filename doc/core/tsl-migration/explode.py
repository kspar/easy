#!/usr/bin/env python3
"""Turn the JSONL export into one directory per exercise, ready to hand to the migration.

    python3 explode.py --export /tmp/tsl-export.jsonl --out ./out

Produces:

    out/
      summary.txt                 counts and a test-type histogram   -- shareable
      exercises/<id>/meta.json    ids, title, container, grading script
      exercises/<id>/tsl.json     the spec, exactly as stored
      exercises/<id>/<other>      generated_0.py, meta.txt, ...

Everything under `exercises/` is production content: titles, and specs whose messages are written
by teachers. Only summary.txt is written to be shared.

The round trip back: muuli returns a directory of the same shape with `tsl.json` rewritten, and
`--verify` checks that nothing else moved before those specs go anywhere near the database.
"""
import argparse
import json
import pathlib
import re
import sys
from collections import Counter

# The four that replaced 39 (EZ-1607). Anything else in a spec is pre-migration.
NEW_TYPES = {"contains_test", "calls_test", "definition_test", "function_is_test"}
# Survived the collapse untouched, so these need no migration.
UNCHANGED_TYPES = {
    "program_execution_test",
    "function_execution_test",
    "class_instance_test",
    "placeholder_test",
}

# Anchored so a stray "../" in a file_name cannot escape the output directory. Asset names come
# from the database, and the compiler writes them, but this script may well outlive that.
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]+$")


def spec_asset(assets):
    """The TSL spec among an exercise's assets, whatever it is called."""
    for a in assets:
        if a["file_name"] in ("tsl.json", "tsl.yaml", "tsl.YAML"):
            return a
    return None


# A value, then a newline, then the next key — with no comma between them.
MISSING_COMMA = re.compile(r'(["\d\]\}]|true|false|null)(\s*\n\s*)(")')


def parse_spec(content):
    """Parses a stored spec the way production does, and says how strict a parser had to be.

    Returns (spec, leniency) where leniency is 'strict', 'control-chars', 'missing-commas' or
    None when nothing could read it.

    **This matters for anyone writing migration tooling.** kotlinx.serialization is more permissive
    than `json.loads`, and production specs exercise the difference: 36 of them are rejected by
    Python and accepted by Kotlin, so they compile and grade perfectly well today. Treating them as
    corrupt would drop real exercises from the migration; rewriting them as strict JSON is fine and
    is what saving through the API will do anyway.
    """
    try:
        return json.loads(content), "strict"
    except json.JSONDecodeError:
        pass
    try:
        # Literal newlines and tabs inside strings. Python refuses; Kotlin does not care.
        return json.loads(content, strict=False), "control-chars"
    except json.JSONDecodeError:
        pass
    try:
        # Missing commas between object members. Verified against the real compiler: kotlinx parses
        # `{"id":1 "name":null}` without complaint, on the same default config production uses.
        return json.loads(MISSING_COMMA.sub(r"\1,\2\3", content), strict=False), "missing-commas"
    except json.JSONDecodeError:
        return None, None


def verify(rows, migrated: pathlib.Path) -> int:
    """Checks a returned migration against the export. Returns the number of problems found.

    The point is to catch, before anything is written to a database, the three things that would
    be expensive to discover afterwards: an exercise that went missing, a file other than the spec
    having been edited, and a spec that still contains types the model no longer has.
    """
    problems = []
    by_id = {int(r["exercise_id"]): r for r in rows}
    returned = {int(p.name) for p in (migrated / "exercises").iterdir() if p.is_dir() and p.name.isdigit()}

    for missing in sorted(set(by_id) - returned):
        problems.append(f"{missing}: in the export but not in the migration")
    for extra in sorted(returned - set(by_id)):
        problems.append(f"{extra}: in the migration but not in the export")

    for eid in sorted(set(by_id) & returned):
        original = {a["file_name"]: a["file_content"] for a in by_id[eid]["assets"]}
        d = migrated / "exercises" / str(eid)

        for f in sorted(d.iterdir()):
            if f.name in ("meta.json", "tsl.json"):
                continue
            # Generated scripts are regenerated on save, so editing them here is at best pointless
            # and at worst a change that gets silently thrown away.
            if f.name in original and f.read_text(encoding="utf-8") != original[f.name]:
                problems.append(f"{eid}: {f.name} was modified; only tsl.json should change")

        spec_path = d / "tsl.json"
        if not spec_path.exists():
            # A legacy YAML exercise has a tsl.yaml and never had a tsl.json to rewrite.
            if any(a["file_name"].lower().endswith((".yaml", ".yml")) for a in by_id[eid]["assets"]):
                continue
            problems.append(f"{eid}: no tsl.json in the migration")
            continue
        try:
            spec = json.loads(spec_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            problems.append(f"{eid}: tsl.json does not parse — {e}")
            continue

        tests = spec.get("tests", [])
        stale = sorted({t.get("type") for t in tests if isinstance(t, dict)} - NEW_TYPES - UNCHANGED_TYPES)
        if stale:
            problems.append(f"{eid}: still uses retired test types: {', '.join(stale)}")

        # The compiler has a duplicate-id check that is never called outside its own main(), so
        # this is the only place it runs before a spec reaches production.
        #
        # Reported only when the migration *introduced* them. 174 of 721 production specs already
        # have duplicate ids and work anyway, so flagging those would bury a real regression under
        # a quarter of the corpus — and would be asking a migration to fix something that is not
        # its job.
        ids = [t.get("id") for t in tests if isinstance(t, dict)]
        dupes = {i for i in ids if ids.count(i) > 1}
        before, _ = parse_spec(next(
            (a["file_content"] for a in by_id[eid]["assets"] if a["file_name"] == "tsl.json"), "null"
        ))
        old_ids = [t.get("id") for t in (before or {}).get("tests", []) if isinstance(t, dict)]
        already = {i for i in old_ids if old_ids.count(i) > 1}
        introduced = sorted(dupes - already)
        if introduced:
            problems.append(f"{eid}: migration introduced duplicate test ids {introduced}")

    for p in problems:
        print(f"  {p}")
    print(f"\n{len(problems)} problem(s) across {len(returned)} migrated exercises")
    return len(problems)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True, type=pathlib.Path)
    ap.add_argument("--out", type=pathlib.Path, help="write the exploded tree here")
    ap.add_argument("--verify", type=pathlib.Path, help="check a returned migration against the export")
    args = ap.parse_args()

    rows = [json.loads(line) for line in args.export.read_text(encoding="utf-8").splitlines() if line.strip()]

    if args.verify:
        sys.exit(1 if verify(rows, args.verify) else 0)
    if not args.out:
        ap.error("one of --out or --verify is required")

    ex_dir = args.out / "exercises"
    ex_dir.mkdir(parents=True, exist_ok=True)

    types = Counter()
    containers = Counter()
    leniency = Counter()
    no_spec, yaml_specs, unparsable, needs_migration, already_new = [], [], [], [], []

    for row in rows:
        eid = int(row["exercise_id"])
        d = ex_dir / str(eid)
        d.mkdir(exist_ok=True)
        containers[row["container_image"]] += 1

        assets = row.pop("assets")
        (d / "meta.json").write_text(json.dumps(row, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        for a in assets:
            name = a["file_name"]
            if not SAFE_NAME.match(name):
                print(f"  skipping unsafe asset name on exercise {eid}: {name!r}", file=sys.stderr)
                continue
            (d / name).write_text(a["file_content"], encoding="utf-8")

        spec = spec_asset(assets)
        if spec is None:
            no_spec.append(eid)
            continue
        if not spec["file_name"].lower().endswith(".json"):
            # Legacy YAML specs. Still exported, but they are a different parse and a different
            # decision — flagged rather than silently counted as JSON.
            yaml_specs.append(eid)
            continue
        parsed, how = parse_spec(spec["file_content"])
        if parsed is None:
            unparsable.append(eid)
            continue
        leniency[how] += 1
        tests = parsed.get("tests", [])

        found = [t.get("type") for t in tests if isinstance(t, dict)]
        types.update(found)
        old = [t for t in found if t not in NEW_TYPES and t not in UNCHANGED_TYPES]
        (needs_migration if old else already_new).append(eid)

    lines = [
        "TSL migration export",
        "=" * 60,
        f"exercises exported          {len(rows)}",
        f"  need migration            {len(needs_migration)}",
        f"  already on the new model  {len(already_new)}",
        f"  legacy YAML spec          {len(yaml_specs)}",
        f"  no spec asset             {len(no_spec)}",
        f"  unreadable by any parser  {len(unparsable)}",
        "",
        "how strict a JSON parser the spec needs",
        "  kotlinx accepts all of these; json.loads does not. Migration tooling written in",
        "  Python or JS must allow for the last two or it will drop real exercises.",
        f"  strict JSON                       {leniency['strict']}",
        f"  literal control chars in strings  {leniency['control-chars']}",
        f"  missing commas between members    {leniency['missing-commas']}",
        "",
        "container images",
        *(f"  {c:<28} {n}" for c, n in containers.most_common()),
        "",
        "test types in use, most common first",
        "  (this is the frequency table the add-test presets should be chosen against)",
    ]
    for t, n in types.most_common():
        tag = "new" if t in NEW_TYPES else ("kept" if t in UNCHANGED_TYPES else "RETIRED")
        lines.append(f"  {t:<40} {n:>6}  {tag}")

    (args.out / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\nwrote {len(rows)} exercise directories to {ex_dir}")


if __name__ == "__main__":
    main()
