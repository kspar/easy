# Testing: where we stand, and what is missing

How to *run* the tests is in `DEVELOPMENT.md` §5 (core) and `doc/web/browser-testing.md` (web).
This document is the other question: what is actually covered, what is not, and which kinds of
tests we do not have at all.

Written 2026-08-03, after a stretch of work on the exercise editor and embedding that added a lot
of tests and, more usefully, showed which ones were worth having. **Substantially revised
2026-08-15**, when the goal changed from "more coverage" to "a green build is a deploy decision"
and every count in here turned out to be wrong.

**Where this is tracked.** **EZ-1766** is the programme; this document is the survey behind it.

- **EZ-1766 (A test suite good enough that a green build is a deploy decision)** — the umbrella,
  with the phase ordering and the decisions taken.
- **EZ-1705 (Set up automated tests for React web)** — the web side. Its comment history is the
  best record of how the harness got here and what each round of it caught.
- **EZ-1366 (R&D unit tests)** — the core side, open since 2021.
- **EZ-1715 (postgres in CI)** — **done**, 2026-08-15, though not the way it was written.
- **EZ-1723 (dev environment)** — the prerequisite for testing that needs real infrastructure.
- **EZ-1710 (Automate releases)** — owns everything *downstream* of a green build. Finishing
  EZ-1766 does not by itself enable automatic production deploys; it removes the coverage reason
  not to.

---

## Where we stand

Counted, not estimated — and this time with the commands, because hand-maintained numbers going
stale is exactly what happened to the first version of this table.

**As of 2026-08-15:**

| | Size | Tests |
| --- | --- | --- |
| **core** | 117 `@RestController` classes | 12 test files, **40 `@Test` methods, all running** |
| **web** | 119 `.ts`/`.tsx` files, 29,292 LOC | 4 unit files, 28 browser scripts (27 in CI) |
| **tsl / tsl-common** | the TSL compiler | none |
| **aae** | the executor | none |

```sh
grep -rl '@RestController' core/src/main/kotlin | wc -l          # 117
find core/src/test/kotlin -name '*.kt' | wc -l                   # 12
find web/src -name '*.ts' -o -name '*.tsx' | wc -l               # 119
ls web/dev-harness/scripts/*.mjs | wc -l                         # 28
grep -ho 'check(' web/dev-harness/scripts/*.mjs | wc -l          # 599 call sites
```

The web numbers look better than they are, because the check counts are lopsided:

```
unit      44 examples + 11,903 property checks + 532 merge checks
browser   ~581 checks across the 27 scripts CI runs (a 28th needs a real core)
```

Nearly all of the property checks belong to one module — the markdown editing commands. That is
not misplaced effort (they found real bugs, repeatedly) but it means the totals say very little
about breadth.

### What the first version of this table got wrong

Worth recording, because it is the argument for the shell commands above. It claimed **111**
controllers (117), **96** web files (119), **9** browser scripts (28), **291** checks (581) — and
on the core side, **34 tests of which 9 were tagged `db`**. The real numbers were 32 and 7. Every
one of those was written by hand and none was ever re-derived.

It also omitted `LandingPage` from the coverage table entirely — 1225 lines, the second-largest
page in the app.

### The backend blocker is gone

**All 40 core tests now run, in CI and on a laptop, with no setup.** EZ-1715 landed on 2026-08-15,
but not as written: Testcontainers rather than a postgres service container, because a service
container fixes CI and leaves the laptop exactly as broken as it was — which is *why* the
database-backed tests had stopped running in both places. `DEVELOPMENT.md` §5 has the mechanics.

Two things that issue turned out to be scoped at about a third of: the test config
(`core/src/test/resources/application.yaml` is now **committed**, so CI starting the context is
what keeps the `${easy.*}` placeholder list honest), and test isolation — `PER_CLASS` plus
`dropAll` in `@AfterAll` does not scale to a second test class, it *breaks* on one.

**And the flaky test was right.** EZ-1763 was filed as a fixture problem: two submissions given
`DateTime.now()` back to back share a millisecond, so "the latest submission" was a coin toss
between grade 71 and 81, at 4 failures in 5 runs. The fixture was only what made a **production**
bug reproducible — `selectAllCourseExercisesLatestSubmissions` used `DISTINCT ON` with an ordering
that was not total, so which submission survived was whatever the query plan produced. A student
could see a grade that changed on refresh. Two sibling queries had the same defect, and in
`StudentReadSubmissions` it is worse: a non-total order under `LIMIT`/`OFFSET` lets rows be skipped
or repeated *between pages*.

The lesson is the one worth carrying: **when a test flakes on a tie, suspect the query before the
fixture.** Spacing the fixture rows out would have made it green and hidden the bug. `TestClock`
plus a banned `DateTime.now()` is the fixture half; the tiebreakers are the real fix.

---

## What is not covered

Web pages, by size, with what exists today. Corrected 2026-08-15 — six of the eight rows in the
previous version were wrong and nine pages were missing.

| Page | Lines | Coverage |
| --- | --- | --- |
| `ParticipantsPage` | 1823 | **none** |
| `LandingPage` | 1225 | 16 |
| `CourseExercisesPage` | 1155 | 110 — the best-covered page in the app |
| `CourseExercisePage` | 968 | 51 across four scripts — but **none of it the grading flow** |
| `ExerciseLibraryPage` | 798 | 11 — listing, sort, filter, create |
| `GradeTablePage` | 549 | 6 — deep links only |
| `ExercisePage` (library) | 481 | 171 across five scripts |
| `EmbedExercisePage` | 395 | 34 |
| `CoursesPage` | 366 | **none direct** — five scripts visit `/courses` but assert on global chrome |
| `SystemMessagesPage` | 350 | 17 |
| `SimilarityPage` | 320 | 15 |
| `AboutPage` | 313 | 30 |
| `AccountSettingsPage` | 290 | 14 |
| `ArticlePage` | 289 | ~34 |
| `ArticlesPage` | 130 | in `articles.mjs` |
| `JoinByLinkPage` | 92 | **none** |
| `NotFoundPage` | 19 | none |

**Zero-coverage routes** — stated as routes rather than files, because that is what a reader can
check against `routes/routes.tsx`: `courses/:courseId/participants`, `link/:inviteId`,
`moodle/link/:inviteId`, `courses`, `*`.

Priority, by consequence rather than by size:

1. **The grading flow on `CourseExercisePage`.** Restating the previous version accurately: it now
   has 51 checks, but they are the embed action, teacher testing, the assessment tab and retry —
   *not* selecting a student, loading their solution, and saving a grade. That is still the most
   expensive kind of bug this application can have.
2. **`ParticipantsPage`.** Biggest file in the repo, no coverage. Group management, invites and
   Moodle sync, the last of which reaches a real external system — and already has a bug in it
   (EZ-1768: the sync poll stops after the first flag change and never restarts).
3. **`GradeTablePage`.** Grades are the output of the whole application, and the 6 checks it has
   are all about deep links. Also already has a bug (EZ-1767).
4. **`JoinByLinkPage`.** 92 lines, and nearly every one is an invisible-if-wrong branch — the
   invite id is upper-cased before lookup, the Moodle prefix appears in two places, and there is a
   guard whose comment describes a fixed bug that nothing pins.

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

40 tests against 117 controllers. **No longer blocked** — EZ-1715 is done.

This is where the highest-value untested logic lives — access control (`assertAccess`, the
`DirAccessLevel` hierarchy, group visibility), grade aggregation, the Moodle sync, and every
`permitAll` endpoint, which are reachable by anyone on the internet.

One correction to the previous version, which said the database-backed tests were "the shape to
copy". They were the shape to **replace**: `@TestInstance(PER_CLASS)` plus `dropAndUpdateSchema` in
`@BeforeAll` plus `dropAll` in `@AfterAll` plus 200 lines of inlined `insert {}` plus a
`@MockitoBean` and a `@TestPropertySource` is four separate things that do not scale — context
forking, cross-class schema destruction, fixture duplication, and `DateTime.now()` collisions. The
EZ-1763 paragraph above is a symptom of the fourth. The shape to copy now is `@IntegrationTest` plus
`Fixtures`; see `DEVELOPMENT.md` §5.

### Coverage by construction — the technique this document was missing

Every item here is phrased as "write tests for X", which means a controller added next month is
untested by default. With 117 controllers that does not converge, and it is why the backend has
stayed at 40 tests.

`RichTextColumnsTest` already demonstrates the alternative in this repo: a reflection guard that
**fails the build until someone decides** which category the new thing belongs to. Applied to
endpoints (EZ-1769) it gives two things nothing else does — every handler must be `@Secured` or
explicitly on the `permitAll` allowlist, and every handler must have a sample request in a registry.
One parameterised test over sample × {anonymous, student, teacher, admin} then turns "we tested some
endpoints" into "no endpoint is reachable by a role that should not reach it".

This should be the default reach for anything with more than a dozen instances.

### Integration tests (several components together) — have none

Nothing exercises core + database + executor together, or core + IdP. The realistic candidates:

- **Submission → grading → feedback**, through a real executor container. This is the application's
  central promise and nothing tests it end to end.
- **JWT verification against a real Keycloak.** `core/dev-idp/` already mints deliberately broken
  tokens, so the hard part exists; nothing automated consumes it.
- **Liquibase migrations against a realistic database.** See below — this deserves its own line.

The "best done on dev rather than in CI" conclusion was **half right**, and the half it got wrong is
the expensive one. It holds for real Keycloak and for migrations against real data. It does not hold
for submission → grading → feedback, which this same paragraph calls the application's central
promise: a fake executor is a `com.sun.net.httpserver` handler with no new dependency, and
`testdata.xml` already registers an executor at `http://localhost:5111` for `mock-executor/server.mjs`
to answer. That is an afternoon in CI, including the failure legs — executor 500, timeout,
`statusInProgressToFailed` on restart — which are the parts nobody exercises by hand.

Migrations and real-Keycloak testing stay on dev (EZ-1723): they need infrastructure CI has no
business standing up, and a production dump has no business reaching CI.

### UI tests — have a good harness, uneven coverage

`doc/web/browser-testing.md` covers the mechanics. The approach is sound: real Chromium, real
router and components, backend faked by Playwright route interception. Fast enough to run on every
push, and the screenshots have caught layout problems no assertion would.

The gap is breadth, not technique — see the table above.

Two things it does *not* prove, worth stating so nobody assumes otherwise: the API contract is
real (every response is a fixture we wrote), and the app works in any browser but Chromium.

### Contract tests — have none, and the gap is real

Every browser test stubs the backend from hand-written fixtures. Nothing checks those fixtures
still match what core returns. The failure mode is a green suite and a broken app, and there is a
live example on record: a fixture kept `anonymous_autoassess_template: null` after the column became
non-nullable, and nothing noticed because both sides were mocked.

**Decided (EZ-1770), and the important correction: this depends on nothing.** The previous version
of this document implied everything backend-adjacent was blocked behind EZ-1715. It is not — core's
DTOs carry `@get:JsonProperty` wire names explicitly and Kotlin nullability is visible to
`kotlin-reflect`, so a shape descriptor is recoverable by **reflection with no database and no
Spring context**. That was this document's single most consequential inaccuracy.

The design: a plain `@Test` emits `doc/core/api-shapes.json` and fails if the committed file differs.
The reason that beats generating OpenAPI is not size — it is that **the artefact is a file in git, so
the diff is the review**. When a column becomes non-nullable, the PR shows `"nullable": true → false`
on one line, and a human catches it before any fixture is touched. Web-side validation hooks into
`fakeApi` and derives endpoint identity from the request URL, so every existing script is retrofitted
with no edits.

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

## What "green" will mean

The condition EZ-1710 can read to decide whether to ship: run conclusion is success **and** the
JUnit report has zero failures **and** no quarantine entry has expired **and** contract-warning
counts are at or below baseline **and** the a11y baseline produced no new fingerprints.

All five are artefacts the suite produces anyway. The point of naming them is that "the build is
green" and "this is safe to deploy" should be the same sentence, checkable by a script.

## Suggested order

Revised 2026-08-15. **The previous ordering put browser coverage second, above backend access
control — that was wrong once auto-deploy became the goal**, and it is drift rather than error: the
list predates that goal. A browser test whose backend is Playwright route interception passes
against *any* backend, including a broken one. It cannot gate a backend deploy, by construction.

1. ~~**EZ-1715** — postgres in CI~~ — **done 2026-08-15.**
2. **Endpoint security surface and authorization matrix** (EZ-1769) — the highest value per hour in
   the programme, and the only item that keeps 117 controllers covered without 117 decisions.
3. **Contract checks** (EZ-1770) — stops the browser suite drifting from reality. **Depends on
   nothing; start it in parallel with 2.**
4. **Service tests for access control** — internet-reachable, and wrong answers are silent.
5. **Web migration to `@playwright/test` + `vitest`**, then the grading flow, `ParticipantsPage`,
   `GradeTablePage` and route guards.
6. **tsl and aae** — two components with no tests at all. Depends on nothing; parallelisable.
7. **Migration tests against an anonymised copy** — once dev exists.
8. **axe in the browser harness** — cheap, and there is already evidence it would find things.
9. **Performance measurement of the two known suspects** — a fixture, not a framework.
