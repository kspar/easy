#!/usr/bin/env python3
"""Compares two directories of generated TSL scripts by what they *mean*, not by their text.

    ./gradlew -q :tsl:compileSpecTree -PspecTree=<corpus> -PspecDump=/tmp/before
    # ... change the emitter ...
    ./gradlew -q :tsl:compileSpecTree -PspecTree=<corpus> -PspecDump=/tmp/after
    python3 doc/core/tsl-migration/semdiff.py /tmp/before /tmp/after

**Why by AST and not by diff.** `ast.dump` normalises literal *representation* away — `'''a'''` and
`'a'` both become `Constant(value='a')` — so a difference here is a difference in what the script
does. Text diffing answers a question nobody has: changing the quote style rewrites all 720 files
and changes nothing.

**Why this exists.** On 2026-08-16 the obvious fix to `PyStr` — escape everything properly — was
written, and this said it changed the meaning of 18 of 720 live exercises. Specs store `\\n` and
rely on the generated literal turning it into a newline; "proper" escaping would have put the two
characters backslash and n into the middle of students' feedback. The fix that shipped changes 2,
and both of those restore characters that were being silently dropped.

That number is not obtainable by reading the emitter, and it is the difference between a fix and an
incident. Any change to `python_ast.kt` or `python_classes.kt` should come with it.

Exit code is 0 if nothing changed meaning, 1 otherwise, so it can gate a script.
"""
import argparse
import ast
import pathlib
import sys


def dump(path: pathlib.Path) -> str | None:
    """The AST, or None if it does not parse. Unparseable is a finding, not a crash."""
    try:
        return ast.dump(ast.parse(path.read_text(encoding="utf-8")))
    except SyntaxError:
        return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("before", type=pathlib.Path)
    ap.add_argument("after", type=pathlib.Path)
    ap.add_argument("--show", type=int, default=10, help="how many changed ids to list (default 10)")
    args = ap.parse_args()

    before_files = sorted(args.before.glob("*.py"))
    if not before_files:
        # An empty scan reports "0 changed", which is indistinguishable from a clean result and is
        # the exact failure this whole programme keeps finding. Refuse instead.
        print(f"No .py files in {args.before} — nothing was compared.", file=sys.stderr)
        return 2

    same, changed, missing = 0, [], []
    broke, repaired, still_broken = [], [], []

    for f in before_files:
        g = args.after / f.name
        if not g.exists():
            missing.append(f.stem)
            continue

        a, b = dump(f), dump(g)

        # Four states, and only one of them is this change's fault. A script that could not parse
        # before and still cannot is somebody else's bug — counting it as a failure here would make
        # the tool cry wolf on every run against a corpus with one bad spec in it, which is exactly
        # the corpus we have.
        if a is None and b is None:
            still_broken.append(f.stem)
        elif a is not None and b is None:
            broke.append(f.stem)
        elif a is None and b is not None:
            repaired.append(f.stem)
        elif a == b:
            same += 1
        else:
            changed.append(f.stem)

    def show(label: str, ids: list[str]) -> None:
        if ids:
            head = ids[: args.show]
            print(f"{label:<20}: {len(ids)}  {head}{' …' if len(ids) > len(head) else ''}")

    print(f"{'identical meaning':<20}: {same}")
    print(f"{'changed meaning':<20}: {len(changed)}")
    if changed:
        head = changed[: args.show]
        print(f"  {head}{' …' if len(changed) > len(head) else ''}")
    show("STOPPED parsing", broke)
    show("started parsing", repaired)
    show("broken before+after", still_broken)
    show("missing in after", missing)

    return 0 if not (changed or missing or broke) else 1


if __name__ == "__main__":
    sys.exit(main())
