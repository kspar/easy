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
| 13 | `moodle_username` is sent by core and **not declared** in `web/src/api/types.ts`, so the app cannot read it | `types.ts` | contract check, incidentally | EZ-1772 |
| 14 | The grade input has no `label` and no `aria-label`: a screen reader announces the control that sets a student's grade as "edit text, blank" | `ActivityFeed.tsx` | writing a locator for it | phase 9 |

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

### About the gate

**A browser test that stops early is indistinguishable from one that passed.** Every guard in
`tests/support/spec.mjs` exists for that: the check-count ratchet, the contract budget, the expiring
quarantine. The ratchet earned itself on its first run and has caught a miscount since.

**Estimates of coverage and measured coverage disagree.** "~581 checks" (read off output), "599"
(counted call sites) and 618 (what actually ran) were three different numbers for the same suite.
Only the one a runner writes is worth keeping.
