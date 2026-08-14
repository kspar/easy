# Testing: where we stand, and what is missing

How to *run* the tests is in `DEVELOPMENT.md` §5 (core) and `doc/web/browser-testing.md` (web).
This document is the other question: what is actually covered, what is not, and which kinds of
tests we do not have at all.

Written 2026-08-03, after a stretch of work on the exercise editor and embedding that added a lot
of tests and, more usefully, showed which ones were worth having.

**Where this is tracked.** Two long-lived issues own the work; this document is the survey behind
them rather than a replacement for them.

- **EZ-1705 (Set up automated tests for React web)** — the web side. Its comment history is the
  best record of how the harness got here and what each round of it caught.
- **EZ-1366 (R&D unit tests)** — the core side, open since 2021 and one line long.
- **EZ-1715 (Run core tests in CI with a postgres service container)** — the prerequisite for
  almost everything backend below.
- **EZ-1723 (Set up a dev environment at dev.lahendus.ut.ee)** — the prerequisite for the
  integration and migration testing that needs real infrastructure.

---

## Where we stand

Counted, not estimated:

| | Size | Tests |
| --- | --- | --- |
| **core** | 111 `@RestController` classes | 6 test files, 34 `@Test` methods |
| **web** | 96 `.ts`/`.tsx` files | 3 unit files, 9 browser scripts |
| **tsl / tsl-common** | the TSL compiler | none |
| **aae** | the executor | none |

The web numbers look better than they are, because the check counts are lopsided:

```
unit      44 examples + 11,903 property checks + 532 merge checks
browser   291 checks across 8 scripts (a 9th needs a real core)
```

Nearly all of the property checks belong to one module — the markdown editing commands. That is
not misplaced effort (they found real bugs, repeatedly) but it means the totals say very little
about breadth.

On the core side, **25 of the 34 tests run in CI**. The other 9 are tagged `db` and skipped,
because they need a PostgreSQL instance and the gitignored `core/src/test/resources/application.yaml`
— EZ-1715 (Run core tests in CI with a postgres service container). That issue is the single
biggest unlock in this document: almost every kind of backend test below is blocked behind it.

**And skipped is not the same as passing.** Those 9 are the whole of
`ValidateSelectAllCourseExercisesLatestSubmissions`, and two of them fail most of the time — the
fixture gives two submissions `DateTime.now()` back to back, so they can share a millisecond and
"the latest submission" is then a coin toss between grade 71 and grade 81. Measured at 4 failures
in 5 consecutive runs on 2026-08-14, on code that had nothing to do with them — EZ-1763. Nobody
noticed because CI has never run them, which is the argument for EZ-1715 in one sentence.

---

## What is not covered

Web pages, by size, with what exists today:

| Page | Lines | Coverage |
| --- | --- | --- |
| `ParticipantsPage` | 1823 | **none** |
| `CourseExercisesPage` | 1155 | 110 checks — the best-covered page in the app |
| `CourseExercisePage` | 948 | **only the embed action** (12 checks) |
| `ExerciseLibraryPage` | 798 | 11 checks — listing, sort, filter, create |
| `GradeTablePage` | 514 | **none** |
| `ExercisePage` (library) | 496 | 112 checks across three scripts |
| `SimilarityPage` | — | **none** |
| `EmbedExercisePage` | — | 37 checks |

Priority, by consequence rather than by size:

1. **`CourseExercisePage`.** The teacher grading flow — submissions, feedback, the testing tab,
   the settings dialog. A bug here costs a student a grade, which is the most expensive kind of
   bug this application can have. `course-exercise-embed.mjs` already stubs enough of its
   endpoints to build on.
2. **`ParticipantsPage`.** Biggest file in the repo, no coverage. Group management, invites and
   Moodle sync, the last of which reaches a real external system.
3. **`GradeTablePage`.** Grades are the output of the whole application and nothing checks the
   table that shows them.
4. **`SimilarityPage`.** Plagiarism results shown to teachers about named students. Low volume,
   high sensitivity.

---

## Kinds of tests, and which we should actually invest in

### Unit tests — have some, want more of the same kind

What works: pure logic pulled out of components and pinned by example *plus* a property sweep.
Two in the repo earned their keep immediately — the markdown commands (2 real bugs found on the
first run of the matrix) and `mergeField` (532 checks over the function that silently decides whose
edit survives a concurrent save).

The lesson is about *which* logic: not everything, but anything where **being wrong is invisible**.
Offset arithmetic, merge resolution, permission derivation, date/deadline comparisons, grade
thresholds. If a bug shows up as a wrong number rather than a stack trace, it wants a unit test.

Cost is near zero — `npm run test:unit` needs no framework and no dependency the app lacks, because
esbuild resolves the TypeScript and Node runs the result.

**Worth doing next:** `hasAccess` / `DirAccessLevel` derivation, deadline and visibility logic in
`CourseExercisesPage`, and the TSL compiler in `tsl/` which has no tests at all despite being a
compiler — the one category of program where property tests are most obviously appropriate.

### Service tests (backend, one controller, real database) — have almost none, want them most

29 tests against 111 controllers. The 9 that exist and need a database are the shape to copy:
call the service layer against a real PostgreSQL, assert on rows.

This is where the highest-value untested logic lives — access control (`assertAccess`, the
`DirAccessLevel` hierarchy, group visibility), grade aggregation, the Moodle sync, and every
`permitAll` endpoint, which are reachable by anyone on the internet.

**Blocked on EZ-1715.** Until CI has a postgres service container, anything written here only runs
on someone's laptop, which in practice means it stops running.

### Integration tests (several components together) — have none

Nothing exercises core + database + executor together, or core + IdP. The realistic candidates:

- **Submission → grading → feedback**, through a real executor container. This is the application's
  central promise and nothing tests it end to end.
- **JWT verification against a real Keycloak.** `core/dev-idp/` already mints deliberately broken
  tokens, so the hard part exists; nothing automated consumes it.
- **Liquibase migrations against a realistic database.** See below — this deserves its own line.

Best done on dev (EZ-1723) rather than in CI: they need infrastructure CI has no business
standing up, and dev exists precisely to be a place where real things can be run.

### UI tests — have a good harness, uneven coverage

`doc/web/browser-testing.md` covers the mechanics. The approach is sound: real Chromium, real
router and components, backend faked by Playwright route interception. Fast enough to run on every
push, and the screenshots have caught layout problems no assertion would.

The gap is breadth, not technique — see the table above.

Two things it does *not* prove, worth stating so nobody assumes otherwise: the API contract is
real (every response is a fixture we wrote), and the app works in any browser but Chromium.

### Contract tests — have none, and the gap is real

Every browser test stubs the backend from hand-written fixtures. Nothing checks those fixtures
still match what core returns. The failure mode is a green suite and a broken app, and this
session produced a live example: a fixture kept `anonymous_autoassess_template: null` after the
column became non-nullable, and nothing noticed because both sides were mocked.

Cheapest useful version: generate or validate fixtures against the real response shapes — an
OpenAPI document core already nearly has (`doc/core/api.yaml.outdated`, which the name admits is
stale), or a small set of "shape" tests that hit a local core and assert on keys and types.

### Migration tests — have none, and we now have a reason to want them

Changeset `020826-1` (making the template non-nullable) rewrites production rows. It was tested by
hand, against a copy of the dev database, once. That is better than nothing and worse than a test.

The mechanism is cheap: restore a dump into a scratch database, run Liquibase, assert on the
resulting schema and data. It becomes considerably more valuable once dev exists and there is
an anonymised production copy to run it against (`doc/core/anonymise-db/`), because the
interesting migration failures are all about real data rather than clean schemas.

### Performance tests — none, and mostly fine

No load tests, no query-time budgets. Mostly the right call for the size of the deployment, with
two exceptions worth measuring rather than assuming:

- **The grade table and participants list on a large course.** Both build big tables client-side
  and both fetch everything. There is no evidence either is a problem, and no evidence it is not.
- **Executor concurrency.** `aae/gunicorn-conf.py.sample` sets `workers = 30` with Docker builds
  behind it, and VM sizing for dev is an open question in `doc/dev-environment.md` §10.4
  precisely because nobody has numbers.

A single scripted "1000 students, one course" fixture would answer more than a load-testing
framework would.

### Accessibility tests — none, and cheap to start

The app is used by students under exam conditions and by teachers all day. This session found two
real a11y defects by accident — a switch demoted to a checkbox by `slotProps`, and two controls
sharing one accessible name. Both were found because Playwright locators use the accessibility
tree, which is a hint: `@axe-core/playwright` in the existing harness would catch a class of these
automatically for the cost of one dependency.

---

## What makes a test here trustworthy

Learned the hard way in the work that produced this document. Several tests that *passed* were
proving nothing:

- **A positional locator that quietly moved.** `.cm-content` `.first()` pointed at the snippet
  until a second editor appeared above it, after which every negative assertion — "does not
  contain iframe-resizer" — passed against a document that could never have contained it. Select
  by content or role, not position.
- **A stub shadowed by another stub.** A handler for `/exercises/{id}` also matched
  `/exercises/{id}/anonymous/details`, so the embed page was served the wrong object. It had a
  title and text, so the preview looked plausible while being wrong. Order handlers most-specific
  first, and assert on rendered *content*, not on an element existing.
- **An assertion that matched the default.** "Reopening keeps the options" checked a value that was
  also the default, so it passed whether or not anything was remembered. Assert on a value that
  differs from the default.
- **A listener installed on the wrong document.** A `postMessage` collector added before a
  navigation died with that document, turning "no messages were posted" into a test that could not
  fail. Use `addInitScript`.
- **Timing dressed up as behaviour.** An assertion made on the next tick about something that only
  appears after a refetch resolves — passing or failing on how fast a stub replied. Poll with
  `waitUntil`.

The pattern: a test that cannot fail is worse than no test, because it is counted. When adding
one, it is worth making it fail on purpose once.

---

## Suggested order

1. **EZ-1715** — postgres in CI. Unblocks everything backend.
2. **`CourseExercisePage` browser coverage** — highest consequence untested surface.
3. **Service tests for access control** — internet-reachable, and wrong answers are silent.
4. **Contract or fixture-shape checks** — stops the whole browser suite drifting from reality.
5. **`ParticipantsPage` and `GradeTablePage`** — largest remaining untested surfaces.
6. **Migration tests against an anonymised copy** — once dev exists.
7. **axe in the browser harness** — cheap, and there is already evidence it would find things.
8. **Performance measurement of the two known suspects** — a fixture, not a framework.
