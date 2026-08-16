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
- **`doc/testing-log.md`** — the running record of what the programme has *found*: every defect,
  including the ones it introduced itself, and the lessons that cost time to learn. This document
  says what is covered; that one says what was wrong.
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

**As of 2026-08-16:**

| | Size | Tests |
| --- | --- | --- |
| **core** | 117 `@RestController` classes (124 endpoints) | 26 test files, **93 `@Test` methods, all running** |
| **web** | 120 `.ts`/`.tsx` files | 9 unit files, 33 browser specs (32 in CI) |
| **tsl / tsl-common** | the TSL compiler | none |
| **aae** | the executor | none |

```sh
grep -rl '@RestController' core/src/main/kotlin | wc -l          # 117
find core/src/test/kotlin -name '*.kt' | wc -l                   # 26
find web/src -name '*.ts' -o -name '*.tsx' | wc -l               # 120
ls web/tests/browser/*.spec.mjs | wc -l                          # 33
ls web/tests/unit/*.test.mjs | wc -l                             # 9
```

The web numbers look better than they are, because the check counts are lopsided:

```
unit      44 examples + 11,903 property checks + 532 merge checks, plus the grade table,
          the api client, i18n parity and the suite's own bookkeeping
browser   722 checks across the 32 specs CI runs (a 33rd needs a real core)
```

The browser number is now **measured rather than estimated** — it is the sum of
`web/tests/expected-checks.json`, which the suite writes and then enforces per spec. The earlier
figure here was "~581", read off run output by hand; 599 was the count of `check(` call sites, which
is a different number again because a call site inside a loop, a branch not taken, or a helper
called twice does not map one-to-one onto a check that ran. Three numbers, none of them derived the
same way — which is the argument for a file the runner writes rather than a number a human
maintains.

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

**All 93 core tests now run, in CI and on a laptop, with no setup** — 32 when this started, of which 25 ran. EZ-1715 landed on 2026-08-15,
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
| `ParticipantsPage` | 1823 | 25 — Moodle sync and the roster. **Groups still uncovered** |
| `LandingPage` | 1225 | 16 |
| `CourseExercisesPage` | 1155 | 110 — the best-covered page in the app |
| `CourseExercisePage` | 968 | 70 across five specs, now **including the grading flow** |
| `ExerciseLibraryPage` | 798 | 11 — listing, sort, filter, create |
| `GradeTablePage` | 549 | 29 — links, three sort orders, both filters, the CSV |
| `ExercisePage` (library) | 481 | 171 across five specs |
| `EmbedExercisePage` | 395 | 38 |
| `CoursesPage` | 366 | **none direct** — six specs visit `/courses` but assert on global chrome |
| `SystemMessagesPage` | 350 | 32 across the user and admin views |
| `SimilarityPage` | 320 | 15 |
| `AboutPage` | 313 | 30 |
| `AccountSettingsPage` | 290 | 14 |
| `ArticlePage` | 289 | ~19 |
| `ArticlesPage` | 130 | in `articles.spec.mjs` |
| `JoinByLinkPage` | 92 | 16 — both routes, the upper-casing, the join guard |
| `NotFoundPage` | 19 | none |
| `RequireAuth` (not a page) | 58 | 21 — every restricted route × role, and what leaks |

**Counts come from `web/tests/expected-checks.json`**, which the runner writes, rather than from
reading specs. `node -e "const j=require('./web/tests/expected-checks.json'); …"` sums it. A page
covered by several specs is the sum of those specs, so the rows do not partition the total —
`course-exercises` and the four `course-exercise-*` specs cover different pages despite the names.

**Zero-coverage routes**, stated as routes rather than files because that is what a reader can
check against `routes/routes.tsx`: `courses` and `*`. Down from five — `participants`,
`link/:inviteId` and `moodle/link/:inviteId` were covered in phase 6.

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

Cost is near zero — `npm run test:unit` runs the whole set in well under a second, because vitest
resolves the TypeScript sources directly and there is no browser and no DOM in sight.

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
untested by default. With 117 controllers that does not converge, and it is why the backend sat at
32 tests for years before EZ-1715.

`RichTextColumnsTest` already demonstrated the alternative in this repo: a reflection guard that
**fails the build until someone decides** which category the new thing belongs to. This should be
the default reach for anything with more than a dozen instances.

**Landed 2026-08-15** (EZ-1769). Four guards, all maintenance-free:

| Guard | What it makes impossible |
| --- | --- |
| `EndpointSecuritySurfaceTest` | An endpoint with no `@Secured` — which does not make it unreachable, it makes it reachable by *any* logged-in user, students included. Also asserts the `permitAll` list in **both directions**, because the failure that opens something to the internet is a pattern *broader* than its endpoints |
| `DtoWireNamesTest` | A Kotlin rename silently changing a public API field name, and any wire name that is not `snake_case` |
| `SchemaMatchesTablesTest` | A column in `Tables.kt` with no changeset, a changeset with no `Tables.kt` update, and nullability disagreeing between the two |
| `ChangelogIntegrityTest` | Editing an already-applied changeset — which stops core starting on every environment that already ran it — plus a non-idempotent migration, and `testdata.xml` escaping into the production context |

What they found on their first runs, none of which any existing test would have noticed:

- **Two endpoints with no `@Secured`.** `POST /v2/management/log` — reachable by any authenticated
  user, and it can send the admin an email, so any student could drive unbounded mail. And
  `GET /student/…/submissions/all`, the only one of the ten endpoints under `/student/courses/…`
  without the annotation. Neither was exploitable on its own, because both check access another
  way; both had the role check in one place instead of two.
- **Four nullability drifts.** `exercise.dir_id` had kept `.nullable()` in Kotlin for four years
  after a v2 changeset made it `NOT NULL` — so every read handed out a `Long?` that could never be
  null. The other three are the dangerous direction and are EZ-1771.

The lesson for whoever writes the next guard: the first version of `DtoWireNamesTest` collected
every data class *declared* in a controller and reported 40 findings that were nothing of the sort —
zip entries and intermediates Jackson never sees. Reachability from a handler's return type is the
correct filter. **"It found a lot" is not the same as "it found problems"**, and a guard that cries
wolf on its first run is one people learn to satisfy without reading.

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

Since 2026-08-16 the runner is `@playwright/test` rather than the hand-rolled one, and the unit
tests run under vitest. What that bought, beyond traces and a filter that cannot go stale, is three
ratchets the old runner had nowhere to put: a per-spec **check count**, the existing contract
budget, and an expiring **quarantine** file. All three exist because the failure this suite is most
exposed to is not a wrong assertion but a missing one — a spec that returns early is green.

The gap is breadth, not technique — see the table above.

Two things it does *not* prove, worth stating so nobody assumes otherwise: the API contract is
real (every response is a fixture we wrote), and the app works in any browser but Chromium.

### Contract tests — have none, and the gap is real

Every browser test stubs the backend from hand-written fixtures. Nothing checks those fixtures
still match what core returns. The failure mode is a green suite and a broken app, and there is a
live example on record: a fixture kept `anonymous_autoassess_template: null` after the column became
non-nullable, and nothing noticed because both sides were mocked.

**Done 2026-08-15 (EZ-1770).** `doc/core/api-shapes.json` is generated from core's Kotlin by
reflection and committed: 122 endpoints, 189 types, 182 nullable fields. A plain `@Test` regenerates
it and fails if the committed file differs.

The reason that beats generating OpenAPI is not size — it is that **the artefact is a file in git,
so the diff is the review**. When a column becomes non-nullable, the pull request shows
`"nullable": true → false` on one line, and a human catches it before any fixture is touched.
Web-side validation hooks into `fakeApi` and derives endpoint identity from the request URL, so all
28 existing scripts were retrofitted with **no edits to any of them**.

**It found the bug this section was written about.** Two fixtures still carried
`anonymous_autoassess_template: null` after changeset `020826-1` made the column non-nullable —
the exact example named above, still live, found on the first full run.

The severity ladder took two attempts, and the first one is the more instructive. Making a *missing*
non-nullable field a failure is the obvious rule, and it produced 19 failures in one script, **every
one of them correct behaviour**: a script stubs the fields the page reads and omits the rest, which
is how you write a legible fixture, not drift. The line belongs at **values that are actually
wrong** — `null` in a non-nullable field, a wrong type, an enum value core cannot produce. Absent
and unknown keys are warnings, ratcheted per spec in `web/tests/contract-baseline.json` so the
count can fall but never rise.

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
2. ~~**Endpoint security surface and authorization matrix** (EZ-1769)~~ — **done 2026-08-15.**
   Anonymous gets 401 on all 124 endpoints bar the five public ones; a role outside `@Secured` gets
   exactly 403 across ~250 combinations.
3. ~~**Contract checks** (EZ-1770)~~ — **done 2026-08-15.** `doc/core/api-shapes.json` is
   generated and pinned; the browser harness checks every fixture against it. The TypeScript half
   is EZ-1772.
4. ~~**Service tests for access control**~~ — **done 2026-08-16**, with grading behaviour
   alongside it: the threshold boundary, the four counts, and per-student visibility exceptions.
5. ~~**Web migration to `@playwright/test` + `vitest`**~~ — **done 2026-08-16.** All 27 specs and
   all 5 unit files moved with their labels intact; `web/dev-harness/` is gone. What it added on
   top of the framework: a per-spec check-count ratchet, an expiring quarantine file, and a unit
   test over the suite's own bookkeeping. It also found three specs whose helpers had been quietly
   broken by the move — the ratchet reported 0 checks where 30 were expected, which is the exact
   failure it exists for.
   **Next: the grading flow, `ParticipantsPage`, `GradeTablePage` and route guards.**
6. **tsl and aae** — two components with no tests at all. Depends on nothing; parallelisable.
7. **Migration tests against an anonymised copy** — once dev exists.
8. **axe in the browser harness** — cheap, and there is already evidence it would find things.
9. **Performance measurement of the two known suspects** — a fixture, not a framework.
