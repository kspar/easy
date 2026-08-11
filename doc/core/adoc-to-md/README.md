# AsciiDoc → Markdown migration

EZ-1731 (Migrate exercise text from AsciiDoc to Markdown (~1000 exercises))

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

**1. Export** (against prod or a dev copy — read-only):

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
already fail to render math in production. That is **EZ-1732 (Math in exercise text no longer renders: no MathJax or KaTeX in web/)** —
not something this migration can fix.

## Current state (2026-08-11): written on dev, not yet on production

**Dev is migrated.** 1061 of 1211 current exercise versions now carry Markdown; 20 are held; the
rest never had AsciiDoc. `text_adoc` and `text_html` are untouched and no new `exercise_version`
rows exist, so nothing a reader sees has changed and rollback is still one statement.

The dry run against dev's imported copy reproduced the production numbers **exactly** — 996 clean,
48 maths, 36 differing, 1 with no output — which is the strongest evidence available that the
import is faithful and the converter is stable.

What was written, per the decision taken on the day:

| | Exercises | Written |
| --- | --- | --- |
| converted cleanly | 1013 | yes |
| maths — delimiter only (EZ-1732) | 48 | **yes** — `\(x\)` becomes `$x$`, which is what KaTeX and MathJax expect anyway, so this positions them for EZ-1732 rather than waiting on it |
| text differs after round-trip | 19 | no — held for hand review |
| conversion produced no output | 1 | no — wants its source fixed |

(1013 includes the 17 recovered by the backslash fix described next; the first write put in 1044 and the
second added those 17.)

### Reviewing the held list found a converter bug, not 37 damaged exercises

The first pass held 37. Reading them one by one was going to be the next job; reading them
*together* was quicker and better. Almost half shared one symptom — a stray `\` in the rendered
text — and it was not lost content at all but an added character.

pandoc writes a hard line break as a trailing backslash. CommonMark honours that inside a block and
nowhere else, so the same backslash on a block's **last** line renders as a literal backslash that
students would see. `fix_dangling_breaks` drops exactly those. Re-running took the corpus from
996 clean to 1013, and 17 of the held 37 became byte-identical to production.

Two things made this safe rather than merely effective, and both are worth repeating on production:

- **The rule is narrow.** Stripping *every* trailing backslash also removes the ones CommonMark does
  honour, deleting line breaks the author wrote — and `visible_text` normalises whitespace, so the
  comparison cannot see that happen. The blunt version "fixed" one exercise more, by silently
  reflowing it.
- **It was checked against what had already been written.** Of the 1044 rows already in the
  database, the fix changed the Markdown of **zero**, and no previously-clean exercise regressed.
  Purely additive — which matters because `writeback.py` skips rows that already have `text_md`, so
  a fix that changed them would have left the old text in place and said nothing.

### What is still held (20)

Small, mixed, and each with a known symptom rather than a mystery:

| Symptom | Exercises |
| --- | --- |
| maths, plus one other difference | 3 |
| two blocks joined without a space (`word.Kui`) | 3 |
| whitespace or punctuation only | 5 |
| a link's label disappears | 2 |
| code-fence quoting (`"""`) mangled | 2 |
| table cells run together | 1 |
| a trailing backslash the narrow rule does not catch | 1 |
| zero-width spaces | 1 |
| other small differences | 1 |
| Asciidoctor produces no output at all | 1 |

Whether to chase these in the converter or fix them by hand is an open call. The whitespace and
punctuation ones are probably writable as they are; the joined blocks and the code-fence quoting
are real conversion defects and would repeat on production.

The 20 held ones keep rendering from `text_html` exactly as before. They are not fragile while they
wait: `ExercisePage.tsx` refuses to save a legacy exercise whose Markdown box is still empty, so the
"open it and destroy it" path is closed.

One difference from the August run worth knowing: the bare-ampersand repair fired on **18** docbook
files here against 97 in production on 2026-08-01, with identical outcomes either side. The likely
cause is a newer unpinned `asciidoctor` image escaping more of them itself. It changes nothing about
what gets written, but it is the same class of drift as the `python:3` base that broke the pygrader
image, and a reason to pin the converter images before the production run.

### Production is still to do

Everything needed is now built and rehearsed end to end. Production needs v4.0 deployed first, and
should get the same sequence: export, dry run, review the flag list, `build_payload.py`,
`writeback.py` without `--apply`, then with it. Keep the receipt — it is the rollback list.

### How the converter got here (2026-08-01, against production)

The dry run that settled it, kept because the reasoning still applies. The same numbers came back
from dev ten days later.

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

## The sequence, as run on dev

Done on 2026-08-11. The same order is what production wants.

1. Restore an anonymised copy (`../anonymise-db/`), with v4.0 deployed so `text_md` exists.
2. Re-run the dry run and confirm the numbers still land near 92%. They landed on the nose.
3. Work through the flagged list by hand — the block-title losses and the one malformed source are
   the only ones carrying real content risk. Doing it as a batch rather than one at a time is
   what found the backslash bug. **Still outstanding**: 20 exercises on dev, and nothing is at risk
   while they wait.
4. Decide on the 48 maths exercises. **Decided: write them**, accepting `$…$` — the formula is
   intact, the delimiter is the one KaTeX and MathJax expect, and nothing renders either form until
   EZ-1732 lands, so writing them costs nothing and saves doing this twice.
5. Then write, per below.

## Then the write

**4. Build the payload** — which exercises to write is a decision, so it is an argument:

```sh
python3 build_payload.py --out ./out --export /tmp/export.jsonl \
                         --include ok,math --payload payload.jsonl
```

`ok` is what converted cleanly; `ok,math` adds the delimiter-only maths; `all-flagged` takes
everything that produced output, including the ones that lose a block title, and is not advisable.

**5. Write**, dry run first — it rolls back and tells you what it would have done:

```sh
python3 writeback.py --payload payload.jsonl                              # writes nothing
python3 writeback.py --payload payload.jsonl --apply --receipt receipt.txt
```

Both scripts want to run where the database is, since the payload is exercise text.

What the write does and refuses to do:

- current versions only (`valid_to IS NULL`), `UPDATE` in place, no new `exercise_version` rows:
  the content is not changing, only the source representation is being backfilled, and a new
  version would put an edit nobody made into every affected exercise's history
- `text_adoc` and `text_html` are left alone, so readers see no difference and rollback is
  `UPDATE exercise_version SET text_md = NULL WHERE id IN (…)` over the receipt
- every update carries `valid_to IS NULL AND text_md IS NULL`, so an exercise somebody saved
  between the export and the write is skipped rather than overwritten with Markdown converted from
  superseded AsciiDoc — and the skip is counted out loud rather than swallowed
- it refuses a database whose name does not say it is a copy, like `../anonymise-db/` does
- one transaction, so a failure leaves nothing half-written

Production needs v4.0 deployed first — the `text_md` column arrives with EZ-1729 (core: bootJar cannot start — asciidoctorj JRuby gems do not resolve from a nested jar).

## Other files here

- `corpus-profile.sql` — which constructs appear, and how often
- `risk-buckets.sql` — each exercise bucketed once by its worst construct
- `export.sql` — the dry run’s input
- `build_payload.py` — turns a reviewed dry run into a write payload
- `writeback.py` — the only step that writes
- `anonymise-db/` (sibling directory) — unrelated, for dev data
