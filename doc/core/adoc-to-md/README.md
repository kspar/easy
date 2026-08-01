# AsciiDoc → Markdown migration (EZ-1731)

Backfilling `exercise_version.text_md` for the exercises whose source is still AsciiDoc. Nothing
is visibly broken today — `text_html` is what gets served — but an editor loading a null `text_md`
would show an empty box, and saving would replace good content with nothing. This has to land
before exercise authoring UI ships.

## Handling the data

**Everything these scripts read and write is production content.** The YouTrack instance is public
with guest access and this repo is public. Exports, converted Markdown, per-exercise reports and
diffs stay on your machine. Aggregate counts are fine to share; anything that names or numbers a
specific exercise is not — ask first.

Only `out/summary.txt` is written to be shareable.

## Running a dry run

Nothing writes to any database. The dry run converts, re-renders and compares, then reports.

**1. Export** (against prod or a staging copy — read-only):

```sh
psql -h <host> -U <user> -d <db> -v out=/tmp/export.jsonl -f export.sql
```

**2. Start a local core** for `/v2/preview/markdown`. This is the only faithful way to render
Markdown exactly as production will, since it uses core's own `MarkdownService`:

```sh
java -jar core/build/libs/core-1.jar --server.port=8099 --server.address=127.0.0.1 \
     --easy.core.auth-enabled=false --easy.core.cors.allowed-origins=
```

**3. Convert and verify.** Needs Docker; asciidoctor and pandoc run in throwaway containers so
nothing is installed on the host:

```sh
python3 dry_run.py --export /tmp/export.jsonl --out ./out      # --limit 50 for a first look
```

Output:

| Path | Contents | Shareable |
| --- | --- | --- |
| `out/summary.txt` | counts and percentages | yes |
| `out/report.jsonl` | per-exercise verdict, title, reason | **no** |
| `out/work/md/*.md` | converted Markdown | **no** |
| `out/flagged/*.diff` | before/after text for review | **no** |

## What the conversion does

```
text_adoc
  → strip $run[…] / $in[…] / $nohl[…]        Easy's own markup; the text is kept, the marker
                                              dropped. Nothing in web/ has styled it since the
                                              WUI was removed.
  → asciidoctor -b docbook -a run= -a nur= …  the matching attribute references, passed as empty
                                              rather than stripped, so attribute-missing cannot
                                              drop the line
  → pandoc -f docbook -t gfm --wrap=none --shift-heading-level-by=1
                                              the shift matters: `== X` is <h2> in Asciidoctor
                                              but arrives as <h1> without it
  → rewrite `> [!NOTE]` to `> **Note:**`      commonmark-java has no GitHub-alert support, so
                                              the marker would reach students literally
```

Verification renders the result through `MarkdownService` and compares **normalised visible text**
against the stored `text_html`. Not markup: Asciidoctor wraps every `<li>` in a `<p>` and Markdown
does not, so a structural comparison flags almost every list for no reason.

## What flags, and why that is the point

Flagged exercises are not written — they are listed. A null `text_md` is a visible gap; a subtly
mangled exercise is not.

Math (`stem:` / `latexmath:`) flags by design. It has no Markdown representation, `MarkdownService`
has no math extension, and there is no MathJax or KaTeX in `web/` any more — so those exercises
already fail to render math in production. That is **EZ-1732**, not something this migration can
fix.

## Current state (2026-08-01)

The dry run has been executed against the full production corpus — 1081 exercises, 83 seconds,
nothing written to any database. The converter is settled; **no write has happened, and the write
will be done against staging** (EZ-1723).

| Outcome | Exercises |
| --- | --- |
| convert cleanly | **996** (92.1%) |
| maths — delimiters differ, blocked on EZ-1732 | 48 |
| cosmetic differences (punctuation, whitespace, added text) | 31 |
| lose a block title — want a human | 5 |
| malformed Asciidoctor output — wants its source fixed | 1 |

Two fixes came out of running it against real data, neither of which a synthetic corpus would have
produced:

- **Bare `&` in image URLs.** Asciidoctor writes image URLs into docbook attributes without
  escaping the ampersand, and the course server serves images as `?action=download&upname=…`, so
  the XML was invalid and pandoc refused it. **97 of 1081** needed the repair — it would have been
  the largest failure category by far. It first appeared as 2 failures in a 50-exercise sample and
  looked like a curiosity.
- **Maths classified separately.** Asciidoctor emits `\(x\)` and pandoc emits `$x$`; the formula
  survives intact and only the delimiter changes. Lumped together that is 85 unexplained failures;
  separated it is 48 known and 36 worth reading.

Decisions taken:

- **Auto-numbered captions** (`Figure 1.`, `Tabel 1.`) are not wanted, so they no longer count as a
  difference. Removed from both sides of the comparison, not just production.
- **`codehl` highlighting** is dropped — nothing in `web/` has styled it since the WUI went.
- **Maths** is EZ-1732's problem, not this one.

## When staging exists

1. Restore an anonymised copy (`../anonymise-db/`), with v4.0 deployed so `text_md` exists.
2. Re-run the dry run against staging and confirm the numbers still land near 92%.
3. Work through the flagged list by hand — 5 block-title losses and 1 malformed source are the
   only ones carrying real content risk; the other 31 are cosmetic.
4. Decide on the 48 maths exercises: hold for EZ-1732, or migrate and accept `$…$`, which is what
   both KaTeX and MathJax expect anyway.
5. Then write, per below.

## Then the write

Not built yet, deliberately — the dry run should be reviewed first. When it is:

- current versions only (`valid_to IS NULL`)
- `UPDATE` in place, no new `exercise_version` rows: the content is not changing, only the source
  representation is being backfilled
- leave `text_adoc` and `text_html` alone, so readers see no difference and rollback is
  `UPDATE exercise_version SET text_md = NULL`
- rehearse on staging (EZ-1723) before production
- production needs v4.0 deployed first — the `text_md` column arrives with EZ-1729

## Other files here

- `corpus-profile.sql` — which constructs appear, and how often
- `risk-buckets.sql` — each exercise bucketed once by its worst construct
- `export.sql` — the dry run's input
- `anonymise-db/` (sibling directory) — unrelated, for staging data
