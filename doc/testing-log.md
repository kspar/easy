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
| 26 | Inline comment `type` is unvalidated free text in core (`text` column, no `@Pattern`, no enum, stored verbatim from the request) while the client declares it `'comment' \| 'suggestion'`. Inert today — web writes the field and never reads it back, branching on `suggested_code` instead — so it is a trap for the first reader rather than a live break | `TeacherInlineCommentCrud.kt`, `types.ts` | the `types.ts` contract check, its only surviving finding | fixed, EZ-1777 |
| 27 | `AccessChecksBuilder.testFalse()` — a `@Deprecated("For debugging only")` helper that **refuses all access**, with no callers, living inside the access-control builder. One forgotten line from 403-ing an endpoint. Deleted with the `or` combinator | `access_control_dsl.kt` | the coverage gate naming it as an uncovered line | EZ-1773 |
| 28 | Unlinking a course from Moodle sets `moodle_short_name = null` and deletes nothing else, so every outstanding Moodle invitation stays live — and `join()` matches on invite id with **no predicate on the course still being linked**, so it still enrols. A teacher who unlinks to stop Moodle enrolment has not stopped it | `LinkCourseMoodle.kt`, `JoinMoodleLinkedCourseByInvite.kt` | asking how the client's `moodle_pending_students` partition is reachable at all | EZ-1780 |
| 29 | `ConfirmDialog` wraps its message in a bare `<Typography>` (`<p>`), and the group-deletion confirmation passes a `<ul>` of affected students inside `<Box>`. Invalid nesting: React logs it, the browser closes the paragraph early, nothing fails | `ConfirmDialog.tsx` | a console error in the output of a *passing* spec | fixed, EZ-1766 |
| 30 | `selectInlineComments` orders by `created_at` alone, which the controller stamps with `DateTime.now()` at millisecond resolution. Two inline comments saved in the same millisecond tie, and the order the client shows them in is then whatever the query plan produces. Third instance of the family that opened this table, and the cheapest — one tiebreaker | `TeacherInlineCommentCrud.kt` | reviewing the EZ-1777 fix, not by any failure | fixed, EZ-1777 |
| 21 | One spec in the migration corpus compiles to a script with an unbalanced bracket — it would fail on the first submission. The tool that signed off the migration reported it as one of 721 successes | a live spec | `compileSpecTree` learning to parse its own output | EZ-1774 |

Nothing new in production code came out of phase 7, which is itself worth recording: porting two
scripts that had been run by hand for weeks found nothing the scripts had not, and the executor path
— the largest thing here with no coverage at all — was **correct**. Phase 7's yield is the four
things below, which are about the tests and the harness rather than the application.

Two fixtures also described responses core cannot produce (`anonymous_autoassess_template: null`
after the column became non-nullable; a `grade` sent as a bare number). Those are test defects, but
they are the ones the contract check was built for.

### EZ-1781, the grading-image pins system (2026-08-21)

A different shape from the rest of this log. The application code was fine; everything below is
**deploy machinery, and nine of the eleven were caught before a user could see them** — six by a
`/code-review` pass, two by running the thing, one by a test. Worth recording because deploy paths
have no users to complain, so the only feedback is somebody looking.

| # | What | Where | Found by |
| --- | --- | --- | --- |
| 30 | The Ansible role could only build an image that was **missing**, so a version bump updated the Dockerfile on the host, reported `changed`, and left grading on the old version. Dev advertised silmused 1.7.11 while grading with 1.7.4 for a fortnight | `roles/executor/tasks/main.yml` | deploying PR #70 and looking | fixed, `b3607bf8` |
| 31 | The inputs digest hashed each image's own smoke script but **not the shared version checker every Dockerfile COPYs**. Editing the checker moved no digest, so CI found every tag already published, pulled the old images, and the new checker reached nothing | `bin/pins.py` | `/code-review` |
| 32 | `docker/setup-buildx-action` selects the `docker-container` driver, which cannot see the daemon's image store — so `imgrec`'s `FROM pygrader` could not resolve and the label build had no `--load`. The first CI run would have died at the first child image | `bin/build-grading-images.sh` | `/code-review`, before the run |
| 33 | The documented production promotion told an operator to run `/easy-smoke.sh` on a published image. A label is metadata *about* an image and is invisible inside it, so it found no expectations and exited 1 — the operator would have concluded the artefact dev had graded with for weeks was broken | `doc/aae/grading-images.md` | `/code-review` |
| 34 | The About page selected images by "has a grading label", which reported **every retained rollback copy** (twelve rows for four images, with colliding React keys) *and* skipped production entirely, whose hand-built images carry no labels | `aae/containers.py` | `/code-review` |
| 35 | The reconciler guarded only the pull, so a `docker tag` failure on a later image escaped the loop and skipped the single `save_state` — discarding the quarantine recorded for an earlier one, which then failed again every five minutes | `easy_grading_sync.py` | `/code-review` |
| 36 | No `concurrency` group on a workflow that moves a mutable channel tag a host follows. Two master pushes in one build window and the *older* image could own the channel, get pulled, pass its smoke check and go live — a silent downgrade with nothing failing | `grading-images.yml` | `/code-review` |
| 37 | `executor_images_enabled: false` skipped everything that creates the images and then asserted they existed | `roles/executor_images` | `/code-review` |
| 38 | The version-exists gate asked `git ls-remote <repo> <sha>`. ls-remote matches **ref names**, so it reported a problem for the pin that was correct | `bin/pins.py` | running it |
| 39 | The free-space floor called `disk_usage` on the state *file*, which does not exist on a first run — so the check silently did nothing on exactly the run where a host is emptiest | `easy_grading_sync.py` | a test |
| 40 | `Environment=EASY_GRADING_IMAGE_NAMES=tiivad silmused pygrader imgrec` in a systemd unit. `Environment=` **splits on whitespace**: systemd kept `tiivad` and parsed the rest as assignments that went nowhere, so the About page showed one grading image out of four while CI, the registry, the reconciler and `state.json` were all correct | `easy-executor.service.j2` | applying it to dev and reading the page |

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

### And from EZ-1781, one that hid rather than failed

| What | Found by |
| --- | --- |
| The grading-image cache in `aae/containers.py` is two pieces of global state — a module attribute, and a file whose default path is in the machine's tempdir and so is **shared between runs**. Only the one test file that knew about it neutralised them, so a test asserting the endpoint answers `[]` passed on a fresh CI runner and failed on a laptop that had run the suite before | running the suite twice on one machine |
| The background refresh thread read that path from the module global *when it got round to writing*, which in the suite meant after its own test had put the value back — so it wrote to the real machine-wide path | the same, once the fixture was in place and the file came back anyway |

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

The waiver is gone now — EZ-1777 was fixed on 2026-08-21, `type` is a Kotlin enum on both requests
and the response, and the mechanism worked as designed: deleting the entry was not optional, because
a waiver that stops firing fails the test.

### Closing a two-sided disagreement means picking a vocabulary, and the fixtures are data too

Fixing EZ-1777 read like a three-file change: an enum in `Enums.kt`, the column through
`enumerationByName`, the DTOs. It was seven, and the four extra ones are the interesting part.

**The client's spelling was the wrong one.** Web sent `'comment'`/`'suggestion'`; every other enum on
this API is on the wire in the Kotlin constant's own casing — `AUTO`, `COMPLETED`, `PRAWM`. So the
vocabulary moved to the server's and `types.ts`, `exercises.ts` and `AnnotatedCodeEditor.tsx` moved
with it in the same commit, with a test asserting the old spelling is now **refused** — an
implementation that quietly accepted both would look identical from the outside.

The first version of this paragraph justified that refusal by saying leniency would leave two
spellings in the column, and **that was wrong**: `ACCEPT_CASE_INSENSITIVE_ENUMS` maps `comment` to
`COMMENT` and Exposed writes the constant, so lenient input could not have reached the column at all.
The review caught it. The real reasons are narrower and worth having straight — the feature is a
global `MapperFeature`, so it would loosen every enum-typed field on the API to buy one field a
migration window, and the window buys compatibility with a client replaced in the same commit on an
unreleased version. **A decision defended by a wrong mechanism is worse than one defended by a thin
reason, because nobody re-examines it.**

**`testdata.xml` is data, and it had already run.** The seeded inline comments carried the lowercase
values, so a fix that only touched code would have left every developer's database and dev itself
serving rows core can no longer read. Editing those inserts is editing an *applied* changeset, which
is the one failure this repo treats as worst — Liquibase then refuses to run and core does not start —
so it needs `<validCheckSum>ANY</validCheckSum>` and a normalising `<sql>` changeset placed where it
runs before the fixtures on a fresh database and after the existing rows on an old one. Neither of
those is about inline comments. **A type-level fix to a stored value is a migration, and the
migration's hardest question is usually where the old values live rather than what to do with them.**

The one thing that did *not* need measuring was the row count, and it is worth saying why, because
`210826-1` and `210826-2` a week earlier needed exactly that. Those two were deciding **whether** a
backfill was required and the answer was in the data. This one rewrites every row it finds either
way, so the count could not change a character of the changeset — and in production it will find
nothing at all, the table being new in the unreleased v4.0.

The **dry run** still mattered, for the reason two sections down: `:core:test` runs Liquibase against
an empty table, so nothing there executes a single one of the three statements. Against 14 constructed
rows — both spellings, mixed case, an empty string, a junk value, one already correct — it came out 8
COMMENT / 6 SUGGESTION, nothing outside the enum, and every row carrying a suggestion body a
SUGGESTION. Two things that only running it showed: `lower(type)` in the first statement quietly
absorbs the mixed-case row, so the third never sees it; and the empty-string row with a suggestion
body is reconstructed rather than defaulted, which is the whole argument for deriving from
`suggested_code` instead of writing `ELSE 'COMMENT'`.

**Another detector that could not fire — the family two sections up, arriving two days after the seven.**
The review found the `created_at`-only ordering in `selectInlineComments` (defect 30), and the
tiebreaker is a one-line fix. Pinning it was the problem. The test written for it inserted two rows
sharing an instant, then UPDATEd the older one, on the theory that Postgres writes the new tuple later
in the heap so a sequential scan would return them the wrong way round — and it **passed with the
tiebreaker and without it**, carrying a comment that claimed the failing case had been checked. That
section's own last instruction, "remove the fix and check the rule fires", is the only reason the
comment is not in the repo.

Deleted, and the absence is documented at the call site instead. The general point is not "test your
tests" — it is that **a tie has no wrong answer you can demand.** Where the plan decides, a green test
is evidence about this plan on this data on this Postgres, so the honest artefact is the fix plus a
note saying nothing pins it. Compare EZ-1763, where the same defect class *was* testable: `DISTINCT
ON` has to discard a row, so the wrong choice is observable, and a fixture with a deliberate tie flaked
4 runs in 5. Here both rows come back either way and only their order differs.

**And a smaller measured thing, in passing.** Rewriting that changeset's `<comment>` after it had been
applied did *not* change its checksum — `ChangelogIntegrityTest` stayed green with the baseline
untouched. Its own failure message said the opposite ("a comment … is still a new checksum"), so the
message is now narrower and says which part was measured. Liquibase keeps `<comment>` in the changelog
table's `COMMENTS` column rather than hashing it. A guard's advice is prose like any other and goes
stale the same way; this one had been telling people to reach for `validCheckSum` they did not need.

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

### Printing the command was not enough

`doc/testing.md`'s own opening argument is that hand-maintained counts go stale, and its fix was to
print the shell command beside each figure. That fix **failed three times in two days.** Not because
anyone ignored it: printing a command still asks a human to run six of them, sum a seventh, and do it
at the same commit the reader will be at. The repo moved in between, every time.

The instructive one is the third. A memory note recorded core 190, aae 113 and two test suites; I
measured at `b3607bf8`, found core 189, aae 91 and neither suite present, and concluded the note was
wrong. It was not — EZ-1781 landed those suites in `a28c4573`, ten commits ahead of where I was
looking. **Both readings were correct at their own commit, and neither carried one**, which is what
made them look like a contradiction instead of a sequence.

So the rule is not "measure" — it was already "measure". It is:

- **Record the commit beside the number.** A count without a revision is not a measurement, it is a
  rumour. This applies to notes and commit messages as much as to documents.
- **Make the measurement one command, not six.** `bin/testcounts` prints every figure, and
  `--run` also runs each suite and reports what it says. The cost of refreshing the document is now
  lower than the cost of arguing about whether it is current.

It is deliberately **not** a gate. Two figures are environment-dependent — `aae` reports 119 with
`tiivad` installed and fewer without, and the browser suite skips a spec that needs a real core — so
equality would fail for reasons that are not defects. A gate that fires on non-defects gets muted,
and then it is worse than the prose it replaced.

Writing it also reproduced two traps this log already documents, which is its own small argument for
tools over instructions. The JVM line came back **empty** because Gradle held `:tsl:test` UP-TO-DATE
and printed no summary at all — the staleness trap from `bin/mutate.sh`, arriving by a different
door, and silent in the reassuring direction. And two Python suites reported "No module named
pytest" because the system interpreter is not the one CI uses. Both were visible only because the
script prints every line rather than the one it expected.

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

### A migration that only runs against an empty database has not been tested

EZ-1771 added `NOT NULL` to three columns. `:core:test` runs Liquibase against a fresh
Testcontainers database with no rows, so the backfill matched nothing and all three constraints
succeeded trivially. Green, and worth nothing: the only interesting question about
`addNotNullConstraint` is what it does when a null is actually there.

The answer came from a dry run against dev's anonymised copy of production, inside a transaction that
rolls back:

```
before            32 student nulls, 40 teacher nulls
UPDATE 32 / UPDATE 40
after backfill     0 / 0
ALTER TABLE ×3     ok
ROLLBACK
after rollback    32 / 40   (unchanged, as intended)
```

(Row totals deliberately left out of the committed files — this repo is public, and the null counts
are what the reasoning needs.)

That is the whole test, and it takes a minute. Two things it establishes that no unit test could:
the backfill's join covers **every** null row (32 updated of 32 that exist, not 31), and the
constraint the migration would then add actually holds. A missed row means `addNotNullConstraint`
fails, and because migrations run inside the Spring context, **core does not start** — on whichever
environment has the row, which by definition is the one nobody tested against.

Generalising: for a data-rewriting changeset, the test that matters runs against data. Write the
`BEGIN … ROLLBACK` dry run before writing the changeset, not after, because it is also how you find
out whether your backfill source is populated for the rows that need it — a source that is itself
missing on some rows leaves them behind, and the constraint then fails.

`doc/core/migration-dry-run.sql` is that as a fill-in-the-blanks template, including the two steps
easy to skip: checking the backfill source's coverage *before* trusting it, and re-counting after the
`ROLLBACK` so a dry run cannot be mistaken for having applied it.

**And the guard for the other direction already existed.** Deleting the two changesets and re-running
`SchemaMatchesTablesTest` names all three columns — because `knownNullabilityDrift` is now empty, so
there is nothing left to excuse them. That is the shape worth copying: the exemption list could not
outlive the exemption, since an entry that no longer disagrees fails the test.

### A gate can have an invariant nobody wrote down

The check-count ratchet is one number per spec **file**, and `spec.mjs` compares it in each test's
teardown. Every one of the 34 specs had exactly one `test()`, so nobody had found out what happens
with two — and what happens is bad in both directions at once:

- each test compares *its own* count against the file's single number, so every test but the largest
  fails the ratchet; and
- `record-checks.mjs` keys its Map by file, so `counts.set(spec, count)` keeps only the **last**
  test's count. Recording therefore "fixes" the failure by lowering the floor to whatever the last
  test happened to report.

The first symptom is loud, which is lucky, because the repair for it is silent and wrong. Adding two
tests to `participants-groups.spec.mjs` produced exactly that: a red run whose obvious fix would have
dropped the file's floor from 10 to 5.

Split into three files, and `record-checks.mjs` now refuses a file that reports two different counts,
naming the cause. The general shape is worth carrying: **a mechanism that has only ever been used one
way has an untested invariant, and it is usually the invariant that the mechanism's own error message
does not mention.** The ratchet's message talked about removed assertions and early returns — both
real, neither what was happening.

### Two findings that came out of writing one test

Neither was in the application logic the spec set out to cover, and both were found by asking "how do
I even reach this state?".

**The state that makes the code reachable was itself the bug.** Group membership can only be edited
when `!isMoodleLinked`, and Moodle-pending students only exist when the course syncs with Moodle — so
`moodle_pending_students`, which the client partitions the selection to produce, looked like dead
code. It is not: `LinkCourseMoodle` unlinks by setting `moodle_short_name = null` and deleting
nothing, so an unlinked course still reports its pending rows. That is the only state where both
halves exist at once — and following it one step further found that
`JoinMoodleLinkedCourseByInvite.join()` matches on invite id with no predicate on the course still
being linked, so **the outstanding invitations still enrol** (EZ-1780). A teacher who unlinks to stop
Moodle enrolment has not stopped it.

Worth generalising: when a code path looks unreachable, the interesting question is not "is it dead"
but "what would have to be true for it to run" — the answer here was a bug two files away.

**`<p>` cannot contain a `<div>`, and the browser does not complain.** `ConfirmDialog` wraps its
message in a bare `<Typography>`, which renders `<p>`; the group-deletion confirmation passes a `<ul>`
of affected students inside `<Box>`. React logs it, the browser silently closes the paragraph early,
and nothing fails. It sat in the console output of a passing spec — the harness prints console errors
and has never gated on them. Fixed with `component="div"` and pinned by a check that collects
`cannot contain a nested` and asserts none, which fails when the fix is reverted.

The broader gap stays open on purpose: **console errors are printed and never gated suite-wide**, and
turning that into a gate is its own change with its own backlog. Recorded here so it is a decision
rather than an oversight.

### Deleting dead code can fail a coverage gate, and that is the gate working

Removing the `or` combinator (EZ-1773) took `core.ems.service.access_control` from **85% to 84%** and
failed `koverVerifyTargets`. Nothing got worse. The deleted code was *the best-tested thing in the
package* — six tests for a facility with zero callers — so taking it out left the ratio standing on
the lines that were never covered.

The tempting fix is to move the target to 84%. It is the wrong one, and the right one paid for
itself: the gate was now telling the truth about **17 untested lines of access-control code**, and
among them was `isCourseExerciseOnCourse`'s student-visibility branch — the whole of what stops a
student reading an exercise before its visible-from date, reached by five `/student/` endpoints.
Covering the real gaps took the package to **94%**, further above the line than before the deletion.

Two things generalise:

- **A coverage percentage is a ratio, so it moves when you change either side.** A drop after a
  deletion says nothing about whether the remaining code got worse; look at what is missed, not at
  the number. The Kover XML report answers this in about a minute:
  `python3 -c` over `report.xml`, filtering `line[@mi!='0']`, gives the uncovered line numbers per
  file, and reading fifteen of them is faster than arguing about a threshold.
- **The dead code was hiding the live gap.** `student_visible_from = null` means "not scheduled" and
  had to read as *hidden*; nothing had ever asserted that, and getting it wrong would be silent,
  permissive, and indistinguishable from correct behaviour on any course whose exercises are all
  published. It is now `access/unscheduled-reads-as-visible` in `bin/mutate.sh`, and it is CAUGHT.

The same pass found two more dead things in that file: `testTrue()` and `testFalse()`, deprecated
debug helpers with no callers, which were the package's last two uncovered lines. `testFalse()`
refuses all access — a helper that 403s an endpoint, living inside the access-control builder, one
forgotten line from being shipped. Deleted.

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

### From EZ-1781, the grading-image pins system

**A list is not tested by testing one element.** Every test of the image reporter set up a single
image, so the reporter was thoroughly tested and *the length of its output never was*. #40 passed all
of them. The test that would have caught it is the boring one — four in, four out — and it did not
exist because each interesting case only needed one image.

**A gate that cries wolf on the normal case is worse than no gate.** #38 reported a problem for the
correct pin. The first thing anybody does with a check like that is stop reading its output, at which
point it is worse than absent: it has consumed the attention a real failure needed.

**Nine of eleven came from reading, not from running.** The two that needed a real host (#38, #40)
needed it absolutely — no amount of review would have produced `systemctl show`. But the majority
were visible in the diff, and the review that found them ran *after* the code was written and
committed, which is later than this repo's own convention says. It was still worth it; it would have
been worth more earlier.

**Everything upstream can be correct and the answer still wrong.** #40 is the clearest case in this
log: the registry, the workflow, the reconciler, `state.json` and the labels on disk were all right,
and the page showed a quarter of the truth. Verify the thing a person reads, not the last thing the
machinery did.

**A leak that hides is worse than one that fails.** The cache leak passed on CI and failed on a laptop — the
direction that looks like a local problem. Reversed, it would have been fixed the first time anybody
ran the suite.

**Read the log, not the exit code.** The reconciler is deliberately forgiving: a failed pull leaves
the previous image alone, and because `docker image inspect <bare name>` still succeeds, the closing
assertion passes. Degrading safely and reporting success are the same thing to a shell.

**Check the claim in the comment.** I wrote that a missing build argument would be caught by the
version guard, then tested it: numpy was installed first and failed with an opaque pip error about
`numpy~=`, which says nothing about the missing argument. The comment was wrong for two of the four
images at the time it was written.

**Delete the file before asking whether something recreated it.** Twice while chasing the cache leak
I concluded a fix had not worked, from a file the *previous* run had left. An existence check is only
evidence if the starting state was known.
