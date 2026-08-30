# Beta-tester feedback, August 2026 — triage

A beta tester — a UT programming lecturer, authoring real exercises — sent **50 observations** across
five areas: the TSL data model, the new React UI, exercise texts, saved auto-assessments, and general
navigation. This file triages every one against the code, the dev spec corpus and a live compiler, so
that nothing is filed twice and nothing is dismissed on a hunch.

**The tester was right far more often than not**, and precise where it counted. Two claims were
checked so exactly — "exactly these three tests have no name", "one exercise says version 1.0.0 where
everywhere else says 1.0" — that the corpus reproduced them to the instance. A third, four verbatim
JSON parser errors, reproduced to the character offset.

They were also usefully wrong twice, and both corrections are worth more than the original claims:
the apostrophe bug they flagged is fixed, but rechecking it found a live one (B10′); and the specs
they reported as broken are not broken at all — they grade fine and cannot be *opened*, which is a
different and better-defined bug (D3).

## Ground rules used here

Same as the two audit programmes this file sits beside: **verdicts come from execution or from the
corpus, never from reading a form and inferring**. Where a claim was settled by running something,
the command and the result are recorded. Where it is a matter of taste, it says so and is not filed.

**Nothing in this file names an exercise, and neither do the issues it filed.** The tester identifies
exercises by course and title; that mapping lives in `doc/beta-feedback-2026-08-exercises.md`, which
is **gitignored**, alongside the script that regenerates it. This file carries counts, mechanisms and
code locations only.

That split is not fussiness. **The dev corpus is production's data** — EZ-1742's production frequency
table and the dev snapshot agree exactly where they overlap (`placeholder_test` 20,
`program_imports_module_test` 9) — so a dev exercise id is a production exercise id.
`doc/core/tsl-migration/.gitignore` already keeps the snapshots out of git for that reason, and
`kspar/easy` is public.

## Where the evidence came from

| source | what it settled |
|---|---|
| `doc/core/tsl-migration/after-dev.jsonl` | 721 exercises, 3991 tests — every saved spec *after* the EZ-1607 migration. Settled all of §D and the model items claiming "this is null everywhere" |
| `doc/core/tsl-migration/from-dev.jsonl` | the same corpus *before* migration, so residue is a measured delta rather than an assertion |
| `POST /v2/tsl/compile` on the core at `:8080` | the codegen findings and the D3 correction. Read-only; the UX audit's `oidc_claim_*` recipe works unchanged |
| `tsl-common/src/main/kotlin/tsl/common/model/` (449 L, 7 files) | every model asymmetry |
| `web/src/features/library/tsl/`, `web/src/i18n/et.json`, `web/src/components/RelativeTime.tsx`, `web/src/api/courses.ts`, `core/ems/service/courses.kt` | every UI, copy and performance item |

---

## Summary

| § | area | items | new | already filed | second opinion | debatable | other |
|---|---|---|---|---|---|---|---|
| A | TSL data model | 13 | 5 | 2 | 3 | 3 | — |
| B | User interface | 22 | 13 | 5 | — | 3 | 1 fixed |
| C | Exercise texts | 3 | — | 3 | — | — | — |
| D | Saved auto-assessments | 9 | 7 | 1 | — | — | 1 no action |
| E | Everything else | 3 | 3 | — | — | — | — |
| | **total** | **50** | **28** | **11** | **3** | **6** | **2** |

*"Already filed" means an existing issue or audit finding already carries it; "second opinion" means
an existing issue names the same thing but reached a different conclusion, and this report is
evidence against that conclusion.*

Plus **B10′**, a confirmed High bug found by rechecking a stale item — not one of the tester's 50.

### Issues filed

| issue | type | carries |
|---|---|---|
| **EZ-1810** | Bug, High, tsl | B10′ — a string starting with `"` is emitted as raw Python |
| **EZ-1811** | Feature, tsl+web | A3–A7, A13, B12, B15, B18–B20 — the execution tests support arbitrarily different things |
| **EZ-1812** | Usability, web | B2, B4, B6–B9, B14, B17, B21, B22 — the two test lists don't correspond; one vocabulary pass |
| **EZ-1813** | Bug, tsl+ülesanded | D3–D8 — five specs unopenable in the editor, one exercise unmigrated, twenty placeholders |
| **EZ-1814** | Cosmetics, web | E1 — Estonian dates in English order |
| **EZ-1815** | Performance, core+web | E3 — the exercise list ships the grade table's payload |
| **EZ-1816** | Usability, Low, web | E2 — course order is unexplained and unchangeable |

### What to fix first

Asked by kspar after the triage, and recorded here because the issue list does not carry an order.

**1. The grader passes students it should fail.** Not one issue — one failure mode with four
independent causes, and the only findings here that reach a *student*:

| mechanism | filed | state |
|---|---|---|
| A test with no checks passes everyone — and the first preset in the menu makes one | X-023 / EZ-1795 | **fixed** `83eade30` |
| `outputCategory` defaults to `ALL_IO`, so a prompt containing the expected string passes a wrong answer | A12 / **EZ-1818** | **fixed** 2026-08-30 |
| Output checks cannot say "and nothing else", so a student printing 1, 2, 3, 4… passes | B13 / **EZ-1818** | **fixed** 2026-08-30 |
| A value starting with `"` silently loses its quotes, so the check compares the wrong string | B10′ / EZ-1810 | open |

**EZ-1795 was resolved on 2026-08-29 and fixed the first row** (plus X-015, X-016, X-022, X-027). The
two middle rows reached it only as a comment and did not travel with the fix — verified still live on
master afterwards (`common.kt:117` unchanged; `nothingElse` absent from `TslSections.tsx`) — so they
were refiled as **EZ-1818** rather than left inside a Resolved issue. That refiling is the lesson:
**a finding added to an issue by comment does not get fixed with it.**

Everything else in these 50 items is *teacher* friction — visible, annoying, workaroundable. This
cluster is invisible from the authoring side and does not self-correct. It also holds the two cheapest
fixes in the batch: the `ALL_IO` default is one word (and provably cannot disturb a stored exercise,
since `encodeDefaults = true` means every saved spec already pins the value), and EZ-1810 is one line
moved inside the `forceString` branch.

One ordering constraint inside it: **`nothingElse` must not land before the `ALL_IO` default changes.**
Against `ALL_IO`, "and nothing else" would count the program's own input prompts as "something else"
and fail correct submissions — worse than not having it.

**2. Run the TSL migration on production.** ~36 exercises there grade correctly and cannot be opened
in the new editor; the migration fixes 31 as a side effect of re-serialising (D3–D5/D8). Decide the
one deliberately-skipped exercise first (D6).

**3. EZ-1810's SyntaxError half**, if it has not already ridden along with §1 — cheap, and it kills a
whole test set at grading time rather than corrupting one check.

**Deprioritise EZ-1812** (naming, ordering, wording). Largest item count, lowest harm; worth one pass
when someone is already in `et.json`.

### Comments added to existing issues

**EZ-1742** (A2, A8, A10–A13, D1 — model review; extends §1 and §7, and corrects §12) ·
**EZ-1795** (B1, B11, B13 — the silent-failure chain, with X-019's missing reach evidence) ·
**EZ-1539** (B5, D10 — assign default names in the UI) ·
**EZ-1800** (B3 — a better rename than X-024's) ·
**EZ-1702** (C1–C3 — three exercises the adoc→Markdown conversion did not reach).

### What this triage learned about the register

**Two open issues already covered more than expected**, and finding them changed six verdicts:

- **EZ-1742** ("TSL model review after the static-test collapse") already contains `Test.inputs`
  (§1), the `scopeType`/`scope` split (comment finding 9), and the `GenericCheck`/`GenericCheckLong`
  divergence including `id` and `elementsOrdered` (§7). Four of the tester's model items are second
  opinions on it rather than discoveries — which is worth something in itself, since §7 judged the
  divergence "defensible" and an independent reader did not.
- **EZ-1539** ("Make test.name mandatory — assign default values in UI") is exactly the tester's
  complaint that the card title is a string absent from the menu they just used. They arrived at the
  issue's own prescription independently.

The lesson for the next triage: **search the register by subsystem before filing, not by keyword.**
`Subsystem: tsl #Unresolved` returned both in one call; neither would have matched a keyword search
for "scope", "title" or "dead field".

---

## A. The TSL data model

The tester built their own table of the model (`TSLi_struktuur.md`) to write tests against; the
observations are in that table's order. Several are the kind of thing only someone transcribing the
whole model would notice.

### A1 — "The whole model could be one file instead of seven" · **debatable, not filed**

Accurate as description: `calls.kt`, `class.kt`, `common.kt`, `contains.kt`, `defines.kt`,
`function.kt`, `program.kt` come to 449 lines, of which `common.kt` is 193. One file would be a
comfortable size.

Against: the split is by test family and matches how `Compiler.kt` is organised, so a reader
following one test type touches one file. This is taste, and the tester's real problem — *there is no
single place to see the whole model* — is better solved by their own table than by a refactor that
churns seven files' history. Adjacent to EZ-1540 ("Clean up tsl and tsl-common"). **For kspar.**

### A2 — `inputs`, `passedNext`, `failedNext` are dead · **extends EZ-1742 §1** → comment

EZ-1742 §1 already lists `Test.inputs`, quoting the same TODO. It does **not** list the two
neighbours, and X-019 (UX audit) listed all three as capabilities "no UI reaches", advising they be
left as a deliberate omission.

**That advice was too generous.** In `common.kt` all three are plain properties, not constructor
parameters:

```kotlin
val inputs: String? = null // TODO: Kaspar, kas selle jätame?
val passedNext: Long? = null
val failedNext: Long? = null
```

so **no caller can set them, in any language, ever**. They are not an unexposed feature; they are
unreachable from the model's own constructor. `TSLFormat` has `encodeDefaults = true`, so all three
are written into every saved spec regardless.

Measured over the corpus: `inputs` present in **2056** tests, `passedNext` and `failedNext` in
**2061** each, **non-null in zero**. Three keys of pure noise in ~2000 stored specs, and three more
rows on the hand-maintained Kotlin↔TypeScript contract F-039 flags as unguarded. A deletion needs to
confirm decode still accepts old specs that carry them.

### A3 — `exceptionCheck` only on `ProgramExecutionTest` · **CONFIRMED, new** → EZ-1811

`ProgramExecutionTest` has `var exceptionCheck: ExceptionCheck? = null`; `FunctionExecutionTest` and
`ClassInstanceTest` have no such field. The tester's example is exactly right — a function whose only
job is to print something is a common thing to test, and "it finished without raising" is the check
you want. Same for a constructor.

### A4 — `outOfInputsErrorMsg` only on `FunctionExecutionTest` · **CONFIRMED, new** → EZ-1811

Running out of user input is not function-specific: a main program that reads more than the test
provides hits it constantly, and so does a constructor that prompts.

### A5 — that message says "Programm küsis…" inside a *function* test · **CONFIRMED, new** → EZ-1811

Verbatim from `function.kt`:

```kotlin
val outOfInputsErrorMsg: String = "Programm küsis rohkem sisendeid kui testil oli anda",
```

The tester reports this has caused real confusion in the course. One-line copy fix ("Funktsioon
küsis…"), and if A4 is done, each variant wants its own wording.

### A6 — `ClassInstanceTest` has no `standardInputData` or `inputFiles` · **CONFIRMED, new** → EZ-1811

Confirmed in `class.kt`. A constructor that prompts for input or reads a file is ordinary in some
teaching contexts and cannot be tested today.

### A7 — `ClassInstanceTest` has no "class not defined" message · **CONFIRMED, new** → EZ-1811

`FunctionExecutionTest` carries `functionNotDefinedErrorMsg`; `ClassInstanceTest` carries `className`
and no counterpart. The student who misspells the class name gets tiivad's words, not the teacher's.

### A8 — `scopeType` in `DefinitionTest`, `scope` in the other two · **already EZ-1742, finding 9** → comment

Confirmed across `defines.kt`, `contains.kt`, `calls.kt`, and already the subject of EZ-1742's comment
finding 9 — which also records the sharper consequence the tester could not see: the shared scope form
pointing at `scope` for a `DefinitionTest` writes a field nothing reads, and the test then silently
grades against the default `PROGRAM` scope. The web model special-cases it with a comment at
`tslModel.ts:648`.

Value of this report is independent confirmation, from someone reading the model rather than building
a form. Filed as a comment, not a new issue. Note for whoever fixes it: these are `@Serializable`
field names, so it is a **wire-format migration** over ~776 stored static tests, not a rename.

### A9 — field order could be more logical · **debatable, not filed**

The tester proposes for `DefinitionTest`: `scopeType`, `functionName`, `className`, `superClassName`,
`definitionCheckValue`, `definitionCheckType`, `genericCheck`. Constructor order is also
positional-argument order for any Kotlin caller and changes `@Serializable` element indices. Low
value, non-zero cost, purely a reading preference. **For kspar.**

### A10 — `GenericCheck` is the only check with an `id` · **second opinion on EZ-1742 §7** → comment

§7 lists `id` among the `GenericCheck`/`GenericCheckLong` differences and judged the divergence
defensible. The tester asks the natural question — delete it, or add it to the others? — and the
answer is **neither**, for a reason worth recording: the field's only consumer anywhere is

```
web/src/features/library/tsl/TslSections.tsx:293:  <Paper key={check.id ?? i} …>
```

a React list key. The compiler never emits it — a compiled check dict has no `id`. `GenericCheckLong`
has none and needs none, which is the proof the model does not require it. Measured: present in
**3081** stored checks, and in **0 of 776** `GenericCheckLong`s. A UI concern that leaked into the
wire format; key the list on something else and drop it.

### A11 — rename `GenericCheck` to `OutputCheck` · **debatable, already noted in EZ-1742 §7**

The observation is sound and §7 makes it too ("the `Long` suffix says nothing about which is which"),
as does `common.kt`'s own `// TODO: rename to DataCheck or ValueCheck?`. Two arguments against
`OutputCheck` specifically: it also checks *inputs* today (A12), and it is reused by the class-instance
test where "output" is a stretch. Should be picked together with the A12 decision, not before it.
**For kspar.**

### A12 — `outputCategory` defaults to `ALL_IO` · **duplicate of X-019** → comment on EZ-1795, EZ-1742

Confirmed in the model, and the tester's single most-repeated complaint — it appears again as B11
from the UI side, the only item of the 50 they raise twice. X-019 has the consequence:

> today every check sees `ALL_IO`, so a prompt containing the expected string passes a test the
> student's answer failed.

**What the tester adds is the cheap half of the fix.** X-019 concluded `outputCategory` "deserves a
form only once someone decides what to call it in a teacher's words" — but changing the **default** to
`ALL_OUTPUT` needs no form and no vocabulary decision, and is right on their argument: a test should
check what the program produced, not what the test itself fed in.

Caveat for whoever does it — **and this paragraph originally got it backwards**: `encodeDefaults`
governs what *kotlinx* writes, but the stored specs were written by the React editor and by
`migrate.py`, both plain JSON. Measured directly on `after-dev.jsonl` (2026-08-30): **all 3081
genericChecks omit the key**, so flipping the default changes what every one of them means on its
next save or recompile. It was flipped anyway, as a decision (EZ-1818, kspar 2026-08-30) — the
tester's argument won — with the known input-echo-dependent shape (KT2_jalgpall) needing an
explicit `ALL_IO`.

### A13 — `elementsOrdered` on `GenericCheck` but not `GenericCheckLong` · **second opinion on EZ-1742 §7** → EZ-1811

Also listed in §7 among the "defensible" differences. Whether ordering is meaningful for a static
check is a genuine question — "these three functions are defined, in this order" is plausible but
unusual — so it rides along on EZ-1811 as the question it is, not as an assumed omission.

---

## B. The user interface

### B1 — choosing TSL produces an immediate decode error · **duplicate — X-015, EZ-1795** → comment

The tester's verbatim error, `Expected start of the object '{', but had 'EOF' instead at path: $`, is
the exact string X-015 recorded and confirmed by execution: choosing TSL seeds a grading script and an
empty asset list but no `tsl.json`, so `useTslSpec` compiles the empty string and Save is disabled
before the teacher has done anything. Already in EZ-1795, with the fix named (`emptySpec()`).

**What this adds is reach**: the tester met it as the very first thing they did, on a new exercise,
without being told to look for it.

### B2 — the Add-test menu's group headings are not distinct enough · **CONFIRMED, new** → EZ-1812

The tester read the group headings as *smaller test names* and wondered whether some tests were
abbreviated to fit — exactly backwards from the intent. Cause is a default:

```
TslEditor.tsx:163:  <ListSubheader key={group.labelKey}>{t(group.labelKey)}</ListSubheader>
```

with no style override. MUI's `ListSubheader` is `0.875rem` at `text.secondary`; a `MenuItem` is
`1rem` at `text.primary`. The heading is **smaller and fainter than the items under it** — the
hierarchy cue is inverted, which is precisely the misreading reported. Same pattern at
`TslTestCard.tsx:247`, so one fix covers both lists.

### B3 — "Kutsub välja funktsiooni" appears twice · **duplicate — X-024, EZ-1800** → comment

Confirmed again in `et.json`: `tsl.preset.callFunction` and `tsl.preset.callsFunction` are
byte-identical. X-024 has the full analysis.

**The tester's fix is better than X-024's.** X-024 proposed two long descriptive labels; the tester
proposes **"Käivitab funktsiooni"** for the first, parallel to the existing "Käivitab programmi" in
the same group, reserving "kutsub välja" for the static check — because calling a function reads
naturally as a *syntactic* claim about the code, which is what `calls_test` checks. Two words, keeps
the menu scannable, and reuses a distinction the menu already draws. Recommended over X-024's version.

### B4 — the type dropdown and the Add-test menu do not correspond · **CONFIRMED, new** → EZ-1812

The UX audit's T1 unit considered the *naming* difference between these lists and found it defensible
(action voice vs. type name). The tester's point is different and stands: the lists have **different
contents and different groupings**.

| | source | items | groups |
|---|---|---|---|
| Lisa test (menu) | `PRESET_GROUPS` in `tslPresets.ts` | 13 presets | 6: Käivitab koodi · Mida kood sisaldab · Mida kood välja kutsub · Mida kood defineerib · Funktsiooni omadused · Muu |
| Testi tüüp (Select) | `TEST_TYPE_GROUPS` in `tslModel.ts` | 8 types | 3: Käivitab koodi · Vaatab koodi · Muu |

Two group names are shared, four are not, and no item text is shared at all. This is the structural
cause of B5.

### B5 — the card title becomes something in neither list · **already EZ-1539** → comment

Reproduced exactly, including the tester's example. Picking **"Kasutab tsüklit"** builds a
`contains_test` via `fromStatic(...)`, which **never sets `name`**; `TslTestCard.tsx:79` falls back to
`testDefaultName(test, t)`, returning `tsl.containsName.KEYWORD_NO_ARG` = **"Programm otsib
reserveeritud võtmesõna"** — a string appearing nowhere in the menu just used.

Their proposed rule — *the menu row I pick becomes the test's initial title* — **is EZ-1539's own
prescription** ("assign default values in UI, so the default values are visible and easily editable"),
reached independently by a user. Two-line fix for the preset path, since presets already receive `t`.

One thing to decide with it: an explicit `name` is stored in the spec and is what students see in
feedback, whereas today these store `null` and fall back to Kotlin's `getDefaultName()`. That is an
improvement for the student too, but it is a behaviour change — and it is also what removes EZ-1742's
finding 10, where a `mustHaveProperty: false` test is titled with the opposite of what it enforces.

### B6 — "Funktsiooni väljakutse test" vs "Väljakutse test" · **CONFIRMED, new** → EZ-1812

Both in the type dropdown (`tsl.defaultName.function_execution_test`, `tsl.defaultName.calls_test`) —
B3's collision in the other list. The tester's reasoning applies to both and is correct:
`function_execution_test` *runs* the function, which does not require the student's program to call it
anywhere.

### B7 — every type label ends in "test" except one · **CONFIRMED, new** → EZ-1812

`tsl.defaultName.function_is_test` = "Funktsiooni omadus"; the other seven end in "test". Trivial, and
free while B6 is open.

### B8 — the definition test should come before the calls test · **CONFIRMED, new** → EZ-1812

Current static-group order: `contains_test`, `calls_test`, `definition_test`, `function_is_test`.
Something must be defined before it can be called. Apply to `PRESET_GROUPS` too, so B4 does not worsen.

### B9 — "Koodi sisu test" is too broad · **debatable, carried on EZ-1812**

The tester proposes "Koodist otsingu test" — the instance-level names already say "otsib". Against:
awkward Estonian, and this type stands in for 13 retired ones, so breadth may be honest. Listed on
EZ-1812 as part of the vocabulary decision rather than as a defect. **For kspar.**

### B10 — a test name ending in an apostrophe breaks the spec · **ALREADY FIXED** — and see B10′

The tester flagged this as historical and said they had not rechecked. It is fixed, in `03d5c215`
(EZ-1766). `closeableInTripleQuotes()` in `python_ast.kt` handles exactly this and documents it:

> **Ends with a quote.** `'''it's'''` is four consecutive quotes; the literal closes after three and
> the fourth is stray. Escaping that last one is the whole fix.

Verified against the live compiler with the tester's own example, shaped as `Leidub funktsioon
'nimi'` — it generates Python that **parses**; so does a trailing backslash, the other case that KDoc
names. The workaround they have been using — always append a full stop so the name does not end in
the quote — is no longer needed.

### B10′ — but a value *starting* with `"` is emitted as raw Python · **CONFIRMED, new, High** → EZ-1810

Rechecking B10 across neighbouring cases found a live bug of the same family. `PyStr` short-circuits
before it quotes anything:

```kotlin
if (value == null) return "None"
if (value.startsWith('"')) return value          // emitted verbatim as Python source
if (forceString) return "'''${closeableInTripleQuotes(…)}'''"
```

The `startsWith('"')` escape hatch belongs to the `forceString = false` path — fields carrying raw
Python expressions, like function arguments — but it is tested **before** `forceString`, so it applies
to every string field in the model. Two failures, both reproduced against the real compiler:

| teacher types | where | generated | result |
|---|---|---|---|
| `"Tere" väljastamine` | test name | `name="Tere" väljastamine` | **SyntaxError** — the whole test set fails at grading time |
| `"Tere"` | expected output | `'expected_value':["Tere"]` | **silently wrong** — quotes consumed as syntax, so the check compares against `Tere` |
| `Tere` | expected output | `'expected_value':['''Tere''']` | correct (control) |

The second is worse, and the UI leads teachers into it: the function-arguments help text reads
*"Argumendid eraldi ridadel ja Pythoni süntaksis, nt sõne `"abc"`"* — the builder teaches
double-quoted strings in one field, and that habit carried into the expected-output box produces a
check that quietly compares the wrong thing.

Fix: move the `startsWith('"')` test inside the `forceString == false` branch, where it was always
meant to live.

**Method note.** This exists because a stale item was rechecked rather than closed. The report was out
of date; the area was not.

### B11 — "what counts as output?" — checks see user input too · **duplicate — X-019** → comment on EZ-1795

The UI half of A12, and the tester's most keenly felt problem: *"Alatasa on vaja nipitada ja leiutada
võtteid, kuidas eraldada programmi lõpptulemusena saadavat väljundit … programmi sisendandmete
küsimisest."* X-019's `ALL_IO` finding described by someone who has worked around it for two years.

### B12 — the user-input box cannot be closed once opened · **CONFIRMED, new** → EZ-1811

`TslStdInSection` renders an Add button when `inputs.length === 0` and a bare `TextField` otherwise,
with **no delete control anywhere in the component**. Emptying the box does not close it either:
`onChange(e.target.value.split('\n'))` turns `''` into `['']`, length 1.

The contrast the tester noticed is real and is in the same file: `TslInputFilesSection`,
`TslReturnCheckSection`, `TslDataChecksSection` and `TslOutputFileChecksSection` all render a
`DeleteOutlineOutlined` `IconButton`. This one is the exception, and a stray empty `standardInputData`
is not harmless — it feeds a blank line to a program not expecting to read.

### B13 — output checks cannot say "and nothing else" · **duplicate — X-019** → comment on EZ-1795

`nothingElse` exists on `GenericCheck`, `OutputFileCheck` and `ClassInstanceCheck`, and
`tsl.nothingElse` exists in `et.json`, but neither output section renders it — both render only
`elementsOrdered`. X-019 recorded this as "exposed on *static* tests only" and judged it "worth adding
for symmetry".

**The tester supplies the correctness argument X-019 lacked**, and it is better than symmetry: a
student who prints 1, 2, 3, 4, … in the hope the right answer is in there currently **passes**. That
is a grader that cannot fail, which is EZ-1795's own theme — it belongs with X-023/X-027, not in the
nice-to-have tier.

### B14 — the output-file check's sentence is ungrammatical · **CONFIRMED, new** → EZ-1812

The standard output check composes `tsl.outputCheckSent1` ("Väljundis") + check-type +
`tsl.outputCheckSent2` ("järgmistest") + data-category. The output-**file** check renders the filename
and the two selects with **no connecting words**, so it reads

> `[output.txt]` `leiduvad kõik` `sõnedest`

against "Väljundis leiduvad kõik järgmistest sõnedest". Both of the tester's specifics hold — the
leading "Väljundfailis" is missing and so is "järgmistest".

### B15 — no exception check on function or class-instance tests (UI) · **CONFIRMED, new** → EZ-1811

The UI half of A3: `TslExceptionCheckSection` is rendered only in the `program_execution_test` body.

### B16 — the exception check should be able to name the exception · **debatable, not filed**

A feature request, not a defect: `ExceptionCheck` carries only `mustNotThrowException: Boolean`, so
"must raise `ValueError`" cannot be expressed. Needs a model field, a compiler change and tiivad
support; the tester frames it as a future want. **For kspar** — worth its own issue if wanted, but it
should not ride along with EZ-1811's symmetry work.

### B17 — swap two check blocks in the function test · **debatable, carried on EZ-1812**

Current order: return, output, argument, output-file. The tester wants the two output checks adjacent
(return, argument, output, output-file) so "what the function produced" forms one block and "what
happened inside it" another. Counter-argument: current order puts the two most-used checks first.
Pure preference, no data consequence.

### B18 — the "Veateated" block exists only on the function test · **CONFIRMED, new** → EZ-1811

The UI half of A4/A5: `TslErrorMessagesSection` is rendered only from the `function_execution_test`
body, and its three fields are exactly the three only that test has. Fixing the model fixes the UI.

### B19 — the class-instance test cannot be given inputs (UI) · **CONFIRMED, new** → EZ-1811

The UI half of A6: the `class_instance_test` body opens straight at `tsl.createObject` with no
`tsl.inputs` group at all.

### B20 — the UI should be uniform across test types · **CONFIRMED as the framing, new** → EZ-1811

Not a separate defect but the correct diagnosis of A3–A7, B15, B18 and B19 together, and worth stating
as the issue's premise rather than filing seven unrelated gaps:

| | user input | input files | output check | output-file check | exception check | error messages |
|---|---|---|---|---|---|---|
| `program_execution_test` | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| `function_execution_test` | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| `class_instance_test` | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ |

Every ❌ is a gap the tester hit, none looks like a decision, and the union of the rows is what all
three should offer. That table is the issue.

### B21 — "Objekti oleku kontroll" in a test about isendid · **CONFIRMED, new** → EZ-1812

`et.json` uses *isend* for the test ("Klassi isendi loomise test", "Isendi loomise kood") and *objekt*
for the check (`tsl.instanceCheck`, `tsl.instanceFields`, `tsl.instanceCheckPass`/`Fail`). Two words
for one thing in one card. Standardise on *isend*, matching the test's own name.

### B22 — contains-test wording · **CONFIRMED, new** → EZ-1812

`tsl.longAll` "leiduvad kõik loetletud" → "…loetletutest"; `tsl.longMissingOne` "vähemalt üks
loetletud puudub" → "puudub vähemalt üks loetletutest". The elative is the right case for a
partitive-of-a-set reading, and the reordering stops "loetletud" parsing as a modifier of "üks". Taken
on the tester's authority — they teach the language.

---

## C. Exercise texts — all three are EZ-1702

All three reports are the same message, and it is **not an error**: `noMarkdownSource` in `et.json` is
a deliberate warning shown when an exercise has HTML but no Markdown source, and blocking Save until
something is typed is the designed guard against silently replacing a text with nothing.

The tester's actual observation is the sharp one: *these are among the few exercises where it happens,
while other exercises from the same courses and years are fine* — which is precisely **EZ-1702**,
still open. Three instances, identified in `doc/beta-feedback-2026-08-exercises.md`.

Two notes carried to the issue. One of the three has **Python Grader**, not TSL, so the gap is not
confined to TSL exercises. And another is also §D's wholly unmigrated exercise — the same one is
broken on both halves, which makes it the natural first to look at and possibly the shared cause.

---

## D. Saved auto-assessments

Every item was settled against `after-dev.jsonl` — the post-migration state of all 721 TSL exercises,
3991 tests. No production access was needed and none was used.

### D1 — one exercise has `tslVersion: "1.0.0"` · **CONFIRMED, new, harmless** → comment on EZ-1742

Measured: **715 exercises at `1.0`, exactly 1 at `1.0.0`.** The tester found the one.

Its consequence is nil, which is the more interesting finding: `tslVersion` is **read by nothing** —
required on `TSL`, written as the literal `'1.0'` by `tslModel.ts:270`, and never branched on in
`tsl/`, `core/` or `web/`. Either use the field or write down that it is decorative.

### D2 — *(numbering note)* the tester's four JSON-error reports are D3–D5 and D8

Kept in their original positions below rather than renumbered.

### D3, D4, D5, D8 — four unparseable specs · **CONFIRMED, new — and the framing is not what it looks like** → EZ-1813

All four reproduce **character-exactly** — same message, same line, same column, same byte offset —
and there is a fifth the tester had not reached:

| parser error, reproduced | count |
|---|---|
| `Expecting ',' delimiter: line 36 column 17 (char 1238)` | 1 |
| `Expecting ',' delimiter: line 27 column 21 (char 686)` | 1 |
| `Invalid control character at: line 11 column 48 (char 252)` | 3 — one of them unreported |

(Which exercises: `doc/beta-feedback-2026-08-exercises.md`.)

**They are not corrupt, and the tester's implied diagnosis is wrong.** All five were pushed through
the real compiler:

```
strict JSON: REJECTS (JSONDecodeError)   kotlinx: compiles, 1 script(s)     ×5
```

They grade correctly and always have. This is **EZ-1742 §12's leniency finding** — production's `Json`
accepts missing commas and literal control characters — but with a consequence that comment did not
draw. §12 concluded *"saving through the API would normalise these, since the editor re-serialises."*
**It cannot**: `tslModel.ts:598` parses with `JSON.parse`, which is strict, so the editor never loads
them. With X-022 (an invalid spec disables Save), a teacher who opens one sees a raw browser parser
message and is locked out of an exercise that works.

Cause in every case is a teacher-typed multi-line value — a 2-D matrix as a test name, a long list as
a `returnValue` — written with the newline **unescaped**. A short string makes a strict parser say
"control character"; a longer one runs on and the next error is a missing delimiter. Same cause, two
messages.

The producing bug is gone: the React editor serialises with `JSON.stringify` (`tslModel.ts:586`).
This is wui-era data.

**Scale, and it is bigger than five.** The five above are the *post*-migration count, measured on dev.
**Production has not been migrated** (confirmed by kspar, 2026-08-29), so it still carries the
pre-migration number: **36** such specs, per EZ-1742's comment of 2026-08-07, measured on the
production export. So on production today, roughly 36 exercises grade correctly and cannot be opened
in the new editor at all.

**The migration is itself the fix for 31 of them**, as a side effect of re-serialising — which is a
better argument for running it than anything in the runbook. Re-serialising the residual five
server-side finishes the job, and EZ-1742 §12 already asks whether to normalise on write; this is the
argument for yes.

### D6 — `program_imports_module_test` serializer error · **CONFIRMED, new** → EZ-1813

Real, and the cause is worse than one stale test type: **one exercise was skipped by the EZ-1607
migration entirely.**

| | exercises with retired types | retired-type tests |
|---|---|---|
| before (`from-dev.jsonl`) | 158 | 558 |
| after (`after-dev.jsonl`) | **1** | **11** |

All 11 of the survivor's tests are retired types: `program_imports_module_test`,
`program_defines_function_test`, `function_calls_function_test`, `program_calls_function_test`.
Confirmed against the live compiler by pushing all 721 post-migration specs through
`POST /v2/tsl/compile`: **720 compiled, 1 rejected**, with exactly the tester's error. **It is also one
of §C's three exercises** — the same one is broken on both its text and its auto-assessment.

**The migration tooling is not at fault.** This was first written up as "a clean run should not have
left this", and that was wrong; the correction is recorded here and on EZ-1813 rather than quietly
edited away, because the reasoning is the useful part.

Running `migrate.py`'s own `MIGRATIONS`/`UNCHANGED` tables over the spec: `load_spec` parses it and
**all 11 tests resolve to a mapping**, so `migrate.py` would have converted it cleanly and exited 0.
The exercise was never *written back*. `from-dev/writeback.log` records **188 exercises written,
spanning ids 287–1332**, with this one absent from the middle of that span — so it was not truncated
by `--limit`, which breaks on an ascending sort and would leave a contiguous tail. The
"already migrated" check cannot explain it either: that compares live against migrated, and this spec
does change.

That leaves `--skip`, documented as *"for the ones that need a decision rather than a migration"*.
One exercise, mid-range, deliberately shaped. **The tooling worked; someone deferred this exercise.**

Two smaller findings survive, neither a blocker:

1. **The decision was never made or never recorded**, and the exercise is broken in the editor today.
   It has to be made before the production run, or production inherits the same skip.
2. **A `--skip`ped exercise leaves no durable trace.** `writeback.log` records writes, not exclusions;
   the exclusion goes to stdout and dies with the terminal. For a 723-exercise production run, one
   line appending skipped ids to the log makes "what did we defer, and why" answerable afterwards.

### D7 — one test is of type `placeholder_test` · **CONFIRMED, new, low** → EZ-1813

More common than the tester supposed: **20 instances across 19 exercises**, not one — and matching
EZ-1742's production frequency table exactly, which is how we know dev is production's data.

A legitimate type (the "type not chosen yet" state), but `Compiler.kt:256` compiles it to the empty
string, so a saved placeholder emits nothing and can never fail. EZ-1795's family, in 19 live
exercises.

### D9 — some exercises use Python Grader rather than TSL · **no action**

The tester cannot remember whether this is intended. It is a per-exercise authoring choice, both
graders are supported, and nothing in the data suggests a defect. Recorded so the question is not
asked twice.

### D10 — three tests have no name · **already EZ-1539** → comment

Their most precise claim, and exactly right. Over all 3991 tests: **three have no name, all three are
`function_is_test`, all three have `id: 2`** — matching the three exercises they listed, and the only
unnamed tests in the corpus.

So the data cost of making `name` mandatory is three rows. The remaining question is the UI one
EZ-1539 already owns.

---

## E. Everything else

### E1 — dates are inconsistent and not written the Estonian way · **CONFIRMED, new** → EZ-1814

Both example strings reproduce **exactly**, from `RelativeTime.tsx`:

```
et  same-year   format(d,'MMM d, ') + format(d,'p')  ->  jaan 7, 09:46
et  other-year  format(d,'PPp')                      ->  16. dets 2025. 15:35
```

One hardcoded pattern. Same-year dates use `'MMM d, '` — **month-first, an English convention** — and
pass the Estonian locale only so it can supply the month abbreviation; other-year dates use the
locale-aware `'PPp'`, which correctly yields day-first order. The two branches of one component
disagree, which is the inconsistency reported. The future-date branch has the same `'MMM d, '` line.

Two notes for the fix: the year is redundant in a same-year branch, so `'d. MMM'` may be wanted rather
than `'PP'`; and the double full stop in `16. dets 2025. 15:35` comes from date-fns's own `et` locale,
so the other branch reads badly too and should be composed explicitly.

`RelativeTime` also serves the exercise library list — the surface the tester was looking at.

### E2 — course order changes · **explained; the finding is that it is invisible** → EZ-1816

Not a bug. `api/courses.ts` sorts both student and teacher lists by `last_accessed` descending —
most-recently-visited first. That fully explains the observation.

The finding is what the tester actually asked for: **nothing says the list is most-recently-used, and
there is no way to change it.** A list that silently reorders itself between visits reads as unstable
rather than helpful. Their suggestion — offer sorting, as the exercise library already does — is the
right shape. Low, because the default is defensible and only its legibility is not.

### E3 — course exercises take a long time to load · **CONFIRMED, new** → EZ-1815

`GET /v2/teacher/courses/{courseId}/exercises` returns the four summary counts **and**
`latest_submissions: List<SubmissionRow>` — one row per student per exercise.
`selectAllCourseExercisesLatestSubmissions` (`core/ems/service/courses.kt:187`) builds that for every
student and every exercise, so the response is O(students × exercises).

One hook, `useTeacherCourseExercises`, serves five call sites:

| call site | needs `latest_submissions`? |
|---|---|
| `GradeTablePage` → `gradeTable.ts` | **yes** — it is the grade table's entire dataset |
| `CourseExercisesPage` | no — reads only the four counts |
| `SimilarityPage` | no |
| `AddFromLibraryDialog` | no |
| `AddToCourseDialog` | no |

Four of five — including the exercise list a teacher opens at the start of a class — wait for,
transfer and parse the grade table's full payload and discard it. The counts are already computed
server-side, so the fix needs no new aggregation: put the heavy field behind `?include=submissions`,
or give the grade table its own endpoint.

**Not measured against real data.** The shape is certain from the code and the consumer list is
exhaustive; the magnitude is not. Production was not queried and dev's courses are too small to show
it.

---

## What was not checked, and why

- **Timings for E3.** Mechanism read from the code, consumer list exhaustive, but no request timed.
  Production was out of scope without asking; dev's courses are too small.
- **Anything requiring a real grading run.** The TSL findings stop at the generated Python. Whether
  tiivad *behaves* as the codegen implies — in particular B10′'s silently-wrong `expected_value` — was
  not observed end to end, for the same reason the UX audit's Testimine round trip stayed open: the
  core on `:8080` has no TSL-capable executor.
- ~~**Whether production has the same migration residue as dev.**~~ **Answered, and the question was
  malformed.** Production has not been migrated at all (kspar, 2026-08-29), so it has no *residue* —
  it has the entire pre-migration state: ~189 exercises on retired types and ~36 unopenable specs.
  The dev run is the rehearsal, not a partial production run. Corrected in D3–D5/D8 and D6 above.
- **The tester's own `TSLi_struktuur.md`.** Not supplied with the feedback, and worth asking for: a
  lecturer's transcription of the model is the closest thing to teacher-facing TSL documentation that
  exists, and EZ-1785's leads section notes there is none.
