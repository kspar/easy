#!/usr/bin/env python3
"""Writes migrated TSL specs back through the API, one exercise at a time.

    # see what would happen, touching nothing
    python3 writeback.py --migrated ./migrated --url https://... --token "$TOKEN"

    # actually do it
    python3 writeback.py --migrated ./migrated --url https://... --token "$TOKEN" --apply

Dry run by default. `--apply` is the only thing that writes.

**Why it re-reads each exercise instead of using the export.** `PUT /v2/exercises/{id}` replaces
the whole exercise, and requires `title`, `grader_type`, `solution_file_name` and
`solution_file_type` — none of which the export captured, along with `text_md`. Rebuilding the
request from export data would either fail validation or, with plausible-looking defaults, quietly
blank an exercise's text. So every field except the spec comes from a GET taken moments before the
PUT, and the export is used only to know *which* exercises to touch.

**Why it sends only `tsl.json`.** The server appends the freshly compiled scripts to whatever
assets the request carries (`req.assets + compileResult.scripts + metaScript`). Sending back the
`generated_0.py` that the GET returned would therefore store it *twice*, once stale. The web
editor has the same behaviour and the same rule.

Safety properties, in the order they matter:

- nothing is written without `--apply`
- it stops at the first failure rather than continuing through 189 exercises
- already-migrated exercises are skipped, so a re-run after a stall resumes rather than repeats
- every id it touched is appended to a log as it goes, so an interrupted run is reconstructable
- it refuses to run if the spec it is about to write still contains retired types
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

RETIRED_MARKERS = ("_contains_", "_calls_", "_defines_", "_imports_", "_is_recursive", "_is_pure")
NEW_TYPES = {"contains_test", "calls_test", "definition_test", "function_is_test"}
KEPT = {"program_execution_test", "function_execution_test", "class_instance_test", "placeholder_test"}


def is_retired(test_type):
    if test_type in NEW_TYPES or test_type in KEPT:
        return False
    return any(m in test_type for m in RETIRED_MARKERS)


def request(url, headers, method="GET", body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw[:400]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--migrated", required=True, type=pathlib.Path)
    ap.add_argument("--url", required=True, help="core base URL, e.g. https://host/v2")
    ap.add_argument("--token", help="bearer token for a teacher/admin account")
    ap.add_argument(
        "--dev-user",
        help="username for oidc_claim_* headers instead of a token — only works against a core "
             "run with easy.core.auth-enabled=false, which is how this script is tested locally",
    )
    ap.add_argument("--apply", action="store_true", help="actually write; omit for a dry run")
    ap.add_argument("--limit", type=int, help="stop after this many exercises")
    ap.add_argument("--delay", type=float, default=0.2, help="seconds between writes")
    ap.add_argument("--log", type=pathlib.Path, default=pathlib.Path("writeback.log"))
    args = ap.parse_args()

    if bool(args.token) == bool(args.dev_user):
        ap.error("exactly one of --token or --dev-user is required")
    headers = (
        {"Authorization": f"Bearer {args.token}"}
        if args.token
        else {
            "oidc_claim_preferred_username": args.dev_user,
            "oidc_claim_email": f"{args.dev_user}@test.ee",
            "oidc_claim_easy_role": "teacher,admin",
        }
    )

    base = args.url.rstrip("/")
    dirs = sorted(
        (p for p in (args.migrated / "exercises").iterdir() if p.is_dir() and p.name.isdigit()),
        key=lambda p: int(p.name),
    )

    if not args.apply:
        print("DRY RUN — nothing will be written. Add --apply to write.\n")

    written = skipped = 0
    for i, d in enumerate(dirs):
        if args.limit and written >= args.limit:
            print(f"\nstopping at --limit {args.limit}")
            break

        eid = d.name
        spec_path = d / "tsl.json"
        if not spec_path.exists():
            continue
        spec_text = spec_path.read_text(encoding="utf-8")

        # Refuse to write a spec that has not actually been migrated. Cheap here, and the
        # alternative is a 400 from the server after the write has already been attempted.
        try:
            tests = json.loads(spec_text).get("tests", [])
        except json.JSONDecodeError as e:
            print(f"{eid}: ABORT — migrated spec does not parse: {e}")
            return 1
        stale = sorted({t.get("type") for t in tests if isinstance(t, dict) and is_retired(t.get("type"))})
        if stale:
            print(f"{eid}: ABORT — spec still uses retired types: {', '.join(stale)}")
            return 1

        status, current = request(f"{base}/exercises/{eid}", headers)
        if status != 200:
            print(f"{eid}: ABORT — GET returned {status}: {json.dumps(current)[:200]}")
            return 1

        live = next((a for a in (current.get("assets") or []) if a["file_name"] == "tsl.json"), None)
        if live is None:
            print(f"{eid}: skip — no tsl.json on the server (legacy YAML?)")
            skipped += 1
            continue
        if json.loads(live["file_content"]) == json.loads(spec_text):
            # Already the migrated spec: either it never needed migrating, or a previous run got
            # this far. Either way there is nothing to do, which is what makes a re-run safe.
            skipped += 1
            continue

        # Everything but the spec comes from the server, so nothing this script did not read can
        # be lost. Assets carry the spec alone — the server regenerates the rest and would
        # otherwise store the stale copy alongside the new one.
        payload = {
            "title": current["title"],
            "text_md": current.get("text_md"),
            "grader_type": current["grader_type"],
            "solution_file_name": current["solution_file_name"],
            "solution_file_type": current["solution_file_type"],
            "grading_script": current.get("grading_script"),
            "container_image": current.get("container_image"),
            "max_time_sec": current.get("max_time_sec"),
            "max_mem_mb": current.get("max_mem_mb"),
            "assets": [{"file_name": "tsl.json", "file_content": spec_text}],
        }

        if not args.apply:
            print(f"{eid}: would write ({len(tests)} tests)")
            written += 1
            continue

        status, resp = request(f"{base}/exercises/{eid}", headers, method="PUT", body=payload)
        if status != 200:
            print(f"{eid}: FAILED {status}: {json.dumps(resp)[:400]}")
            print(f"\nStopped at exercise {eid}. {written} written so far; see {args.log}.")
            return 1

        with args.log.open("a", encoding="utf-8") as f:
            f.write(f"{eid}\n")
        written += 1
        print(f"{eid}: written ({written}/{len(dirs)})")
        time.sleep(args.delay)

    verb = "would write" if not args.apply else "written"
    print(f"\n{verb}: {written}    skipped (already current): {skipped}")
    if not args.apply and written:
        print("Re-run with --apply to write.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
