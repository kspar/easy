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

**As of 2026-08-21, at master `0c4028e6`:**

| | Size | Tests |
| --- | --- | --- |
| **core** | 118 `@RestController` classes (124 endpoints) | 36 test files, **194 tests, all running** |
| **web** | 122 `.ts`/`.tsx` files | 14 unit files (**191 tests**), 37 browser specs (36 in CI, **778 checks**) |
| **tsl** | the TSL compiler | 3 test files, **81 tests** |
| **tsl-common** | the shared TSL model | 1 test file, **30 tests** |
| **aae** | the executor | 6 test files, **119 tests** (89 without tiivad installed, when 30 skip) |
| **bin** | `pins.py`, the grading-library pins parser | 1 test file, **74 tests** |
| **executor_images** | the grading-image reconciler | 1 test file, **19 tests** |

**One command runs all of this:** `bin/testcounts` prints the sizes instantly, and
`bin/testcounts --run` also runs every suite and reports what each one says. It exists because
printing the commands below — which this document did, deliberately, for exactly this reason —
**still went stale three times in two days**: printing a command still asks a human to run six
of them, sum a seventh, and do it at the commit the reader is at. It is not a CI gate, because
two of these figures are environment-dependent (see aae below) and a gate that fires on
non-defects gets muted. The commands are kept here so the script is checkable rather than
trusted.

```sh
bin/testcounts --run

# or, one at a time:
grep -rl '@RestController' core/src/main/kotlin | wc -l          # 118
find core/src/test/kotlin -name '*.kt' | wc -l                   # 36
find web/src -name '*.ts' -o -name '*.tsx' | wc -l               # 122
ls web/tests/browser/*.spec.mjs | wc -l                          # 37
ls web/tests/unit/*.test.mjs | wc -l                             # 14

./gradlew :core:test                                             # 194
cd web && npm run test:unit                                      # 191
cd web && npx playwright test                                     # 778 checks / 36 specs
python -m pytest aae/tests -q                                     # 119, or 89 without tiivad
python -m pytest bin/tests -q                                     # 74
python -m pytest ansible/roles/executor_images/tests -q           # 19
```

**Always write down the commit these were measured at.** Two of these suites did not exist a day
before this line was written, and a reader who measures a different commit and finds smaller numbers
has no way to tell whether the table is stale or wrong — that exact confusion happened once, and was
resolved only by noticing the two readings were of different trees. Both were correct.

**The last two suites are Python and live outside `aae/`.** They test tooling rather than the
application — the parser that decides which grading library version an environment runs, and the
reconciler that puts an image live on a host — and most of what they assert is refusal: a pull request
that may not merge itself, an image that must not become live. CI runs them in the `Executor (Python)`
job, which is why that job's name now understates it.

**aae has two numbers.** 30 of its tests exercise the real `tiivad` package and skip when it is
absent, so a bare `pytest aae/tests` reports 89 and a full run reports 119. CI installs tiivad at
the pinned version first, so CI always sees the larger figure; a laptop usually sees the smaller one
and is not broken.

Both halves of that split move as tests are added, and this sentence has already been wrong once —
it said 22/91/113, measured a few commits earlier. `bin/testcounts --run` derives the split from
which tests carry `@needs_tiivad` and prints it, so the numbers here are a snapshot and the script is
the answer.

The endpoint figure has no command, on purpose: it comes from `EndpointInventory`, which reads
Spring's resolved handler patterns. Grepping `@(Get|Post|…)Mapping` gives 123, which is a different
number — a mapping declaring two paths is one annotation and two endpoints. When these disagree,
the inventory is right and the grep is a coincidence.

The endpoint figure is the one number with no command, on purpose: it comes from
`EndpointInventory`, which reads Spring's resolved handler patterns. Grepping
`@(Get|Post|…)Mapping` gives 123, which is a different number — a mapping declaring two paths is one
annotation and two endpoints. When these disagree, the inventory is right and the grep is a
coincidence.

194 is what the runner reports, not a count of `@Test`. The two differ now that some tests are
parameterised — `StorageServiceContractTest` is 9 annotations and 15 runs, over two backends — and
counting annotations would have understated the suite while *looking* like a measurement. Read it
off the last line of `./gradlew :core:test` instead. It takes about 28 seconds, measured over three
consecutive runs.

Most of that was once the autograde tests. `AutoGradeScheduler.grade()` runs on a fixed delay, so
that interval is the floor on how long each of them waits; the test config sets it to 100ms against
a deployed environment's 3000, which took the suite from 55 seconds to 28. Not a behaviour change in
disguise — the tests poll the database for an outcome rather than sleeping for a fixed period, so a
slower tick would make them slow rather than make them pass.

The web numbers look better than they are, because the check counts are lopsided:

```
unit      191 tests across 14 files — the markdown commands (11,903 property checks), merge
          resolution, the grade table, api/client, i18n parity, localStorage, tslModel, the
          api/types.ts contract, the a11y gate's own arithmetic, the flake reporter's, and the
          suite's bookkeeping
browser   778 checks across the 36 specs CI runs (a 37th needs a real core)
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

**All 194 core tests now run, in CI and on a laptop, with no setup** — 32 when this started, of which 25 ran. EZ-1715 landed on 2026-08-15,
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
| `ParticipantsPage` | 1823 | 54 — Moodle sync, the roster, and groups across five specs |
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
  null. The other three were the dangerous direction — the database permitting a null that Kotlin
  promised was impossible — and were closed by EZ-1771 in changesets `210826-1` and `210826-2`.
  `knownNullabilityDrift` is now **empty**, and the entries had to go in the same commit as the
  changesets because an entry that no longer disagrees fails the test.

  Answering it needed a measurement rather than a judgement: `article_version.title` had no nulls at
  all, while the two `*_course_access.created_at` columns had 32 and 40, so they needed a backfill
  and a changeset that only added the constraint would have stopped core from starting. And because
  `:core:test` runs Liquibase against an empty database, the migration was verified by a
  `BEGIN … ROLLBACK` dry run against dev's anonymised copy — see `doc/testing-log.md`, "a migration
  that only runs against an empty database has not been tested".

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

**Done 2026-08-15 (EZ-1770), completed 2026-08-19 (EZ-1772).** `doc/core/api-shapes.json` is
generated from core's Kotlin by reflection and committed: 123 endpoints, 194 types, 184 nullable
fields. A plain `@Test` regenerates it and fails if the committed file differs.

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

#### The TypeScript half (EZ-1772, 2026-08-19)

Fixtures only cover the fields some test happens to read. `web/src/api/types.ts` covers everything
*the app* reads, and for four days nothing compared it to anything.
`web/tests/unit/api-types-contract.test.mjs` now does, off the committed shape file — no backend, no
Docker, ~400 ms. 45 of the 67 interfaces under `web/src/api/` carry a line naming their endpoint and
the path from the response root:

```ts
/** @endpoint GET /v2/teacher/courses -> courses[] */
/** @requestBody PUT /v2/exercises/{exerciseId} */
```

Because the endpoint string has to exist in the shape file, this is also the only check in the repo
that notices a route being renamed out from under the client.

**Direction is the whole substance of it.** A naive version compares two property lists and
complains about every difference, which is wrong both ways: a response field core sends that the app
ignores is normal, while one the app declares and core does not send is a live bug — every read is
`undefined` and TypeScript insists otherwise. For a request body those swap. So each of the six
rules states which direction it applies to, and severity follows from that rather than from how
different the two sides look.

Result: **one** finding, EZ-1777 — core stores inline-comment `type` as unvalidated free text and
the client declares it as `'comment' | 'suggestion'`. Inert today, because nothing reads the field
back, so it is a trap rather than a break. Waived in `web/tests/api-types-baseline.json`, which
demands a note and an issue per waiver and fails when a waiver stops firing.

**What it does not reach**: only `interface` declarations. Request bodies are mostly declared inline
in a `mutationFn` signature, so of ~40 mutating endpoints exactly one carries a `@requestBody` line
and the two request-direction rules guard that one. An inline type literal is not just unchecked, it
is also absent from the unannotated list — invisible to the gate whose job is making unchecked types
visible. **EZ-1779.**

**A finding of one is exactly when to distrust the detector**, so this one is built the other way
round: half its 38 tests feed it a deliberately broken client and fail if the rule stays quiet, and
`bin/mutate.sh` carries four mutations of the real `types.ts` — the first web mutations in that file.
That discipline paid immediately. The first version had a rule comparing the *Kotlin type name* a
reference resolved to; it opened with seven findings, and four were its own artefacts, because core
declares a `RespAsset` and a `GroupResp` per controller and five structurally identical wire types
therefore have five names. Replacing it with a recursive structural walk removed the four and is
strictly stronger — a genuinely swapped reference now fails on the property names that do not line
up. See `doc/testing-log.md`.

### Migration tests — have none, and we now have a reason to want them

Changeset `020826-1` (making the template non-nullable) rewrites production rows. It was tested by
hand, against a copy of the dev database, once. That is better than nothing and worse than a test.

The mechanism is cheap: restore a dump into a scratch database, run Liquibase, assert on the
resulting schema and data. It becomes considerably more valuable once dev exists and there is
an anonymised production copy to run it against (`doc/core/anonymise-db/`), because the
interesting migration failures are all about real data rather than clean schemas.

**Still true, and EZ-1771 is the second demonstration.** `:core:test` runs Liquibase against a fresh
empty database, so `210826-2`'s backfill matched nothing and its three `addNotNullConstraint`s passed
vacuously — green, and worth nothing, since the only interesting question about that changeset is what
it does when a null is actually present. What closed the gap was a one-minute `BEGIN … ROLLBACK` dry
run against dev: 32 and 40 nulls before, exactly 32 and 40 rows updated, 0 after, all three
constraints applied, rolled back with dev unchanged. A missed row means the constraint fails, and
because migrations run inside the Spring context, **core does not start** — on whichever environment
has that row, which by definition is the one nobody tested against.

So the shape of the eventual test is now known from having done it twice by hand, which is the
argument for building it. Until then: for any data-rewriting changeset, run the dry run **before**
writing the changeset, because it is also how you discover whether your backfill source is populated
for the rows that need it.

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
counts are at or below baseline **and** the a11y baseline produced no new fingerprints **and** no
`api-types-baseline.json` waiver has gone stale.

All six are artefacts the suite produces anyway. The point of naming them is that "the build is
green" and "this is safe to deploy" should be the same sentence, checkable by a script.

The three baseline files — `contract-baseline.json`, `a11y-baseline.json`,
`api-types-baseline.json` — all obey the same two rules, and both matter equally. An entry without a
note and an issue is **rejected at load**, so "we looked and decided this is fine" cannot be spelled
the same way as "nobody looked". And an entry that no longer fires **fails**, so the files can only
shrink: without that, a baseline accumulates permissions for problems that were fixed long ago and
silently re-permits them when they come back.

## Suggested order

Revised 2026-08-15. **The previous ordering put browser coverage second, above backend access
control — that was wrong once auto-deploy became the goal**, and it is drift rather than error: the
list predates that goal. A browser test whose backend is Playwright route interception passes
against *any* backend, including a broken one. It cannot gate a backend deploy, by construction.

1. ~~**EZ-1715** — postgres in CI~~ — **done 2026-08-15.**
2. ~~**Endpoint security surface and authorization matrix** (EZ-1769)~~ — **done 2026-08-15.**
   Anonymous gets 401 on all 124 endpoints bar the five public ones; a role outside `@Secured` gets
   exactly 403 across ~250 combinations.
3. ~~**Contract checks** (EZ-1770, EZ-1772)~~ — **done 2026-08-15, completed 2026-08-19.**
   `doc/core/api-shapes.json` is generated and pinned; the browser harness checks every fixture
   against it, and a unit test checks `web/src/api/types.ts` against it too. Both halves closed.
4. ~~**Service tests for access control**~~ — **done 2026-08-16**, with grading behaviour
   alongside it: the threshold boundary, the four counts, and per-student visibility exceptions.
5. ~~**Web migration to `@playwright/test` + `vitest`**~~ — **done 2026-08-16.** All 27 specs and
   all 5 unit files moved with their labels intact; `web/dev-harness/` is gone. What it added on
   top of the framework: a per-spec check-count ratchet, an expiring quarantine file, and a unit
   test over the suite's own bookkeeping. It also found three specs whose helpers had been quietly
   broken by the move — the ratchet reported 0 checks where 30 were expected, which is the exact
   failure it exists for.
6. ~~**Web coverage breadth**~~ — **done 2026-08-16.** Eight browser slices and six unit modules:
   the grading flow, route guards, both halves of `ParticipantsPage`, the grade table, the courses
   page and join-by-link; plus grade-table logic, `api/client`, i18n parity, `localStorage` and
   `tslModel`. Every page that had no coverage now has some, and five production defects were
   fixed on the way — three of them found by *extracting* logic in order to test it rather than by
   a test failing. See `doc/testing-log.md`.
7. ~~**Port the bash checks, the public surface, executor integration**~~ — **done 2026-08-16.**
   `articles-check.sh` and `files-check.sh` are gone, replaced by `ArticleApiTest`, `FileApiTest`
   and a `StorageServiceContractTest` that runs the same assertions against both storage backends
   (MinIO for the S3 half). The five-pattern `permitAll` surface is exercised behaviourally rather
   than only structurally. **Submission → grading → feedback now runs in CI** against a
   `com.sun.net.httpserver` executor — including the legs that were never tested anywhere: the
   retry, the timeout, an unparseable response, a drained executor, and the
   stranded-IN_PROGRESS recovery at startup. And `StoredFileSweep`, whose inputs were guarded and
   whose behaviour was not.
8. ~~**tsl and aae**~~ — **done 2026-08-16.** 81 tests in `:tsl`, 30 in `:tsl-common` and 59 in
   `aae`, all three of which had none. Found EZ-1774 — every check dictionary emitted with unusable
   keys since 2026-08-07, on master and dev — plus two ways `PyStr` produced a literal Python cannot
   close, one of which was silently truncating teachers' expected values. Golden files under
   `tsl/src/test/resources/golden/` are the load-bearing part: they would have caught EZ-1774 in the
   authoring diff, and the syntax test would **not** have, because the defect is valid Python. `aae`
   gets a new CI job and needs no Docker — the suite fakes it, because what is worth testing is the
   directory `aae` lays out and the answers it gives. What is still missing is running a generated
   script against a real tiivad: **EZ-1775**.
9. ~~**Measurement, a11y, flake**~~ — **done 2026-08-16.** Kover with class-level targets;
   `bin/mutate.sh`, 25 deliberate defects and 25 caught; axe inside the browser specs, plus three
   rules axe does not have (a `<main>` landmark, a visible keyboard focus ring, links sharing a name
   but not a destination); and a nightly workflow running each spec five times to find the
   intermittence `retries: 0` refuses to paper over, alongside the mutation suite.

   It found **eight** real violations across two rounds — one critical — and all are fixed, so
   `web/tests/a11y-baseline.json` is empty. The file can only shrink: a new fingerprint fails, and so
   does an entry that no longer fires.

   Three things worth carrying out of it.

   **Coverage and mutation answer different questions**, measured on the same code: disabling all of
   `StoredFileSweepTest` takes the sweep from 94% to 7% and fails the coverage gate, while disabling
   two of its tests leaves it at 92% and passes. Coverage catches an area falling out of the suite;
   mutation catches a test that cannot fail.

   **Name the code, not the package it lives in.** The first Kover targets used packages and measured
   the wrong thing in three cases of four.

   **Feed every detector a positive case before believing a negative one.** Seven tools this
   programme built or trusted could not detect what they watched for. See `doc/testing-log.md`.

10. **Migration tests against an anonymised copy** — once dev exists.
11. **Performance measurement of the two known suspects** — a fixture, not a framework.
