#!/usr/bin/env python3
"""
EZ-1731 dry run: convert exercise AsciiDoc to Markdown and check nothing changed.

Writes NOTHING to any database. Reads the export produced by export.sql, converts each
exercise, renders the result back to HTML through core's own MarkdownService, and compares
against the HTML production is already serving. Reports how many convert cleanly and which
need a human.

    python3 dry_run.py --export export.jsonl --out ./out

Needs:
  * Docker — asciidoctor and pandoc run in throwaway containers, nothing is installed
  * A local core with `easy.core.auth-enabled: false` on 127.0.0.1, for /v2/preview/markdown.
    That endpoint is the only faithful way to render Markdown exactly as production will:

      java -jar core/build/libs/core-1.jar --server.port=8099 --server.address=127.0.0.1 \
           --easy.core.auth-enabled=false --easy.core.cors.allowed-origins=

OUTPUT HANDLING — read this before sharing anything:
  out/summary.txt    aggregates only. Safe to share once kspar has seen it.
  out/report.jsonl   per-exercise verdicts, includes titles and text. LOCAL ONLY.
  out/md/*.md        converted Markdown. LOCAL ONLY.
  out/flagged/*.diff text diffs for exercises needing review. LOCAL ONLY.
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from collections import Counter

ASCIIDOCTOR_IMAGE = "asciidoctor/docker-asciidoctor"
PANDOC_IMAGE = "pandoc/core"

# Easy's own inline markup, consumed by the deleted EasyCodeProcessor. Highlighting only, and
# nothing in web/ has styled it since the WUI was removed — so the text is kept, the marker
# dropped. See EZ-1731.
EASY_INLINE = re.compile(r"\$(run|in|nohl)\[(.+?)(?<!\\)\]", re.S)
# The matching attribute references, which the same processor defined as span open/close tags.
# Passed to asciidoctor as empty attributes rather than stripped, so that attribute-missing
# behaviour cannot drop the whole line.
EASY_ATTRS = ["run", "nur", "in", "ni", "nohl", "lhon"]

# commonmark-java has no GitHub-alert support, so `> [!NOTE]` would reach students literally.
GH_ALERT = re.compile(r"^>\s*\[!(\w+)\]\s*\n>\s*", re.M)

# Asciidoctor writes image URLs into docbook attributes without escaping `&`, so an image whose
# src has a query string (`?action=download&upname=…`) produces XML that pandoc correctly refuses
# to parse. Escapes bare ampersands — one that is already part of an entity is left alone.
BARE_AMP = re.compile(r"&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)")

# Asciidoctor auto-numbers block captions — "Figure 1.", "Tabel 1." — above titled tables,
# figures and examples. Pandoc keeps the caption text and drops the generated label. kspar has
# said the labels are not wanted, so they are removed from BOTH sides before comparing: an
# exercise whose only difference is a missing label has converted correctly. Stripping both sides
# rather than just production also means the words "Tabel 1." occurring in ordinary prose cannot
# turn into a spurious mismatch.
CAPTION_LABEL = re.compile(r"\b(?:Figure|Table|Example|Joonis|Tabel|Näide|Näited)\s+\d+\.\s*")

CODEHL_SPAN = re.compile(r'<span class="codehl[^"]*">(.*?)</span>', re.S)
TAG = re.compile(r"<[^>]+>")
ADMONITION_LABEL = re.compile(r"\b(Note|Tip|Warning|Important|Caution):")


def strip_easy_inline(adoc: str) -> str:
    return EASY_INLINE.sub(lambda m: m.group(2).replace("\\]", "]"), adoc)


def fix_alerts(md: str) -> str:
    return GH_ALERT.sub(lambda m: f"> **{m.group(1).capitalize()}:** ", md)


def visible_text(html: str) -> str:
    """Normalised visible text, for comparing a re-render against what production serves.

    Compares text rather than markup on purpose: Asciidoctor wraps every <li> in a <p> and
    Markdown does not, so structural comparison produces false alarms on almost every list.
    """
    html = CODEHL_SPAN.sub(r"\1", html)
    text = TAG.sub(" ", html)
    for entity, char in (("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&"), ("&quot;", '"'),
                         ("&#39;", "'"), ("&nbsp;", " ")):
        text = text.replace(entity, char)
    text = ADMONITION_LABEL.sub(r"\1", text)  # the blockquote rewrite adds a colon
    text = CAPTION_LABEL.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


MATH_DELIM = re.compile(r"(\\\(|\\\)|\\\[|\\\]|\$)")


def math_delimiters_only(before: str, after: str) -> bool:
    """True when two texts differ solely in how math is delimited.

    Asciidoctor emits MathJax delimiters (`\\(x\\)`); pandoc emits `$x$`. The formula itself
    survives, so this is a known and uninteresting class of difference — worth separating from
    failures nobody has explained yet. Neither form renders today (EZ-1732).
    """
    def strip(s: str) -> str:
        s = MATH_DELIM.sub("", s)
        s = re.sub(r"\s+", " ", s)
        # Removing a delimiter can leave the space that sat around it, so `…16$.` becomes
        # `16 .`. Only affects which label a flagged exercise gets, never whether it flags.
        return re.sub(r"\s+([.,;:!?])", r"\1", s).strip()

    return strip(before) == strip(after)


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def convert_all(rows: list[dict], work: pathlib.Path) -> dict[int, dict]:
    """adoc -> docbook -> gfm, two container invocations for the whole corpus rather than
    two per exercise. Per-file containers would turn a minute into most of an hour."""
    adoc_dir, db_dir, md_dir = work / "adoc", work / "docbook", work / "md"
    for d in (adoc_dir, db_dir, md_dir):
        d.mkdir(parents=True, exist_ok=True)

    for row in rows:
        (adoc_dir / f"{row['exercise_id']}.adoc").write_text(
            strip_easy_inline(row["text_adoc"] or ""), encoding="utf-8")

    attrs = []
    for a in EASY_ATTRS:
        attrs += ["-a", f"{a}="]
    print(f"  asciidoctor: {len(rows)} files -> docbook", flush=True)
    r = run(["docker", "run", "--rm", "-v", f"{work}:/w", "-w", "/w", ASCIIDOCTOR_IMAGE,
             "sh", "-c",
             "asciidoctor -b docbook --failure-level=FATAL -o /dev/null /dev/null 2>/dev/null; "
             f"for f in adoc/*.adoc; do asciidoctor -b docbook {' '.join(attrs)} "
             '-o "docbook/$(basename "$f" .adoc).xml" "$f" 2>>docbook/errors.log || '
             'echo "FAILED $f" >> docbook/errors.log; done'])
    if r.returncode != 0:
        print(f"  asciidoctor container failed: {r.stderr[:400]}", file=sys.stderr)

    repaired = 0
    for xml in db_dir.glob("*.xml"):
        text = xml.read_text(encoding="utf-8", errors="replace")
        fixed = BARE_AMP.sub("&amp;", text)
        if fixed != text:
            xml.write_text(fixed, encoding="utf-8")
            repaired += 1
    if repaired:
        print(f"  escaped bare ampersands in {repaired} docbook file(s)", flush=True)

    print(f"  pandoc: docbook -> gfm", flush=True)
    r = run(["docker", "run", "--rm", "-v", f"{work}:/data", "--entrypoint", "sh", PANDOC_IMAGE,
             "-c",
             'for f in docbook/*.xml; do pandoc -f docbook -t gfm --wrap=none '
             '--shift-heading-level-by=1 -o "md/$(basename "$f" .xml).md" "$f" '
             '2>>md/errors.log || echo "FAILED $f" >> md/errors.log; done'])
    if r.returncode != 0:
        print(f"  pandoc container failed: {r.stderr[:400]}", file=sys.stderr)

    out = {}
    for row in rows:
        ex = row["exercise_id"]
        md_file = md_dir / f"{ex}.md"
        if not md_file.exists():
            out[ex] = {"md": None, "error": "conversion produced no output"}
            continue
        md = fix_alerts(md_file.read_text(encoding="utf-8")).strip()
        md_file.write_text(md, encoding="utf-8")
        out[ex] = {"md": md, "error": None}
    return out


def render_markdown(md: str, base_url: str) -> str:
    req = urllib.request.Request(
        f"{base_url}/v2/preview/markdown",
        data=json.dumps({"content": md}).encode(),
        headers={
            "Content-Type": "application/json",
            "oidc_claim_preferred_username": "adoc-migration-dry-run",
            "oidc_claim_email": "dry-run@local",
            "oidc_claim_easy_role": "teacher",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)["content"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--core", default="http://localhost:8099")
    ap.add_argument("--limit", type=int, help="convert only the first N, for a quick look")
    args = ap.parse_args()

    rows = [json.loads(line) for line in args.export.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit:
        rows = rows[: args.limit]
    print(f"Loaded {len(rows)} exercises")

    try:
        render_markdown("# probe", args.core)
    except (urllib.error.URLError, OSError) as e:
        print(f"\nCannot reach core at {args.core}/v2/preview/markdown ({e}).\n"
              "Start a local core with auth-enabled=false — see this script's docstring.",
              file=sys.stderr)
        return 2

    out = args.out
    work = out / "work"
    if work.exists():
        shutil.rmtree(work)
    (out / "flagged").mkdir(parents=True, exist_ok=True)

    converted = convert_all(rows, work)

    results, reasons = [], Counter()
    for i, row in enumerate(rows, 1):
        if i % 100 == 0:
            print(f"  verified {i}/{len(rows)}", flush=True)
        ex = row["exercise_id"]
        conv = converted.get(ex, {"md": None, "error": "not converted"})
        entry = {"exercise_id": ex, "title": row.get("title"), "chars": len(row["text_adoc"] or "")}

        if conv["error"] or not conv["md"]:
            entry |= {"status": "flagged", "reason": conv["error"] or "empty output"}
        else:
            try:
                rendered = render_markdown(conv["md"], args.core)
            except Exception as e:  # noqa: BLE001 - one bad row must not end the run
                entry |= {"status": "flagged", "reason": f"render failed: {e}"}
            else:
                want, got = visible_text(row["text_html"] or ""), visible_text(rendered)
                if want == got:
                    entry |= {"status": "ok", "reason": None}
                else:
                    reason = ("math delimiters only (EZ-1732)"
                              if math_delimiters_only(want, got)
                              else "text differs after round-trip")
                    entry |= {"status": "flagged", "reason": reason,
                              "chars_before": len(want), "chars_after": len(got)}
                    (out / "flagged" / f"{ex}.diff").write_text(
                        f"--- production html (visible text)\n{want}\n\n"
                        f"+++ converted markdown, re-rendered\n{got}\n", encoding="utf-8")
        results.append(entry)
        if entry["status"] == "flagged":
            reasons[entry["reason"].split(":")[0]] += 1

    (out / "report.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in results), encoding="utf-8")

    ok = sum(1 for r in results if r["status"] == "ok")
    lines = [
        "EZ-1731 adoc -> markdown dry run",
        "=" * 40,
        f"exercises processed : {len(results)}",
        f"converted cleanly   : {ok} ({100.0 * ok / max(len(results), 1):.1f}%)",
        f"flagged for review  : {len(results) - ok} ({100.0 * (len(results) - ok) / max(len(results), 1):.1f}%)",
        "",
        "flagged by reason:",
    ]
    lines += [f"  {n:>5}  {reason}" for reason, n in reasons.most_common()] or ["  (none)"]
    lines += ["", "Aggregates only — safe to share. report.jsonl, md/ and flagged/ are production",
              "content and must stay local."]
    summary = "\n".join(lines)
    (out / "summary.txt").write_text(summary + "\n", encoding="utf-8")
    print("\n" + summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
