# What the test programme has found, and what it taught

A running log for **EZ-1766**. `doc/testing.md` is the survey — what exists, what is missing, what
to do next. This is the other half: **what turned up while doing it**, kept because the defects are
evidence about where this codebase goes wrong, and the lessons are cheaper to read than to re-learn.

Append to it. Do not tidy entries away once they are fixed — a fixed bug is still evidence.

---

## Defects in production code

| # | What | Where | Found by | Issue |
| --- | --- | --- | --- | --- |
| 1 | `DISTINCT ON` with an ordering that is not total: which submission survives is whatever the query plan produces, so a student's grade could change on refresh | `courses.kt`, `StudentAwaitLatestSubmission`, `StudentReadSubmissions` | a "flaky" test | EZ-1763 |
| 2 | Same defect under `LIMIT`/`OFFSET`, where a non-total order lets rows be **skipped or repeated between pages** | `StudentReadSubmissions` | reading the siblings of #1 | EZ-1763 |
| 3 | No `@Secured` — reachable by any logged-in user | `POST /v2/management/log` | endpoint security surface test | EZ-1769 |
| 4 | No `@Secured` | `GET /v2/student/…/submissions/all` | endpoint security surface test | EZ-1769 |
| 5 | `exercise.dir_id` declared `.nullable()` in Kotlin for four years after a changeset made it `NOT NULL` | `Tables.kt` | schema-vs-Tables drift test | EZ-1769 |
| 6 | Grade table roster built from **the first exercise only**: a student absent from it never appeared, and the table looked complete | `GradeTablePage.tsx` | reading the code | EZ-1767 |
| 7 | `find(...)!` on the same roster: a student absent from a *later* exercise crashed the whole page | `GradeTablePage.tsx` | reading the code | EZ-1767 |
| 8 | The grade comparator was **not a total order** — `a.grades[i]?.grade` is `undefined` out of range and `undefined - undefined` is `NaN`, which reads as "equal" one way and not the other. `Array.sort` is then undefined behaviour: a table that reorders itself between renders | `GradeTablePage.tsx` | extracting it to test it | EZ-1767 |
| 9 | A stale sort key (its exercise removed while the page was open) returned 0 for every pair, leaving rows in roster order | `GradeTablePage.tsx` | extracting it to test it | EZ-1767 |
| 10 | Per-cell `find()` over the roster: O(students × exercises × students), ≈2M comparisons per sort on a 300-student course | `GradeTablePage.tsx` | extracting it to test it | EZ-1767 |
| 11 | Moodle sync poll dies mid-sync: cleanup cleared the interval without nulling the ref, so the guard then refused to start a replacement. Spinner spins forever; a reload fixes it, so nobody filed it | `ParticipantsPage.tsx` | reading the code | EZ-1768 |
| 12 | Three columns whose Kotlin nullability disagrees with the schema | `Tables.kt` | schema-vs-Tables drift test | EZ-1771 |
| 13 | `moodle_username` is sent by core and **not declared** in `web/src/api/types.ts`, so the app cannot read it | `types.ts` | contract check, incidentally | declared 2026-08-19 (EZ-1772); rendering it is EZ-1778 |
| 14 | The grade input has no `label` and no `aria-label`: a screen reader announces the control that sets a student's grade as "edit text, blank" | `ActivityFeed.tsx` | writing a locator for it | EZ-1776 |
| 15 | Course cards navigated with a bare `onClick`, so **ctrl/cmd+click could not open a course in a new tab** — on the first screen of every session, and against this repo's own written UI convention. The sanctioned helper already existed, stranded in another file | `CoursesPage.tsx` | writing a locator for it | fixed, `spaLink.ts` |
| 16 | Two hooks wrote to `localStorage` **unguarded, from click handlers**. `setItem` throws in Safari private browsing, on a full quota, and on access inside an iframe with third-party cookies blocked — the last of which `useEmbedTheme` documents *because the embed hit it*. The failure is not a lost preference; it is an exception escaping a click handler | `useSavedGroup.ts`, `useRecentExercises.ts` | deduplicating four copies | fixed, `api/localStorage.ts` |
| 17 | **No page had a `<main>` landmark** — wider than first thought — so reaching content by screen reader meant tabbing past the whole sidebar on every navigation | `AppLayout.tsx` | a locator that threw, then the landmark rule | fixed, EZ-1776 |
| 18 | **Every check dictionary emitted with keys like `'\'check_type\''` since 2026-08-07** — `PyDict` learned to quote its own keys and three callers were already quoting theirs. 635 of 720 corpus exercises. On master and dev; never reached production | `python_classes.kt` | reading the emitter, then measuring against the corpus | EZ-1774 |
| 19 | A value ending in a quote produced four consecutive quotes; Python closed the literal at the third and read the rest as an adjacent string, so a spec saying `1 4 7 ''` graded against `1 4 7 `. Two corpus exercises silently losing characters | `python_ast.kt` | asking CPython to parse the output | EZ-1774 |
| 20 | A value ending in an odd number of backslashes escaped the closing quote and ran the literal to the end of the file | `python_ast.kt` | asking CPython to parse the output | EZ-1774 |
| 22 | An icon-only back button with no accessible name — announced as "button" and nothing else, and the only way back from the grade table | `GradeTablePage.tsx` | axe, first run | fixed, EZ-1776 |
| 23 | `<ul>`s whose direct children were `role="button"`, so assistive technology loses the item count or does not announce a list at all — the whole sidebar nav | `AppLayout.tsx` | axe, first run | fixed, EZ-1776 |
| 24 | A link nested inside the sort button in every exercise column header: focus order, what is announced and what activation does are all browser-dependent. They were *already* two separate click targets — only the DOM nesting was wrong | `GradeTablePage.tsx` | axe, first run | fixed, EZ-1776 |
| 25 | **MUI leaves `IconButton`, `TableSortLabel` and outlined `Chip` with no visual change at all when tabbed to** — computed background, outline and box-shadow byte-identical focused and unfocused. Their only feedback is the ripple, a click effect that plays once and fades, so a keyboard user who tabs and pauses has nothing on screen | `theme.ts` | the focus-ring rule, after three corrections to it | fixed, EZ-1776 |
| 26 | Inline comment `type` is unvalidated free text in core (`text` column, no `@Pattern`, no enum, stored verbatim from the request) while the client declares it `'comment' \| 'suggestion'`. Inert today — web writes the field and never reads it back, branching on `suggested_code` instead — so it is a trap for the first reader rather than a live break | `TeacherInlineCommentCrud.kt`, `types.ts` | the `types.ts` contract check, its only surviving finding | EZ-1777 |
| 21 | One spec in the migration corpus compiles to a script with an unbalanced bracket — it would fail on the first submission. The tool that signed off the migration reported it as one of 721 successes | a live spec | `compileSpecTree` learning to parse its own output | EZ-1774 |

Nothing new in production code came out of phase 7, which is itself worth recording: porting two
scripts that had been run by hand for weeks found nothing the scripts had not, and the executor path
— the largest thing here with no coverage at all — was **correct**. Phase 7's yield is the four
things below, which are about the tests and the harness rather than the application.

Two fixtures also described responses core cannot produce (`anonymous_autoassess_template: null`
after the column became non-nullable; a `grade` sent as a bare number). Those are test defects, but
they are the ones the contract check was built for.

---

## Defects introduced *by* this programme, and what caught them

Worth as much as the list above: these are the failure modes of the safety net itself.

| What | Caught by |
| --- | --- |
| Three specs stopped asserting entirely during the `@playwright/test` migration — **0 checks reported, and green** | the check-count ratchet, on its first run |
| `easy-core-log`'s sudo re-exec passed `"$@"` *after* the parse loop had consumed it, so `--since … -n 1000` silently became "last 200 lines of everything, exit 0" | `/code-review` |
| A grade check gated on a fixed 150ms sleep where the app needs a mutation round trip plus four refetches — a latent flake that would accuse the app of double-posting grades | `/code-review` |
| Assertions that could not fail for the case they named (a count read before the mutation resolved; a body-only check that could not see a grade posted to the wrong submission) | `/code-review` |
| Frozen fixtures that never modelled the write that had just happened | `/code-review`, twice, in a file whose own comment warned about it |
| Unanchored stub needles answering a deeper endpoint than they name — six occurrences | the contract check, then `fakeApi`'s `[broad stub]` warning |
| Anchoring three of those **silenced the warning without fixing what it pointed at** — the request fell through to a family stub with the wrong shape — and a comment claimed otherwise | `/code-review` |
| The `[broad stub]` warning's own dedup cache was never reset between specs, so with `workers: 1` the first spec to trip a needle silenced it for the whole suite — the same class of bug as the thing it was built to catch | `/code-review` |
| A baseline sampled *after* the navigation it was meant to precede, so the check could not fail for the case it named | `/code-review` |
| `count()` used as a click guard, where it does not auto-wait — a missed click would have been reported as the app not sending a request | `/code-review` |
| **A genuine flake in the gate**: `library-exercise-tsl` waited for `compiled.length > 0`, which a compile already in flight *before* the edit satisfies, then asserted on the latest payload. Failed ~1 run in 3 on an idle machine, and could also pass without testing anything | a reviewer noticing, then measuring it |
| A stray `web/web/.pre-migration-check/` directory, **staged for commit** | `/code-review` |
| `HttpApi.field("id")` read an id off an *error* body — `RequestErrorResponse` carries a correlation id — so "a 403 meant nothing was created" passed on a request that had failed for an entirely different reason | the assertion failing, on the first run |
| `JsonNode` carries its own `map`, which Kotlin resolves in preference to the `Iterable` extension and which maps the node rather than its elements. Every element came back as the array itself | a null field, three lines later |
| The local storage directory was **not** reset between tests while the database was, so "a refused upload left nothing behind" failed on the *previous* test's uploads | the assertion failing |
| A grace-window boundary asserted against the wall clock: a row written "exactly 24 hours ago" is already older than that by the time the sweep computes its cutoff, so the one question worth asking about a grace window could not be put | the test failing, correctly, for the wrong reason |
| **Two tests reading `/unauth/versions` shared one cached snapshot** — `VersionsService` caches for five minutes in a plain field that neither the truncation nor the Spring cache invalidation reaches. Whichever ran first decided what the other saw, and with a shared fixture that reads as a pass | reasoning about the cache, then making both tests assert a per-test version so the hazard could express itself |
| An anonymous submission's DB write is launched on a detached coroutine and never awaited, so an undrained one committed **after the next test's truncation** — satisfying that test's count (vacuous pass) or overshooting it (a failure whose message said the opposite of what happened) | `/code-review` |
| `FakeExecutor.close()` stopped the listener but not the thread pool — `HttpServer.stop` deliberately does not shut down a caller-supplied executor. One live pool of non-daemon threads per test method, which is the classic cause of a test worker that finishes and then hangs | `/code-review` |
| A test named "including a teacher's feedback" wrote into course exercise instructions. Dropping `TeacherActivity.feedbackHtml` from the sweep's scan list — the exact case it named — passed | `/code-review` |
| `assertFalse(url.contains("//$key"))` could not fail: the fixture's `public-base-url` had no trailing slash, so deleting the `trimEnd('/')` it guards left the test green | `/code-review` |
| `assertEquals(0, elements("files").size)` was satisfied by a 403 or a 500 as readily as by an empty listing, because `elements` returns empty for any unparseable body | `/code-review` |
| `uploadedKey()` read `id` without checking the status — walking into the trap `HttpApi.field`'s own KDoc documents, four days after writing it | `/code-review` |
| `Result.deleted` was never asserted in the one test whose name is about it | `/code-review` |
| A recompile could write to a superseded `automatic_exercise` if a teacher saved during the run — the exact history rewrite the endpoint promises never to do, silently, while the report said CHANGED | `/code-review` |
| Only `Exception` was caught, and only around the compile: a `StackOverflowError` or an FK violation mid-run would have escaped as a 500, leaving no record of the hundreds already rewritten | `/code-review` |
| A duplicate asset whose surviving copy happened to match was reported UNCHANGED and left in place — a coin flip, since the query had no `ORDER BY` | `/code-review` |
| An empty script list would have deleted every generated asset and inserted nothing, leaving exercises unable to grade at all | `/code-review` |
| `copyTest changes the id and nothing else` compared two objects that had **both** been through `copyTest`, so a `copyTest` that cleared a field cleared it on both sides and the assertion could not fail | breaking `copyTest` on purpose |
| A test named for the mid-run guard re-pointed the version *before* the scan, so the guard's branch never executed | breaking the guard on purpose |
| A duplicate-asset test constructed the arrangement any implementation repairs, so it passed against the unfixed code | breaking the fix on purpose |
| A focus-ring rule that required an `outline` or `box-shadow`: seven false findings, every one carrying `Mui-focusVisible`, because MUI indicates focus with a background change | reading the findings instead of baselining them |
| The same rule read unfocused styles after `document.body.focus()`, which does not blur, so the entire tab order compared equal | probing it against a synthetic page |
| And read mid-transition, because MUI animates `background-color` — eleven findings became four once transitions were frozen | freezing transitions and re-measuring |
| The a11y recorder wrote nothing when a spec scanned cleanly, so a clean run was byte-identical to a run that never happened | fixing the findings, which put it in exactly that state |
| A CI watcher that took the newest run across *all* workflows and reported a 42-second dependency-graph job as the build's verdict | noticing the job name in the output |

---

## Lessons

### About this codebase

**When a test flakes on a tie, suspect the query before the fixture.** EZ-1763 was filed as two
fixtures sharing a millisecond. Spacing them out would have made it green and hidden a production
bug that changed a student's grade on refresh.

**Every check that fired broadly on its first run was wrong, not the code.** Nineteen missing-field
failures, forty phantom DTO findings, five budget overruns, 111 access-control failures — every time
the fix was to narrow the rule. The checks that found real bugs found one or two things each. A
guard that lights up everywhere is describing its own bug.

**Conversely, breaking the code on purpose caught four gaps green tests had not** — including a
`studentOnCourse` admin bypass nothing noticed, and a changelog guard whose first "cosmetic edit"
did not move the checksum at all. Make a new check fail once before trusting it.

### About flakes

**Wait for the condition you are asserting, not for a proxy.** The one real flake found in the
suite waited for "a compile happened" and then asserted "the compile contained my edit" — two
different things, and a debounced compile already in flight satisfies the first. Six consecutive
passes after the fix, against 1-in-3 failures before it.

**The lesson was already written down, next door.** `library-exercise-tsl-static.spec.mjs`'s
`afterEdit` helper documents this exact trap in four lines of comment, because that spec hit it
first. Its sibling still had the bug. Writing a lesson down where it happened is not the same as
applying it.

**Read the run's outcome, not one line of its output.** Fixing the flake above, I verified with
`grep 'compiler was sent the edited spec'` and reported six clean runs. All six were crashing a few
lines later on a `ReferenceError` the grep could not see — the check prints before the crash. The
full-suite run caught it on the exit code. A verification method that cannot express failure is the
same defect as a test that cannot fail, and it was committed here *while fixing an instance of it*.

**Distinguish "fails reproducibly" from "fails sometimes" before acting.** A review reported this
spec as reproducibly broken; it passed here. Both observations were honest — the reviewer's runs
were competing with mine for port 5199. Three of my own "failures" while investigating turned out
to be the same contention, not the assertion.

### About extracting code in order to test it

**Three of the defects above were found by the extraction, not by a test.** Moving the grade
table's arithmetic into a module surfaced a non-total comparator and a stale-sort-key branch;
deduplicating four `localStorage` copies surfaced that only two guarded their writes. Nothing
failed — the code simply could not be read side by side until it was in one place.

**Deduplicating asymmetric copies is a defect detector.** Four hooks did the same thing four ways.
The differences between them *were* the bugs, and they were invisible while each lived in its own
file.

**`tsc` and a green suite are happy with dead code.** A patch of mine left an `if (false) { }`
block in `useSavedFilters`; typecheck, lint and 34 specs all passed. Read the diff.

### About writing fixtures

**Read `doc/core/api-shapes.json` before writing a fixture, not after the warning.** Five fixtures
this programme wrote from memory were wrong: `/courses/{id}/basic` sends no `id`; `StudentsResp`
carries `moodle_username`; `/submissions/latest/students` returns a whole `ExercisesResp`, not a
bare list; `GET /student/courses` carries `last_accessed` and no `student_count`; "no invite" is an
empty body, because that response type has no nullable fields. Every one worked on screen — the page
reads one field — while describing something core cannot send. One `node -e` first would have saved
each round trip.

**Model the state change, not just the response.** A stub that answers `grade: null` however many
times the page has saved makes correct code look broken. Documented in
`doc/web/browser-testing.md`, and still walked into twice while writing tests *for* that class of
bug.

**A trailing slash means you meant it.** `fakeApi` now warns when a string needle matched a URL with
more path after it, unless the needle ends in `/`. Across 30 specs it fired twice, both real — a
rule that fires on every legitimate prefix stub gets ignored within a week.

### About the tools

**An empty result from a command that cannot see anything is not evidence of absence.** Searching
three days of dev's journal for the storage sweep returned nothing, and the sweep had run exactly on
time — `journalctl` exits 0 with "-- No entries --" for an unprivileged caller and puts the
explanation on stderr. Prove you can see *something* before concluding nothing happened.

**`mdutil -i off <path>` operates on volumes, not directories.** Pointing it at a repo would disable
Spotlight for the whole boot disk. The per-directory mechanism is `.metadata_never_index`.

**Machine load invalidates a timing-sensitive suite silently.** A 4-minute run became 36 minutes
with six failures — including a four-second spec — at load average 52. None of it meant anything
about the code. Check the load before believing a broad failure.

**Two concurrent `playwright test` runs corrupt each other.** With `reuseExistingServer` on, the
second attaches to the first's dev server and dies when the first tears it down. Three separate
confusing failures in one day, in three disguises. It is off now, so the second run says "Port 5199
is already in use" instead.

### About checking the output of a thing that generates code

**A migration verified its own transformation and never the artefact it exists to produce.** Every
step of the TSL migration checked specs: no exercise missing, no non-spec file edited, no retired
types left, and `compileTSL` returned without throwing. Not one looked at the generated Python. It
ran on 2026-08-12, five days *after* the compiler broke, recompiled all 721 exercises with unusable
keys, and reported success.

**"Returned without throwing" is not "produced a correct artefact",** and the gap is invisible while
nothing downstream parses the result. `compileSpecTree` computed 721 scripts and kept only a count.

**You cannot review what is never written down.** That is the same argument `doc/core/api-shapes.json`
already makes one directory over — *the diff is the review artefact* — and it had not been applied to
the compiler's output. `-PspecDump` and the golden files are both that lesson.

**The test that finds one class of defect can be structurally blind to a worse one.** Asking CPython
to parse every generated script found two real bugs. It cannot find EZ-1774, because
`{'\'check_type\'': …}` is *valid Python*. That one was found by reading the emitter and predicting
it. Recorded because the parse test looks like it covers this ground and does not:

```
ast.parse("execute_test(contains_checks=[{'\'check_type\'':'X'}])")  ->  OK
```

**Measure a change to a code generator before believing it.** The obvious fix to `PyStr` — escape
everything properly — measured as changing the meaning of **18 of 720** live exercises, because
specs store `\n` and rely on the literal turning it into a newline. The fix that shipped changes 2,
and both restore characters that were being dropped. `semdiff.py` is that measurement, promoted from
a scratch file to a committed tool.

**A golden file blessed without reading it is worse than no golden file.** It records the defect as
the expectation and makes every later diff clean, while looking like coverage. This happened *during*
the demonstration that golden files catch EZ-1774: regenerating with the broken compiler wrote nine
broken expectations, and `git checkout` did not restore them because they were untracked.

### About tools that watch for a thing they cannot see

**Seven times in one day**, something built to detect a problem was structurally incapable of
detecting it. This is the single most repeated mistake in the programme and the pattern never varies:
**the detector was only ever run against a clean case**, where "reports nothing" and "cannot report
anything" produce identical output.

- A verification `grep` that could not see a crash, so six failing runs read as passes.
- A mutation harness whose regex silently matched nothing, so an unmutated run read as a suite
  catching nothing — twice, in two separate sessions.
- A CI watcher matching the most recent run across *all* workflows, which reported a 42-second
  `Dependency Graph` job as the build's verdict.
- A flake hunter that could not see a flake. `--repeat-each=N` emits N separate spec entries with the
  same title, not N results under one; keying a Map by title with `.set()` kept only the last repeat,
  so every spec reported 1/1 and a genuinely intermittent spec was classified as "failed every time".
- The a11y recorder could not tell "everything is fixed" from "the suite never ran": a spec that
  scanned cleanly wrote nothing, so a clean run was byte-identical to no run. It reached exactly that
  state the moment the last five findings were fixed.
- The focus-ring check produced **seven false findings** by requiring an `outline` or `box-shadow`.
  Every one carried `Mui-focusVisible` — MUI indicates focus with a background change. A rule that
  prescribes *how* something must be done fails on every valid alternative; compare focused against
  unfocused instead, which is what "visible" means.
- The same check then read its unfocused styles after `document.body.focus()`, **which does not
  blur** — probed: `activeElement` stayed on the button and its background stayed the focused colour,
  so the whole tab order compared equal. And it read mid-transition, because MUI animates
  `background-color`: eleven findings became four once transitions were frozen for the check.

**Feed every detector a positive case before believing a negative one.** Delete a baseline entry and
check the run goes red. Disable a test class and check the coverage gate fails. Edit a report so one
repeat fails. Remove the fix and check the rule fires. Each takes under two minutes; every single one
of them either found a defect in the detector or was the only reason a negative result meant anything.

The three a11y corrections were caught *before* acting on the output, unlike the first four. The
difference was not skill — it was doing the two-minute check first rather than last.

**A detector belongs in a file, not a YAML `run:` block.** The flake reporter was four lines of
inline `node -e` and untestable; as `web/tests/support/flake-report.mjs` it has nine tests, one of
them exactly the case it used to get wrong.

### And the mirror: a detector that fires, about the wrong thing

The seven above were silent. The `types.ts` contract check (EZ-1772) failed the other way, and it is
worth recording next to them because the two mistakes feel completely different from the inside and
have the same cause.

Its first version had a rule for "is this nested reference the right type?" that compared the
**Kotlin type name** the wire nests against the name the referenced interface was annotated with. It
opened with seven findings. Four were artefacts: core declares a `RespAsset` and a `GroupResp` *per
controller*, so five structurally identical wire types have five different names, and one TS
interface legitimately stands for all of them.

The rule was not too strict. It was **measuring a proxy** — name identity — for the property anyone
actually depends on, which is structural compatibility. That the proxy happened to be false in this
codebase is luck; the mistake was reachable without knowing anything about Kotlin conventions, by
asking what a client breaks on.

Two things generalise:

- **A cluster of findings that all look alike is evidence about the rule, not the code.** Four of
  those seven differed only in which interface they named. That shape should be read as "check the
  rule" before "check the code" — and the check took two minutes: print the five wire types and see
  whether they differ.
- **The fix was to replace the proxy, not to relax it.** Recursing into the referenced interface and
  comparing it structurally against the nested wire type removed the four artefacts *and* is strictly
  stronger: a genuinely swapped reference now fails on the property names that do not line up, which
  is the reason it is wrong, rather than on a name mismatch that merely correlates with it. Weakening
  the rule to silence the noise would have kept a check that could not see the real defect.

### When a check cannot decide, give the undecidable case its own name

The one real finding out of those seven (EZ-1777) came from a case the checker genuinely cannot
settle: a TS literal union over a field core declares as a bare `String`. That is safe exactly when
the server validates the column, which the shape file does not record. Here core does not, so the
client's union is a promise nothing keeps.

The first version reported it as `kind-mismatch`, the same rule as a number read as a string. That
would have been a quiet disaster: the only way to stop the build complaining is to waive
`kind-mismatch` for that property, and a rule broad enough to cover an undecidable case is a rule
whose waiver also covers the decidable ones. Splitting it out as `ts-narrows-wire-string` means the
waiver in `web/tests/api-types-baseline.json` grants exactly one permission, carries the reason and
the issue id, and fails the day the finding stops firing.

Same shape as `noDataAssertion(id, reason)` and the a11y baseline: **"we looked and decided this is
fine" must be representable, and must not be spelled the same way as "nobody looked."**

### A fix can be present, correct, and still do nothing

`:focus-visible { outline: 2px solid }` was in the stylesheet, the element matched the selector, and
the computed outline was `none`. A single pseudo-class is specificity (0,1,0) — identical to MUI's own
`.MuiButtonBase-root { outline: 0 }` — and `CssBaseline` is injected *before* component styles, so
MUI wins the tie. Doubling the selector (`:focus-visible:focus-visible`) takes it to (0,2,0) and
beats a class without reaching for `!important`.

Worth generalising: in a themed component library, "I added the rule" and "the rule applies" are
different claims. Check the computed value, not the source.

The corollary: **a detector belongs in a file, not in a YAML `run:` block.** The flake reporter was
four lines of inline `node -e` and could not be unit-tested; moved to
`web/tests/support/flake-report.mjs` it has nine tests, one of which is precisely the case it used to
get wrong.

### About hand-maintained numbers

**Five times in this programme a count in `doc/testing.md` was wrong because I typed it instead of
measuring it** — in the document whose own opening argument is that hand-maintained numbers go stale.
The counts now come from the shell commands printed beside them, and the fix that finally worked was
deriving *all* of them in one script rather than patching whichever one I had noticed.

The general form: a number in prose is a cache with no invalidation. Either print the command that
produces it, or have a tool write it (`expected-checks.json`, the Kover report, the runner's own
summary line).

**A sixth, three days later, and a different flavour: the controller count was 117 when it was
written and 118 by the end of the same programme**, because EZ-1774 added `AdminRecompileTsl.kt`.
Measuring once is not the fix — printing the command is, because then the reader can re-run it and a
stale number is one keystroke from being caught rather than something only its author could know.
Also worth writing down: for the endpoint count there *is* no honest one-liner, and the nearest
grep gives 123 against the inventory's 124. When a number has no command, say where it comes from
instead of leaving a bare figure that looks measured.

### About what coverage measures, and what it does not

**A coverage threshold catches an area falling out of the suite; it is blind to losing a test or
two.** Measured on the same code: disabling all of `StoredFileSweepTest` takes the sweep from 94% to
**7%** and fails the gate; disabling *two* of its tests leaves it at **92%** and passes. Tightening
the number to close that gap would make it fail on refactors that add a line, which is how a gate
gets switched off. `bin/mutate.sh` is the tool for the fine-grained question.

**Name the code, not the package it lives in.** The first version of the Kover targets used packages
and measured the wrong thing in three of four cases. `core/ems/cron` scored 30% — not because the
sweep is untested (93%) but because `DeleteInactiveUsers`, 134 lines of Keycloak plumbing, shares the
package. `core/conf/security` scored 80% largely on `DummyZeroAuthFilter`, which is the auth-disabled
path that must never run on a deployed environment and which we therefore *want* uncovered. A
threshold that moves when an unrelated neighbour is added is one people learn to ignore.

**A global coverage number would be actively misleading here.** A large fraction of the codebase is
DTO declaration, so it measures the ratio of boilerplate to logic — and
`EndpointAuthorizationMatrixTest` executes nearly every controller line as a side effect of checking
who may call them. Global coverage would have jumped impressively the week that landed, while
whatever actually needed testing sat untouched.

### About breaking things on purpose, and the harness that does it

Mutation testing has found more vacuous assertions in this programme than any other technique. It
also has failure modes of its own, and both of them make it *lie in the reassuring direction*:

**A mutation that silently does not apply reads as "the test caught nothing to catch."** A `perl -0pi`
pattern with one backslash too few left `aae`'s parser untouched, and the resulting "59 passed" was
read as a result twice. **Always assert the mutation landed** — `grep -q` for the mutated text, and
say so — before believing the run.

**Python's bytecode cache can keep a mutation alive after the restore, or hide it during the run.**
`0o500` → `0o400` is the same byte size, and if the mutate-and-restore happen inside one second the
mtime matches too, so `__pycache__` serves the stale module. The symptom is a test failing against
source that reads correctly, which costs a confusing ten minutes; the *dangerous* symptom is the
reverse — the original still cached while the mutant is on disk, so the mutation appears not to
matter and a vacuous test is pronounced sound. Export `PYTHONDONTWRITEBYTECODE=1` and remove
`__pycache__` between runs.

**Restore by `cp` from a backup, never `git checkout`.** It discards uncommitted work in tracked
files, and does nothing at all for untracked ones — which is how nine golden files stayed blessed
against a broken compiler.

**A deletion is a bad mutation, because "did the edit land" cannot answer it.** The landing guard is
`grep -F` for the mutated text, and deleting a line leaves a substring of the original behind:
removing `| null` from `alias: string | null` leaves `alias: string`, which the original also
contains. The four web mutations added for EZ-1772 rename or merge instead — `alias: string;
course_code` — so the marker is genuinely absent before and present after. A deletion mutation would
have skipped itself while reporting nothing wrong, which is the family of mistake this whole section
is about.

### About replacing a script with a test

**When you delete the script, delete the argument for it too.** Both scripts carried a persuasive
"why this is a script and not a test" section, and both sections had outlived their premise by the
time they were read again — CI had a database. A rationale in prose survives the condition that made
it true, and the more persuasive it is the longer it survives. `api-testing.md` now records what
happened rather than what to do.

**A port is a chance to test what the script structurally could not.** Every genuinely new assertion
in `ArticleApiTest` is about the *cache*: that anonymous and signed-in non-admin readers get
byte-identical payloads, that an admin reading first does not leave the Markdown source in the entry
the next anonymous caller reads. A script running against a long-lived core cannot ask those — a warm
entry and a correct answer look the same from outside. Dropping `isAdmin` from that cache key is a
plausible tidy-up, it leaks the source and the owner's username to the internet, and exactly one test
in the file notices.

**Structural guards and behavioural ones catch different things, and it is worth measuring which.**
Widening the file-serving permitAll pattern from `/*/resource/*/*` to `/*/resource/**` was applied on
purpose: `EndpointSecuritySurfaceTest`, which exists to police that list, **passes**. Only
`FileApiTest`'s "a deeper path is not public" fails. Neither test is redundant, and neither one
covers the other.

**The review of phase 7 found seven problems and not one was in the code under test.** Every one was
in the tests: three assertions that could not fail for the case they named, two order dependencies,
a thread leak, and a test whose name described something it did not do. That is the consistent
result across this programme — the safety net is where the bugs are, and it is the thing nothing
else checks.

**Writing down a trap does not stop you falling into it.** `HttpApi.field` carries a KDoc warning
that an error body has an `id` too, added after that exact mistake cost a run. Four days later
`FileApiTest.uploadedKey()` read `id` without checking the status. A guard in prose protects the
next reader, not the author.

**A shared Spring context has more state than the reset extension knows about.** Truncating the
database and invalidating the Spring caches covers most of it. It does not cover a hand-rolled cache
in a bean field (`VersionsService`, five-minute TTL — `ConcurrentMapCacheManager` has no TTL, which
is why it is hand-rolled), or `AutoGradeScheduler`'s in-memory queue map, which learns about executor
rows on a 60-second timer and therefore has to be told. Both bit within an hour of each other.

**A near-identical fixture hides an order dependency.** The two version tests built the same
executor, so the second reading the first's cached snapshot produced exactly the expected values.
Removing the fix and re-running proved nothing — it still passed. Only once each test asserted
against *its own* executor could the hazard express itself. A fixture that varies per test is not
noise; it is what makes contamination visible.

### About what to inject for a test, and what not to

Three seams were opened in production code for phase 7, and the honest test is whether each one
makes the production code better read on its own terms:

- `StoredFileSweep.sweep(graceHours, deleteEnabled, now)` — **yes**. `cron()` reads configuration and
  `sweep()` does the work, which is a division worth having anyway, and it returns a `Result` instead
  of only logging, so the nightly report is a value.
- `now` specifically — **yes, and it was load-bearing.** Without it the grace boundary is unobservable,
  and that is not a testing inconvenience: it means nobody can answer whether a file uploaded exactly
  one grace period ago is safe.
- Setting `@Value` fields by reflection in `StorageServiceContractTest` — **no, and it is fine.** It
  buys the S3 backend under test without a second Spring context at ten seconds a fork. Both ways of
  getting it wrong are loud: `getDeclaredField` throws on a rename, `lateinit` throws on an omission.
  Reflection into production internals is acceptable exactly when failure is impossible to miss.

### About the gate

**A browser test that stops early is indistinguishable from one that passed.** Every guard in
`tests/support/spec.mjs` exists for that: the check-count ratchet, the contract budget, the expiring
quarantine. The ratchet earned itself on its first run and has caught a miscount since.

**Estimates of coverage and measured coverage disagree.** "~581 checks" (read off output), "599"
(counted call sites) and 618 (what actually ran) were three different numbers for the same suite.
Only the one a runner writes is worth keeping.
