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

## The fixture corpus, and what the 2026-08-21 rehearsal established

`fixtures/` holds seven hand-written AsciiDoc cases, one per documented trap, and
`build-corpus.py` turns them into an export JSONL that `dry_run.py` accepts. **It needs no database
and no production content**, so the converter can be exercised at any commit by anyone:

```sh
cd fixtures && python3 build-corpus.py > corpus.jsonl && cd ..
python3 dry_run.py --export fixtures/corpus.jsonl --out /tmp/rehearsal --core http://127.0.0.1:8099
```

Result at `6cde85c6` with the pinned images: **6 of 7 clean**, the bare-ampersand repair firing on
the image case, both `[%collapsible]` blocks surviving as `<details>`, and the one nested inside a
list item keeping its four-space indent so the list does not end early. Only the maths case flags.

**`build-corpus.py` has to emulate the old renderer, and the first attempt at it was wrong in an
instructive way.** Production's `text_html` came from core's `AdocService` (`adoc_service.kt` at
`c4550ede`, the commit production still runs), which registers an Asciidoctor **postprocessor** —
`EasyCodeProcessor` — that regexes the *rendered HTML*, turning `$run[X]` into
`<span class="codehl run">X</span>`. Setting `-a run=…` does not reproduce that, because the
attributes are the older `{run}`-reference mechanism. Render the fixtures without the postprocessor
and the literal `$run[…]` lands in the HTML, the converter strips it exactly as it should, and the
comparison reports a difference **that does not exist in production** — where the marker is a
`codehl` span whose inner text `visible_text` already keeps. Note `in` maps to class `input`.

That is worth knowing beyond the fixture: it is the shape of every false alarm this dry run can
produce. If a whole class of exercises flags, suspect the comparison's model of what production
stores before suspecting the converter.

### Two classifier bugs the fixture found, both fixed

`math_delimiters_only` decides whether a flagged exercise is filed as "maths, delimiters only" or as
"text differs after round-trip". That is not cosmetic: `build_payload.py --include ok,math` **writes**
the maths bucket and holds the other, so a misfiled formula is an exercise waiting for review it does
not need. Both bugs made it answer False when it should have answered True, and
`tests/test_dry_run.py` now pins all of it — 17 cases, over half of them negative, because a
classifier that answered True more often would pass every positive case and start writing exercises
whose text really did change.

**1. AsciiMath delimiters were never matched.** `MATH_DELIM` matched `\(`, `\)`, `\[`, `\]` and a
bare `$` — but Asciidoctor renders `stem:`, which is its **default** stem notation, as `\$x\$` with
the dollar escaped. Stripping only the bare `$` left the backslash behind, so the production side
read `\x^2\` and could not equal anything pandoc produced. Every AsciiMath exercise was therefore
filed as changed text. `latexmath:` emits `\(x\)` and is the case the function was originally
written against, which is why this survived two dry runs.

**2. pandoc's brace normalisation was not undone.** Asciidoctor writes `x^2`; pandoc writes
`x^{2}`. Identical LaTeX, different string. Now collapsed on both sides — narrowly, only a brace
group directly after `^` or `_`, so a brace in prose is untouched.

Together these explain why maths kept turning up in the held pile: "maths, plus one other
difference" is 3 of the 20 exercises held on dev, and the 2026-08-01 run's 36 unexplained
differences are the population to re-examine. **Expect the maths bucket to grow and the held pile to
shrink when this is next run against real data** — that is the fix working, and it means more
exercises get written, so it is worth a look at the numbers rather than a shrug.

## Running a dry run

Nothing writes to any database. The dry run converts, re-renders and compares, then reports.

**1. Export** (against prod or a dev copy — read-only):

```sh
psql -h <host> -U <user> -d <db> -v out=/tmp/export.jsonl -f export.sql
```

**2. Start a local core** for `/v2/preview/markdown`. This is the only faithful way to render
Markdown exactly as production will, since it uses core's own `MarkdownService`:

```sh
java -jar core/build/libs/core-4.0.jar --server.port=8099 --server.address=127.0.0.1 \
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

Math (`stem:` / `latexmath:`) flags by design: it has no *AsciiDoc-comparable* Markdown
representation, so the round-trip comparison cannot verify it. That was
**EZ-1732 (Math in exercise text no longer renders: no MathJax or KaTeX in web/)**, and it is not
something this migration could fix.

**EZ-1732 has since landed.** `MarkdownService` now has a math extension
(`core/ems/service/markdown_math.kt`) and `web/` typesets with KaTeX, both keyed on `$…$` and
`$$…$$` — which is exactly the form this migration wrote. So the 48 maths exercises render again as
soon as their `text_html` is regenerated from the `text_md` already stored; the flagging behaviour
described here is unchanged, because verification still cannot compare the two representations.

## Current state (2026-08-11): written on dev, not yet on production

**Dev is migrated.** 1060 of 1211 current exercise versions carry Markdown; 21 are held; the rest
never had AsciiDoc. 436 of them carry a working `<details>` collapsible. `text_adoc` is untouched
and no new `exercise_version` rows exist.

**`text_html` is rewritten too**, from the same Markdown — see below. Rollback is the receipt, which
carries every row's previous `text_md` and `text_html`.

### Why the HTML is rewritten rather than left alone

The first pass wrote only `text_md` and left `text_html` as Asciidoctor had rendered it years ago.
That is defensible — nothing a reader sees changes — but it leaves the two representations
disagreeing, and the disagreement is not permanent: core regenerates `text_html` from `text_md` on
every save, so the change was going to happen anyway, one exercise at a time, months apart, on the
day some teacher fixed a typo. Doing it here means it happens once, under a verification, on a copy.

**Not through `PUT /v2/exercises/{id}`**, which would regenerate the HTML itself and was the obvious
suggestion. It creates a new `exercise_version` per exercise, stamps the caller as its author, and
does not carry `text_adoc` forward — so a thousand exercises would change hands, gain an edit nobody
made, and lose the source every re-run of this migration has depended on. It also re-inserts the
auto-exercise and its assets, which is a great deal of grading configuration to disturb in order to
fix a text field.

Instead the write stores **the exact HTML the dry run rendered and approved**. That makes it safe by
construction: an exercise is only written when its re-rendered Markdown matches the stored HTML in
visible text, and that render is what gets stored. Two checks beyond the comparison, because a
text diff cannot see either:

- **Images: 0 lost, 0 gained** across all 1060.
- **Links: one changed**, in an exercise whose source wraps a URL in a code span. Asciidoctor
  auto-linked it *inside* the `<code>`; Markdown renders it as literal code, which is what the
  backticks asked for. Same visible text, one URL no longer clickable.

Nothing keys off the old markup: `web/` renders `text_html` straight into `dangerouslySetInnerHTML`
and styles no Asciidoctor classes — no `listingblock`, no `admonitionblock`, no `.example`.

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

### Collapsible blocks are preserved

`[%collapsible]` is the one construct here whose *behaviour* matters, and it was being silently
flattened: Asciidoctor's docbook backend has no collapsible concept, so every one of them arrived at
pandoc as a plain example block. The verification could not see it either — it compares visible text,
and the text inside a `<details>` is there either way.

It is carried across now. `mark_collapsibles` sentinels each block's title before Asciidoctor runs,
and `collapsibles_to_details` rewrites the marked blocks afterwards into
`<details><summary>…</summary>`. **No core change was needed:** `MarkdownService` builds
commonmark-java without `escapeHtml`, so raw HTML passes through *and* the Markdown inside it is
still parsed — verified against `/v2/preview/markdown`.

Four things this cost, each found by the conversion getting worse before it got better:

- **Indentation is load-bearing.** These blocks nest inside list items, where content sits four
  spaces in. Emitting `<details>` at column 0 ends the list, and everything after it becomes an
  indented code block — fences printed verbatim to the student. Ten regressions.
- **`<summary>` does not parse Markdown.** It is in CommonMark's HTML-block tag list, so a title of
  ``Näide faili `nimed.txt` sisust`` keeps its backticks. 48 regressions, fixed by rendering the
  title's inline Markdown to HTML — four constructs only, so anything unusual flags instead of being
  guessed at.
- **pandoc escapes punctuation** in titles, so the code spans arrived as ``\`…\` `` and had to be
  unescaped first. `_` counts as a word character, which is its own small trap.
- **A line starting with `.` is not always a title.** Requiring a blank line above it to prove
  otherwise cost ten *more* exercises, because a title straight after a `====` delimiter is
  perfectly valid. Reverted; the two exercises that do leak a sentinel are held for other reasons,
  and `build_payload.py` now refuses to write any text containing one.

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

The 21 held ones keep rendering from `text_html` exactly as before. They are not fragile while they
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
- **Maths** is EZ-1732's problem, not this one. (EZ-1732 has since landed, and it reads the `$…$`
  this migration wrote — see the note under "What flags".)

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
