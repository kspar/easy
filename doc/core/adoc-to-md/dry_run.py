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


# --- collapsible blocks ------------------------------------------------------------------------
#
# `[%collapsible]` renders as a <details> widget: a clickable summary hiding the content. Teachers
# use it for extra tasks and worked examples, and it is the one AsciiDoc construct in this corpus
# whose *behaviour* matters rather than its text.
#
# It dies early. Asciidoctor's docbook backend has no collapsible concept, so the attribute is gone
# by the time pandoc sees anything — every collapsible arrives as a plain example block,
# indistinguishable from one that was never collapsible. The verification cannot see the loss
# either: it compares visible text, and the text inside a <details> is present either way.
#
# So the fact is carried across by hand: sentinel the title before asciidoctor runs, then rewrite
# the marked blocks after pandoc. Markdown can express the result — commonmark-java passes raw HTML
# through and still parses the Markdown inside it, so `<details><summary>` needs nothing from core.
SENTINEL = "EZCOLLAPSIBLE"
SENTINEL_OPEN = "EZCOLLAPSIBLEOPEN"
COLLAPSIBLE_ATTR = re.compile(r"^\[[^\]]*%collapsible[^\]]*\]$")
# Asciidoctor's own default summary for a collapsible with no title.
DEFAULT_SUMMARY = "Details"


def mark_collapsibles(adoc: str) -> str:
    """Tag each `[%collapsible]` block's title so it can be found again after conversion.

    The title is the only thing that survives into the Markdown as identifiable text, so it is what
    carries the mark. A block without one gets a title purely to have somewhere to carry it — which
    is also honest, since Asciidoctor was already displaying "Details" for those.

    Both orders occur in the corpus: title before the attribute line (693 blocks) and after it (3).
    """
    lines = adoc.split("\n")
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not COLLAPSIBLE_ATTR.match(line.strip()):
            out.append(line)
            i += 1
            continue

        mark = SENTINEL_OPEN if "%open" in line else SENTINEL

        def titled(candidate: str) -> bool:
            return candidate.startswith(".") and len(candidate.strip()) > 1

        # Look back past blank lines: a title separated from its block by one still belongs to it
        # as far as Asciidoctor is concerned, and inserting a second title orphans the first —
        # which is how exercise 809 lost its "Näited funktsiooni …" heading and got "Details".
        back = len(out) - 1
        while back >= 0 and not out[back].strip():
            back -= 1

        if back >= 0 and titled(out[back]):                # .Title [blank] [%collapsible]
            out[back] = f".{mark} {out[back][1:]}"
            out.append(line)
        elif i + 1 < len(lines) and titled(lines[i + 1]):  # [%collapsible] then .Title
            out.append(line)
            out.append(f".{mark} {lines[i + 1][1:]}")
            i += 1
        else:                                              # no title at all
            out.append(f".{mark}")
            out.append(line)
        i += 1
    return "\n".join(out)


INLINE_CODE = re.compile(r"`([^`]+)`")
INLINE_STRONG = re.compile(r"\*\*([^*]+)\*\*")
INLINE_EM = re.compile(r"(?<!\*)\*([^*]+)\*(?!\*)")
INLINE_EM_UNDERSCORE = re.compile(r"(?<![\w\\])_([^_]+)_(?!\w)")
INLINE_LINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")


def inline_md_to_html(text: str) -> str:
    """Render the inline Markdown in a block title as HTML, for use inside `<summary>`.

    `<summary>` is in CommonMark's HTML-block tag list, so everything inside it is raw: a title of
    ``Näide faili `nimed.txt` sisust`` keeps its backticks and students read them. This came back as
    48 regressions the first time the collapsible rewrite ran, all of them titles carrying inline
    markup.

    Deliberately handles four constructs and no more. Anything else stays literal, the exercise
    fails the comparison, and it is held for a human — which is the correct outcome for a title
    doing something unusual, and much better than a regex that half-understands the whole grammar.
    """
    def code(m: re.Match) -> str:
        return f"<code>{m.group(1)}</code>"

    # pandoc escapes Markdown punctuation with a backslash, so a title arrives as
    # ``Näide funktsiooni \`failist\`e tööst``. Left in place, the backslashes end up either side of
    # the <code> tags and are read out to the student. Unescaping first is also what makes the
    # patterns below match at all.
    text = re.sub(r"\\([^\w\s]|_)", r"\1", text)

    parts = []
    last = 0
    for m in INLINE_CODE.finditer(text):
        parts.append((text[last:m.start()], False))
        parts.append((code(m), True))
        last = m.end()
    parts.append((text[last:], False))

    rendered = []
    for chunk, is_code in parts:
        if is_code:
            rendered.append(chunk)
            continue
        chunk = INLINE_LINK.sub(r'<a href="\2">\1</a>', chunk)
        chunk = INLINE_STRONG.sub(r"<strong>\1</strong>", chunk)
        chunk = INLINE_EM.sub(r"<em>\1</em>", chunk)
        chunk = INLINE_EM_UNDERSCORE.sub(r"<em>\1</em>", chunk)
        rendered.append(chunk)
    return "".join(rendered)


def _collapsible_at(lines: list[str], i: int) -> tuple[str, bool, int] | None:
    """If an example block starts at `i` and is one of ours, return (summary, open, index after
    its title div). pandoc puts each tag on its own line, which is what makes this line-based."""
    if lines[i].strip() != '<div class="example">':
        return None
    j = i + 1
    while j < len(lines) and not lines[j].strip():
        j += 1
    if j >= len(lines) or lines[j].strip() != '<div class="title">':
        return None
    k = j + 1
    while k < len(lines) and not lines[k].strip():
        k += 1
    if k >= len(lines):
        return None
    title = lines[k].strip()
    if not title.startswith(SENTINEL):
        return None
    is_open = title.startswith(SENTINEL_OPEN)
    summary = title[len(SENTINEL_OPEN if is_open else SENTINEL):].strip() or DEFAULT_SUMMARY
    end = k + 1
    while end < len(lines) and lines[end].strip() != "</div>":
        end += 1
    return summary, is_open, end + 1


def collapsibles_to_details(md: str) -> str:
    """Rewrite the marked example blocks into `<details>`, leaving every other div alone.

    Closing tags are matched by depth rather than by the next `</div>`, because these blocks nest —
    a collapsible extra task routinely contains an example block of its own.
    """
    lines = md.split("\n")
    out: list[str] = []
    # None means "this div closes as it was written"; a string is the replacement, indent included.
    closers: list[str | None] = []
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith("<div"):
            # Indentation is load-bearing. These blocks nest inside list items, where the content
            # sits four spaces in; emitting `<details>` at column 0 ends the list, and everything
            # indented after it becomes an indented code block — fences and all, verbatim, on the
            # page. That was ten of the regressions the first time this ran.
            indent = lines[i][: len(lines[i]) - len(lines[i].lstrip())]
            found = _collapsible_at(lines, i)
            if found:
                summary, is_open, i = found
                out.append(f"{indent}<details{' open' if is_open else ''}>")
                out.append(f"{indent}<summary>{inline_md_to_html(summary)}</summary>")
                closers.append(f"{indent}</details>")
                continue
            closers.append(None)
            out.append(lines[i])
        elif stripped == "</div>":
            replacement = closers.pop() if closers else None
            out.append(replacement if replacement is not None else lines[i])
        else:
            out.append(lines[i])
        i += 1
    return "\n".join(out)


def fix_dangling_breaks(md: str) -> str:
    """Drop a trailing backslash where CommonMark will not read it as a hard line break.

    pandoc writes hard breaks as a backslash at end of line. CommonMark honours that *within* a
    block and nowhere else, so the same backslash on a block's final line is not a break — it is a
    literal backslash, and students see it.

    Found on dev while reviewing the exercises this flagged: it accounted for 17 of the 37, which
    were otherwise going to be hand-edited one at a time on the theory that they had lost a block
    title. They had lost nothing; the converter had added a stray character.

    Narrow on purpose. Stripping every trailing backslash also removes the ones that *are* honoured,
    deleting line breaks the author put there — and `visible_text` normalises whitespace, so the
    comparison downstream cannot see that happen. A rule that only touches the last line of a block
    is the difference between fixing this and quietly reflowing somebody's exercise.
    """
    lines = md.split("\n")
    for i, line in enumerate(lines):
        # `\\` at end of line is an escaped backslash the author wanted, not a break.
        if line.endswith("\\") and not line.endswith("\\\\"):
            following = lines[i + 1] if i + 1 < len(lines) else ""
            if following.strip() == "":
                lines[i] = line[:-1]
    return "\n".join(lines)


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
            mark_collapsibles(strip_easy_inline(row["text_adoc"] or "")), encoding="utf-8")

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
        md = fix_dangling_breaks(
            collapsibles_to_details(fix_alerts(md_file.read_text(encoding="utf-8")))
        ).strip()
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

    # The rendered HTML, kept per exercise so the write can store the very output that was verified.
    html_dir = work / "html"
    html_dir.mkdir(parents=True, exist_ok=True)

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
                # Keep it. This is the HTML the comparison below approves, so storing *these bytes*
                # in text_html is the only way to be sure the database holds what was verified
                # rather than a second render that might differ. `writeback.py` reads it from here.
                (html_dir / f"{ex}.html").write_text(rendered, encoding="utf-8")
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
