#!/usr/bin/env python3
"""Turns a dry run into a write payload (EZ-1731 step 5).

    OUTPUT IS PRODUCTION CONTENT — exercise text, one object per line. Keep it local.

Joins three things the dry run leaves behind:

  * `report.jsonl`  — the verdict per exercise
  * `work/md/*.md`  — the converted Markdown
  * the original export — the only place carrying `version_id`, which is what the write targets

Version id and not exercise id, deliberately. An exercise has many versions and only one current
one; naming the row the conversion was actually derived from is what lets `writeback.py` refuse to
write over an edit made since the export, rather than silently retargeting at whatever is current
by then.

Which exercises are included is a decision, not a default — see `--include`:

    --include ok            only what converted cleanly
    --include ok,math       ...plus the maths, whose only flagged difference is the delimiter
                            (\\(x\\) becomes $x$, which is what KaTeX and MathJax expect anyway)
    --include all-flagged   everything the converter produced output for. Not advisable: it takes
                            the exercises that lose a block title along with it.

Usage:

    python3 build_payload.py --out ./out --export /tmp/export.jsonl \\
                             --include ok,math --payload payload.jsonl
"""

import argparse
import json
import pathlib
import sys


MATH_REASON = "math delimiters only (EZ-1732)"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=pathlib.Path, help="the dry run's --out directory")
    ap.add_argument("--export", required=True, type=pathlib.Path)
    ap.add_argument("--payload", required=True, type=pathlib.Path)
    ap.add_argument("--include", default="ok", help="ok | ok,math | all-flagged")
    args = ap.parse_args()

    include = {p.strip() for p in args.include.split(",") if p.strip()}
    unknown = include - {"ok", "math", "all-flagged"}
    if unknown:
        sys.exit(f"unknown --include value(s): {', '.join(sorted(unknown))}")

    version_of = {}
    for line in args.export.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            version_of[row["exercise_id"]] = row["version_id"]

    report = [json.loads(l) for l in (args.out / "report.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]

    chosen, skipped = [], {}
    for r in report:
        ex, status, reason = r["exercise_id"], r["status"], r.get("reason") or ""
        if status == "ok":
            take = "ok" in include
        elif reason == MATH_REASON:
            take = "math" in include or "all-flagged" in include
        else:
            take = "all-flagged" in include
        if not take:
            skipped[reason or status] = skipped.get(reason or status, 0) + 1
            continue

        md_path = args.out / "work" / "md" / f"{ex}.md"
        if not md_path.exists():
            # The one exercise whose conversion produced no output has no file. Anything else
            # missing means the report and the working directory are from different runs.
            skipped["no converted markdown"] = skipped.get("no converted markdown", 0) + 1
            continue
        text = md_path.read_text(encoding="utf-8")
        if not text.strip():
            skipped["converted markdown is empty"] = skipped.get("converted markdown is empty", 0) + 1
            continue
        if ex not in version_of:
            skipped["not in the export"] = skipped.get("not in the export", 0) + 1
            continue

        chosen.append({"exercise_id": ex, "version_id": version_of[ex], "text_md": text})

    with args.payload.open("w", encoding="utf-8") as f:
        for row in chosen:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(f"included: {len(chosen)}")
    for reason, n in sorted(skipped.items(), key=lambda kv: -kv[1]):
        print(f"  skipped {n:4}  {reason}")
    print(f"payload: {args.payload}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
