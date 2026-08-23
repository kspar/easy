# A UI/UX audit programme for `web`

## Context

`web/` is eight weeks old. The old Kotlin-JS `wui` was deleted 2026-07-31, the React app that replaced
it was built page by page against a migration checklist (EZ-1689…EZ-1707), then hardened by a
nine-phase test programme (EZ-1766), and is now being read end to end for defects
(`doc/review-plan.md`, 36 units, Phase 1 and most of Phase 2 done).

None of that asked whether the application is any good to use, or any good to look at. The test
programme asked "can a green build be a deploy decision"; the review programme asks "is this code
right". Both are satisfied by an app that is correct and horrible. The migration checklist asked "does
the page exist", and it was answered per page by whoever built that page — which is how an app ends up
with nineteen dialogs of which eleven have no validation at all, one skeleton loader against
thirty-one bare spinners, a canonical empty-state component used in two places out of a possible
thirty, and a landing page carrying its own private design system while the app behind it runs on
stock MUI Roboto.

So: a first-principles audit of the whole front end as a *user* meets it, and as a *designer* would
judge it. Every view, every role, both themes, phone to large monitor, with the TSL builder as its own
track because it is the deepest thing in the app. Three questions, each a real finding source:

- **How should a student, a teacher and an admin work, and how does the application actually let them
  work?** The gap is a journey finding.
- **Is what is on screen correct, consistent and legible** in both themes at every size? The gap is a
  surface finding.
- **Is it well designed** — does the palette, type, density, motion and choice of component serve this
  product and these users, or is it the default anyone would have got? The gap is a design finding, and
  it counts the same as the other two.

Four things found while planning, as evidence the questions have answers:

- `useMediaQuery` appears in **two files in the entire app** (`AppLayout`, `CourseExercisePage`), four
  call sites, all `breakpoints.down('md')`, inside a single `maxWidth="lg"` container. There is no
  `breakpoints` block in the theme and **no `<Grid>` anywhere**. Both ends of the range are unhandled
  by construction, and EZ-1527 ("Optimise for large screens", Usability) has been open since 2022.
- **32 of 34 browser specs run light-only**, the harness default viewport is 1100×800, and **no
  large-monitor viewport is ever exercised**. Dark mode's entire automated coverage is one screenshot.
- The axe detector already in the harness is wired into **two of forty-one specs**,
  `a11y-baseline.json` is empty, and `color-contrast` is deliberately excluded from its gate —
  "reported, never fatal". The instrument exists, has never been pointed at the app, and its most
  design-relevant output is discarded by design.
- Choosing TSL on a new exercise seeds a grading script and an empty asset list but **no `tsl.json`**,
  so `useTslSpec` immediately compiles the empty string and the first thing a teacher sees on the
  deepest screen in the app is a raw kotlinx decode error with Save disabled.

The outcome is a findings log, not a diff. Fixes come after, each on its own branch.

---

## Decisions

The first four were asked and answered; the rest are this plan's defaults, recorded so the programme
does not re-litigate them mid-flight.

| Decision | Choice |
|---|---|
| Where the programme lives | `doc/web/ux-audit-plan.md` + `doc/web/ux-audit-log.md`, **committed** |
| The consolidated guide | **Written**, as the final unit, from the log's evidence |
| The Keycloak login screen | **In scope**, with `kspar/easy-kc-theme` cloned so findings can name files |
| Real compiler for the T track | Relay to the core on **:8080** — but **ask first**, at the named checkpoint |
| Where findings go | The log only. **Nothing is filed to YouTrack during the programme** |
| Fix policy | **Report only.** No code changes in an audit session, not even an `sx` prop |
| Rigour | **Two-stage: find, then adversarially verify** every candidate |
| How a finding is settled | **By looking at a rendered page**, not by reading `sx` props |
| Design quality | **A first-class finding class** with its own track, bar and severity mapping |

**Why committed, when the review programme's pair is gitignored.** That gitignore is a security
decision with a stated reason: a list of unfixed access-control and XSS findings is a roadmap, and
`kspar/easy` is public. Nothing here is that. "The confirm dialog lives in the wrong directory" and
"three different greens claim to be the brand colour" are not exploitable, and they are the sort of
thing that reaches EZ as `Type: Usability` anyway — EZ-1706, EZ-1734 and EZ-1527 already have.
Committing also avoids the copy-out-of-the-worktree ritual `doc/review-log.md` has to describe in its
own header.

**The one carve-out:** anything with a security consequence — a permission visible in the UI that
should not be, a token in a screenshot, the code-injection shape the TSL mapping pass turned up — goes
to `doc/review-log.md` instead, and this log gets a pointer with no detail.

**Scope boundaries.** In: everything under `web/src`, plus `easy-kc-theme`. Out: the string audit
(EZ-1785) and the icon audit (EZ-1759), which are separate filed issues this programme feeds; core and
aae, which have their own programme; and pages that merely *embed* Lahendus, whose own styling is not
ours.

---

## Ground rules

**Report only.** No code changes in an audit session. A session that also edits cannot be abandoned
cleanly when context runs out, and abandoning cheaply is the whole point.

### The finding bar

A finding must make an argument, not report a feeling. Every finding, of every class, names:

1. **who it costs and what they were doing** — "a teacher marking a late submission", "a student
   meeting the grader for the first time", or for a design finding, what the product loses;
2. **what actually happens** — the action that is missing, the step that misleads, the element that is
   unreadable, the layout that breaks, the choice that reads as an accident;
3. **evidence** — a screenshot, a click count, a computed style, a console error, an axe rule id, the
   viewport width at which it starts, the count of sites affected;
4. **and for anything proposing a change of direction, what better looks like** — concretely enough to
   act on. "The type scale is undifferentiated" is half a finding; "h5 at 1.5rem/400 is the only
   heading level in use, so a page title, a section title and a dialog title are indistinguishable —
   here is a three-level scale that separates them" is a finding.

What is *not* a finding is the bare preference: a colour swapped for another with no reason given, a
component changed because a different one is more fashionable, a rewrite proposed without saying what
the current one costs. The test is not "is this aesthetic" — aesthetics are in scope. The test is
**does it carry an argument and an alternative.**

**"Inconsistent" needs two named sites and a reason one of them is wrong.** Symmetric repetition is
this project's stated convention; divergence between copies is the defect detector. So the finding is
never "there are two patterns" — it is "these two places do the same thing differently, the majority
does it *this* way, and the minority costs the user *that*".

**File at the level of the decision, not the sighting.** This is the mechanism that keeps the log
readable without demoting anything. One palette decision produces **one** finding with its worst
instances as evidence — not forty. `a11y.mjs`'s own docblock says exactly this about contrast: "one
palette decision becomes hundreds of findings, and it is a design call rather than a deploy blocker".
It is right about the arithmetic and this programme disagrees only about the conclusion: a design call
is precisely what this audit is here to make. So the finding is the call, and the hundreds are its
evidence.

**A rule that lights up everywhere is describing its own bug.** Inherited verbatim from the review
programme, where every check that fired broadly on its first run was reviewer error. The trap here is
the mechanical sweep: there are **245 colour literals in 17 files** and **953 `sx` props**, and most
are harmless. More than ~8 findings of one shape in a unit means stop and suspect the auditor — then
collapse to one finding at the level of the decision.

**A dead token is not a broken token.** The planning pass nearly filed its first finding on
`success.light`/`warning.light`/`error.light`/`info.light` being near-white in dark mode as well as
light. They are — and **all four have zero uses**, as does the entire `secondary` triplet. The finding
is therefore not "unreadable chips" but "six palette entries are dead, and mode-blind, so the next
person to reach for one inherits a trap". Count the uses before writing the consequence. This is the
programme's own cautionary tale and belongs in every S- and V-track session.

**Check the known register before writing anything.** Twenty-plus open EZ issues already answer the
obvious questions. Re-finding EZ-1758 is not a finding. Where a unit's observation matches a filed
issue, the log records the id and — where the audit learned something the issue does not say — a
one-line addendum for it.

**Read the rationale before flagging.** This codebase comments *why*, at length. `theme.ts`'s focus
ring carries a specificity measurement and the reason MUI's own rule beat it; `CodeEditor.tsx` explains
why it is not fully controlled; `EmbedExercisePage` forces light theme on purpose; `useEmbedTheme.ts` is
78 lines mostly comment. A finding the comment two lines up already addresses is not a finding — though
a documented choice can still be the *wrong* choice, and saying so with an argument is allowed.

**Judge layout in Estonian.** The app defaults to Estonian; the harness comment says every spec runs in
English because "most selectors assume 'en'". So the default language is the untested one, and **137 of
858 strings are ≥30% longer in Estonian** (measured at `0cf2d952`; worst is
`tsl.containsName.KEYWORD_WITH_PRECEDING_ARG` at 2.42×, in the densest UI in the app). Any overflow,
truncation or wrap finding is checked in `et` at the narrowest viewport before it is believed, and its
absence in `en` proves nothing.

**Numbers carry their commit**, per the memory rule. Every count of files, routes, strings or
violations is stamped with the sha it was measured at.

**No user data in the log.** Screenshots come from fixtures, so this is mostly automatic — but no real
names, emails or exercise titles reach the log or a committed screenshot.

### Design findings

Design quality is a finding class, `design`, alongside `journey`, `theme`, `responsive`,
`consistency`, `a11y`, `docs` and `copy`. It covers what the other classes cannot say:

- **the visual direction** — whether the app has an identity or a default, and whether that identity
  suits a tool students sit in under exam conditions and teachers sit in all day;
- **colour decisions** — not only "is it legible" but "is it the right colour": does it carry meaning,
  does it build hierarchy, is one green three greens, is the accent doing a job;
- **typography, density and hierarchy** — whether the type scale distinguishes what the content
  distinguishes;
- **motion** — whether it earns its place, especially the 493-line animation shown after every single
  student submission;
- **the choice of component and interaction** — a control that works but is the wrong pattern for the
  task is a finding. A 1,882-line page with four tabs and 33 `useState` may be the wrong shape for the
  job even with every button functioning, and saying so is this programme's business.

Three disciplines keep the class honest, and none of them is a demotion:

1. **Argument and alternative**, per the finding bar. A design finding says what the current choice
   costs and what to do instead.
2. **Filed at the level of the decision.** One direction, one finding, with instances as evidence.
3. **Ordered by leverage.** The app is eight weeks old and was built fast by one person; a critique
   whose conclusion is "redesign everything" is unactionable and therefore worthless. Every design
   finding carries an estimate of reach — how many surfaces the change touches — so the closing summary
   can order them by what buys the most for the least. That ordering is the deliverable, not a
   softening of the findings.

**V-track sessions load the `frontend-design` skill first.** Its calibration is directly useful here:
it names the looks that are defaults rather than choices, which is the same question this audit asks of
MUI's stock Roboto-on-grey. Its "spend your boldness in one place" is the lens for `LandingPage`'s
three display faces against the app's none. Its writing guidance ("errors don't apologize, and they
are never vague about what happened") is the argument C1 needs about `general.somethingWentWrong`
appearing in fifteen files.

---

## The instrument

The harness that makes this auditable already exists, is committed, and its own documentation already
prescribes this method. `doc/web/browser-testing.md` says, in a section called "Screenshots are the
point": *"For anything visual, take one and look at it… Capture light, dark, and mobile at minimum."*
This programme is that instruction applied systematically.

`web/vite.stub.config.ts` aliases `keycloak-js` to a stub, so pages run in a real Chromium with no IdP;
Playwright route interception fakes the backend. `web/tests/support/harness.mjs`'s `launch()` already
takes exactly the axes this audit needs:

```js
launch({ role, language, theme, viewport, colorScheme, reducedMotion, shotPrefix })
```

`role` seeds `localStorage.stubRole`, `theme` seeds `themeMode`, `shot(name)` writes a
`deviceScaleFactor: 2` PNG. So **any view, as any role, in either theme, at any viewport, in either
language, can be rendered and photographed** — and the auditor then *reads the PNG*. That is what makes
this evidence-based rather than an opinion pass, for design findings as much as for layout ones, and it
is the same move review unit B1 made when it ran the markdown pipeline standalone instead of reasoning
about it. `reducedMotion` and the doc's animation-sampling recipe (frames at 250/700/1100/1450 ms) are
how the motion findings get evidence.

**Audit drivers must not be added to `web/tests/browser/`.** That directory is ratcheted three ways:
`expected-checks.json` counts checks per spec, `spec-inventory.mjs` fails on a spec nobody counts, and
`suite-integrity.test.mjs` enforces the bookkeeping. A new `.spec.mjs` there breaks the build, which is
a code change, which this programme does not make. Instead:

- drivers live in **`$CLAUDE_JOB_DIR/tmp/ux-audit/`**, outside the repo;
- they `import` `harness.mjs` and `a11y.mjs` from `web/tests/support/` — reused, never copied;
- they run as plain node scripts against a stub server on a **non-default port** (`HARNESS_PORT=5299`),
  because `reuseExistingServer: false` means a concurrent `playwright test` and an audit driver on 5199
  tear each other down in three confusing disguises the config already documents;
- `makeLaunch(browser, testInfo, register)` is exported, so a driver constructs it with a stub
  `testInfo` (only `attach` is used) and a no-op `register`.

**Fixtures.** The specs' own payloads under `web/tests/support/*-fixtures.mjs` are the starting point,
and `doc/core/api-shapes.json` is the authority on shape — five fixtures written from memory during the
test programme were all wrong. Where an audit needs data the specs lack (an empty list, a 200-row
roster, a 60-character course name, a student with no submissions, forty course exercises for forty
grade-table columns) the driver extends a fixture rather than inventing a payload.

**The `:8080` checkpoint.** T3 and T4 need a real compiler, because kotlinx runs with
`ignoreUnknownKeys = false` and a stub cannot answer "does this decode".
`library-exercise-tsl-live.spec.mjs` already relays `/v2/tsl/compile` to the core on :8080. **The
programme stops and asks kspar before the first relay**, per the answer given — no test core is started
on 8080, and the ask is explicit rather than assumed. Until then those two units record their
divergences as `UNCERTAIN`.

**Prove the instrument before trusting it.** Step 0 renders one known-good page and one deliberately
broken variant and confirms the auditor can tell them apart from the PNG alone. A detector that reports
nothing may be unable to report anything.

---

## Where "should work" comes from

The hard half of the question. There is no design spec for this app, so the norm has to be assembled —
and *named*, so a finding cites rather than asserts. Six sources, in descending authority for
behaviour; note the last is the one that carries the design class, and it does not rank below the
others so much as answer a different question.

1. **The `ui-conventions` table.** Eight conventions, each naming a file that already implements it
   correctly: outlined icons (measured at 94.6% compliance), `enGB` dates, `spaLinkProps` for anything
   navigable, `TableSortLabel` for table sorting, `Chip`+Menu filters labelled by category,
   `useSavedGroup` for group persistence, the single-field-dialog focus pattern. Six are checkable
   mechanically across every surface, and review unit E5 already found one broken in four of twelve
   dialogs. **It lives only in the session memory directory**, which is the D2 unit's whole point.
2. **The app's own majority.** Where twenty places do a thing one way and two do it another, the twenty
   are the convention. This is the project's stated style philosophy — independence and slight
   repetition, so divergence is the signal — and the only behavioural norm that cannot be accused of
   imported taste.
3. **The old UI and the migration checklist.** `doc/wui/` is explicitly headed `ARCHIVED` and says
   "nothing here describes the current UI", so it is provenance only — but `archive/ezspa/` is the
   source of a shipped application, and EZ-1689…EZ-1707 is the list of what was meant to carry across,
   several still open. A capability that existed in `wui`, is not in React, and is on nobody's list is
   a *gap in the gap list* — the highest-value thing this axis produces.
4. **The role's job.** For each of student, teacher and admin, the tasks the application exists to
   support, written down *before* opening the app. Derived from `doc/testing.md`'s priority ranking
   (grading flow, then roster, then grade table, then invites) and from `doc/core/api-shapes.json` —
   **an endpoint no UI reaches is a candidate missing action**, and EZ-1758 (draft-saving endpoints
   nothing calls) is proof the sweep works.
5. **Platform and WCAG norms**, for semantic correctness: `error` means error, destructive actions
   confirm, a control that navigates is a link, every control has an accessible name, AA contrast.
6. **Design judgement, argued.** For the design class: does this choice suit *this* product — a tool
   used by students under exam pressure and by teachers all day, in Estonian, on university hardware,
   in two themes? The `frontend-design` skill is the method; the app's own best work is the internal
   benchmark (the `:focus-visible` rule, `useEmbedTheme`, the grade table's sticky column and
   `GradeTablePage`'s `TableContainer` are all examples of care, and they are the standard the rest is
   held to). A design finding cites this source and carries its argument in full, because unlike the
   other five it cannot point at a file and say "that one is right".

Where sources conflict the log says which it is citing.

---

## Method

Two passes per unit, as in the review programme.

**Stage 1 — find.** Walk the scope with a driver: render it, screenshot it, and where it is a journey
*perform the task* click by click and count the clicks. Read the components alongside; the screenshot
is the evidence. Produce candidates carrying the finding bar's parts.

**Stage 2 — verify.** An independent pass that tries to **refute** each candidate: re-render it cold,
count the uses of whatever it blames, check the known register, check the comment, check the
majority-pattern claim survives a proper count, and check the other theme, the other language and the
other viewport — because half the tempting candidates will be true in one combination only, which
changes the finding rather than removing it. Verdict: `CONFIRMED`, `REFUTED`, `UNCERTAIN`.

For a design finding, refutation asks two extra questions: **is the alternative actually better** (state
what it costs as well as what it buys), and **is the current choice load-bearing for something else**
— MUI's defaults are a real constraint, and "replace this component" that implies leaving the library
is a different and much larger finding, which the log should say plainly rather than imply.

`CONFIRMED` and `UNCERTAIN` go in the log; `REFUTED` gets one line in a Refuted appendix so no later
session re-finds it. That appendix is what makes the programme converge.

**Every unit reports its a11y results.** `a11y.mjs` gives `scan` (axe on wcag2a/aa + wcag21a/aa),
`checkMainLandmark`, `checkDuplicateLinkNames`, `checkFocusVisible` and — the one this audit most wants
— `contrastFindings`, which CI runs and deliberately never fails on. Those are free findings on
surfaces no spec visits, in both themes, arriving with a rule id, which is the best evidence a finding
can have.

### The severity ladder

| | |
|---|---|
| **critical** | A user cannot complete a task the application exists for, and has no workaround. Data loss counts: unsaved work discarded without warning is critical even if the pixels are perfect |
| **high** | The task completes but the user is misled, or it takes a route nobody would find; or a surface is unusable at a viewport or theme people actually use |
| **medium** | Real friction with a discoverable workaround — extra steps, a missing confirmation, an error the user cannot act on, an inconsistency that costs relearning |
| **low** | Local, with no task cost and little reach |

**How design findings are rated.** On the same four levels, against product cost rather than task
completion — and **never downgraded merely for having no task cost**, which would reintroduce the
dismissal this ladder is written to avoid:

- **high** — the design actively misleads or obstructs comprehension: hierarchy that hides the
  important thing, colour that implies a meaning it does not have, motion that delays every submission,
  a component whose pattern fights the task it serves.
- **medium** — the product reads as unfinished or as two different products, costing trust: two visual
  identities, three brand greens, a heading scale that does not distinguish what the content
  distinguishes, an interaction that works but that nobody would choose twice.
- **low** — a local choice, defensible either way, with small reach.

Reach is stated separately from severity, because a medium finding touching every page usually outranks
a high one touching a single dialog, and the closing summary orders on both.

---

## Artefacts

**Step 0 of execution**, before any auditing:

1. Write `doc/web/ux-audit-plan.md` — this document.
2. Create `doc/web/ux-audit-log.md`: status table, known register, empty findings section, empty
   Refuted appendix, empty leads-for-EZ-1785/EZ-1759 section.
3. Commit both on a branch, per the git workflow. No `.gitignore` change needed;
   `web/tests/screenshots/` is already ignored.
4. Record the starting sha. Run `cd web && npm run lint && npm run test:unit && npm run test:browser`
   once, judged on **exit codes**, so a pre-existing failure is never reported as a finding.
5. Build the driver harness in `$CLAUDE_JOB_DIR/tmp/ux-audit/` and prove it, per "Prove the instrument".
6. Clone `kspar/easy-kc-theme` into `$CLAUDE_JOB_DIR/tmp/` — disposable and outside the repo, so
   nothing is added to the working tree. (If it should live at `~/IdeaProjects/easy-kc-theme`
   permanently, say so and it goes there instead.)
7. Populate the **known register**: every open EZ issue touching web UI/UX, one line each, grouped by
   surface. Queries: `project: EZ #Unresolved Subsystem: web` and `Type: Usability, Cosmetics`. The
   seed list is in the unit tables below.

### Log format

```
### X-014 A breadcrumb click discards a half-built TSL test set with no warning
- Unit: T5
- Surface: /library/exercise/:id (teacher, editing), light, 1440×900, et
- Norm: platform — destructive navigation must be guarded (source 5); and the app's own
        beforeunload guard on the same state (source 2)
- Class: journey     (journey | design | theme | responsive | consistency | a11y | docs | copy)
- Severity: critical
- Reach: one surface, but the app's deepest editing session
- Verdict: CONFIRMED
- What happens: <who, doing what, and what they get>
- Instead: <for design and pattern findings: what better looks like, and what it costs>
- Evidence: <screenshot path, click count, computed style, axe rule id — and the sha>
- Register: not previously filed | EZ-1234 (addendum: …)
```

### Session protocol

- **Start**: read the status table, take the first `todo` unit, mark it `in progress` with today's date
  and the current sha. V-track sessions load the `frontend-design` skill before looking at anything.
- **Work**: Stage 1, then Stage 2. One unit per session unless two are small. Never leave a unit
  half-audited — finish it or put it back to `todo`.
- **End**: append findings, mark the unit `done <sha>`, record leads belonging to other units under
  their rows. Copy screenshots a finding depends on out of the gitignored `web/tests/screenshots/` into
  the job dir and record the paths; a finding whose evidence has evaporated is downgraded to
  `UNCERTAIN` by the next session.

### Reporting back

**Short.** Counts, and the findings that actually matter: how many candidates, how many kept, how many
refuted, how many were register hits, and the one or two worth acting on now, with their ids. The log is
the report. If a unit produced nothing worth acting on, saying exactly that is the whole report.

---

## The units

36 units in six tracks, ordered so the most-travelled surfaces come first. The student's core loop is
the single most-used path in the application and has **no browser spec at all**, which makes it both the
highest-traffic and least-examined surface in the app.

**This document is the scope; the log is the state.** The unit list does not change as the programme
runs — status, findings and the refuted appendix all live in the log, so this file stays a stable
reference and the log stays the only thing being appended to.

**Every size, count and line number below was measured at `0cf2d952`**, and the programme starts at
`df7244af` — three commits later, one of which (EZ-1786, "An Administration section, so the admin tools
stop hiding in three places") **restructured the sidebar**. So `AppLayout`'s 1024 lines and its nav
inventory are known-stale on purpose: S6 and J9 re-map before they audit, and any unit that quotes a
number re-measures it at the sha it is working on. This is the memory rule about numbers carrying their
commit, applied to the plan's own figures.

### Track J — Journeys: how the roles actually work (9 units)

Each unit walks its tasks end to end as the role, counting clicks, hunting three defect shapes: **a
missing action** (cannot be done at all), **a lost action** (can be, but not where anyone would look),
**a punishing path** (can be, but it costs steps or repeats work). Each also answers what happens on the
unhappy path: no data yet, permission refused, request failed, work half-finished. Where the right fix
is a different interaction pattern rather than a missing button, that is a `design`-class finding filed
from a J unit — the tracks are a division of attention, not of finding classes.

| # | Unit | Scope |
|---|---|---|
| **J1** | Student core loop | `/courses` → exercise list → `CourseExercisePage` student side (348+): read statement, write in `SolutionEditor` (264), submit, `AutogradeAnimation` (493) → `AutoTestResults` (469) → `StudentGradingView` (558), `PreviousSubmissions` (255), `ActivityFeed` (983) from the student's seat. **No browser spec covers any of this.** Register: EZ-1758 (drafts never saved — endpoints exist, nothing calls them), EZ-1404, EZ-1630 |
| **J2** | Student periphery & the front door | Login → first screen continuity, deep-link-then-login, `/link/:inviteId` + `/moodle/link` (`JoinByLinkPage` 92, `JoinCard` 176), `/account` (290), `/a/:alias`, `/about`, and the two missing entry points (EZ-1691 `/register`, EZ-1692 `/tos` 404s). Also: a student handed a teacher-only URL gets `Navigate to="/courses"` with no message |
| **J3** | Teacher: course lifecycle | Create course (`CreateCourseDialog`, inline in `CoursesPage` at :186), `EditCourseDialog` (136) reached only from a sidebar item that is not a route, course colours (`course-colors.ts`, 12 mode-blind hex), and whether ending or archiving a course exists at all |
| **J4** | Teacher: exercise authoring | `ExerciseLibraryPage` (787) → `CreateExerciseDialog` (89, title-only, always creates a teacher-graded `lahendus.py`) → `ExercisePage` (481) three tabs → `ExerciseTextTab` (68) + `MarkdownEditor` (159) + `markdownActions` (355) + upload. Register: EZ-1764, EZ-1765, EZ-1757, EZ-1702, EZ-1760, EZ-1687 |
| **J5** | Teacher: putting an exercise on a course | `AddFromLibraryDialog` (334), `NewCourseExerciseDialog` (117), `ExerciseSettingsDialog` (647 — the biggest dialog in the app: deadlines, visibility, exceptions, 12 `useState`), `ReorderExerciseDialog` (258), `MassVisibilityButton`. Register: EZ-1754 |
| **J6** | Teacher: grading | `CourseExercisePage` teacher side (796+), `SubmissionSelector` (75), `AnnotatedCodeEditor` (871), `TeacherFeedback` (638), `ActivityFeed` (983), `SubmissionsList` (299). The flow `doc/testing.md` calls priority 1. Register: review F-035 — saving a grade does not invalidate the query the grade table reads |
| **J7** | Teacher: roster & groups | `ParticipantsPage` (1882, four tabs, 33 `useState`, 45 `sx`), `CreateGroupDialog`, `AddParticipantsDialog`, `EditInviteDialog`, `ConfirmDialog` (58), `DataTable` (122), Moodle link/sync/handover/unlink. Register: EZ-1768, EZ-1778, EZ-1740 |
| **J8** | Teacher: results out | `GradeTablePage` (463) + `gradeTable.ts` (229) + the CSV, `SimilarityPage` (313) + `SimilarityDiff` (92). Grades are the output of the whole application. Register: EZ-1706, EZ-1767 |
| **J9** | Admin | `/admin/messages` (350 — its inline `MessageDialog` is the only field-validated form in the app), `/articles` (130) + `ArticlePage` (289) with view and edit on one URL, About's `OperatingInfo`, the IdP and bug-dashboard external links, and what an admin cannot do from the UI at all. Register: EZ-1761, EZ-1781, EZ-1748, EZ-1741 |

### Track T — The TSL builder (7 units)

The deepest UI in the app and the one the request singles out: `library/tsl/` is 2,331 lines across nine
files, reached through `AutoAssessTab` (351), against a 446-line Kotlin model and a 257-line compiler.
It has the most test coverage of anything in `web/` (`library-exercise-tsl-static.spec.mjs` alone is 633
lines) and the most open debt (EZ-1695, EZ-1734, EZ-1584, EZ-1536). Coverage of the *mechanics* is not
coverage of the *experience*: nothing tests whether a teacher can tell what they are building.

| # | Unit | Scope |
|---|---|---|
| **T1** | Entry & first run | There is no route, menu item or search that reaches the TSL editor: it is the third thing on the second tab of an exercise page, only in edit mode, only for `tiivad:tsl-compose`. Audit from "I want this auto-graded" to a first test existing — `AutoAssessTab` (351), `autoEvalTypes.ts` (118, and the TSL entry is the one container with **no `helpTextKey`** while pygrader links to GitHub), `tslPresets.ts` (133). The lead: choosing TSL creates no `tsl.json`, so the empty string is compiled and the teacher's first sight is a raw kotlinx error with Save disabled. Register: EZ-1734 |
| **T2** | The test forms | `TslTestBody` (693, nine body components), `TslSections` (661), `TslStaticSections` (201), `TslClassInstanceSections` (156), `TslTestCard` (303). Per type: are the labels the teacher's concepts, is required-vs-optional legible (red outlines that **do not block Save**), what does a half-filled test look like, and does the three-level nesting (card → check card → fields) stay comprehensible — or is the card-and-expander shape itself the wrong pattern for a nested spec. The preset menu and the type `Select` also name the same things differently ("Run the program" vs "Program execution test") |
| **T3** | Model vs compiler | `tslModel.ts` (680) against `tsl-common`'s 446 lines and the golden fixtures. The mapping pass already found the shape: **`outputCategory` has 13 values and 0 are reachable from any form**; `ignoreCase`, `nothingElse` on execution checks, `dataCategory: EQUALS`, `beforeMessage`, `language`/`validateFiles`/`requiredFiles`/`tslVersion` and the `passedNext`/`failedNext` branching feature are all JSON-tab-only; `requiredFiles` is not wired to `solutionFileName`, so the two can silently disagree. Review F-039 records the absent guard — this unit's job is which gaps cost a teacher something. `UNCERTAIN` until the :8080 checkpoint |
| **T4** | The feedback loop | The unit the request is really about: **can a teacher tell whether their test set works before a student meets it?** `TslEditor`'s three tabs, the 400 ms parse and 800 ms compile debounce, and the honest answer so far: errors arrive as verbatim kotlinx strings at the top of the editor with no field mapping; the `Generated scripts` tab previews Python, not results; the `Testing` tab only exists once `grader_type` is `AUTO` *as last saved*, runs against the saved version, and lives on a different tab so tests and results are never on screen together. Also: nothing warns that a test can never fail (two known always-pass shapes), and hidden tests still run and count. **Needs the :8080 relay.** Register: EZ-1536, EZ-1756, EZ-1755 |
| **T5** | State, persistence, escape | `useTslSpec` (145), `exerciseDraft.ts` (97), the visual↔JSON two-way sync. Four leads, each a candidate critical: a React-Router navigation (breadcrumb, sidebar, kebab) **has no unsaved-changes guard at all** while `Cancel` and `beforeunload` do; changing a test's type discards the whole body with no confirm; there is **no undo** at model level, so a deleted test or check is gone; and `tslValid` is never reset when the container changes away from TSL, so Save can stay disabled forever |
| **T6** | TSL under pressure | The same builder at 390 and 2560, in dark, in Estonian — where the 2.42× string, the deepest nesting in the app, 7 `minWidth` values in `TslTestBody` and 5 in `TslSections`, and CodeMirror's `oneDark` against a light MUI surface all meet |
| **T7** | The other end | What the student sees when a TSL test fails: `AutoTestResults` (469) rendering `OK_V3` per-test accordions, and `TeacherFeedback` (638). A test set is only as good as the message it produces, and review C2/F-019 found raw container output reaching student-visible feedback. `window.prompt`/`window.confirm` in `AutoAssessTab` for asset add/remove belongs here too — native browser dialogs in a MUI app |

### Track S — Surfaces: theme, screen size, and the front door (9 units)

Systematic rather than journey-shaped: every route × role × theme × viewport, driven and photographed.
The viewport set is fixed so findings are comparable: **390×844** (phone), **768×1024** (tablet/split),
**1440×900** (laptop), **2560×1440** (large monitor), plus **320** as a stress case only. S4 establishes
the reference so S3 and S5 are diffs against it rather than fresh walks.

| # | Unit | Scope |
|---|---|---|
| **S1** | The theme as a system | `theme.ts` (293) read as a design system. Only `background`, `text` and `divider` switch with mode; `primary`, `secondary`, `success`, `warning`, `error`, `info` and all 25 `shadows` are mode-blind. `secondary.*` and the four `*.light` tints have **zero uses**. `text.secondary` has **177** — the app's real workhorse. Only `primary` declares a `contrastText` (`#fff` on `#16a34a`, ~3.1:1, 26 use sites); MUI computes the other five independently. `shape.borderRadius: 12` is overridden to 8 by Button/Chip/ListItemButton/Alert, to 6 by Tooltip, restated as 12 by Card, and 16 by hand in `AppLayout`. `MuiCard` gets a hover border and shadow *by default*, so non-interactive cards animate |
| **S2** | Dark mode, everywhere | All 22 routes, both role variants where they differ, in dark — against an automated baseline of exactly one dark screenshot. 245 colour literals in 17 files, 56 `mode === 'dark'` conditionals in 20 files, `styled()` used **zero** times and `sx` 953 times. Targets: `LandingPage` (42 hex + 50 rgba + its own `DARK`/`GREEN`/`GREEN_BRIGHT` block), `AutogradeAnimation` (15 hex + 12 rgba + 8 inline `style` on SVG), `course-colors.ts` (12 mode-blind swatches), `RobotFace` (`backgroundColor: 'white'`), `AboutPage` (3 `bgcolor: 'white'` sponsor boxes), `EmbedDialog`'s `background: 'white'` iframe, `logo.svg` tinted five different ways by CSS `filter`, and the three brand greens (`#16a34a` theme, `#43a047` in `index.html`/manifest/`CoursesPage` activity dot, `rgba(76,175,80)` in the theme's own dark selected states) |
| **S3** | Phone | All 22 routes at 390. The mobile drawer is the *only* responsive machinery in the app, so this unit is mostly about everything else: the 260px drawer against a 390px screen, the 647-line settings dialog, the grading split pane, CodeMirror, MUI date pickers (mobile variants are opt-in), and the tables — `GradeTablePage` has a `TableContainer` and a hand-rolled sticky first column, while `ParticipantsPage` and `ExerciseLibraryPage` use bare `<Table>` with **no `TableContainer`**, so no overflow-x at all |
| **S4** | Laptop — the reference | All 22 routes at 1440. Establishes what "correct" looks like for S3 and S5 to deviate from, and catches what is wrong at *every* size. 119 responsive `sx` keys exist in 8 files, but 60 are `LandingPage`'s — the app proper has ~59 |
| **S5** | Large monitor | All 22 routes at 2560, a viewport no spec has ever rendered. `Container maxWidth="lg"` caps content at 1200px beside a fixed 260px drawer; nothing uses `xl`. Which surfaces genuinely want the room — grade table, roster, library, grading split pane, TSL builder — and what the wasted ~1300px costs them. Register: EZ-1527, open since 2022 |
| **S6** | The shell at every size | `AppLayout` (1024, 60 `sx`, 21 icons): the two-state drawer with no rail, the role-switcher chips and `resolveRoleTarget`'s seven navigation rules, `ListSubheader` with an anchor inside it, the two banners that push chrome down, the account menu, the absent breadcrumbs (three local `Breadcrumbs`; `ArrowBack` icon buttons elsewhere), the hand-rolled `h5` page title in 17 places and `h4` in two, `usePageTitle` missing on `EmbedExercisePage` and `TermsRedirect`. Also `ThemeContext` reads `prefers-color-scheme` once at mount and never subscribes while `useEmbedTheme` does — so the embed follows the OS and the app does not, and one toggle removes "follow system" permanently. Register: EZ-1789 |
| **S7** | Dense data | Where many rows meet a fixed column: `GradeTablePage` (one column per course exercise, `maxWidth: 100` title cells), `ParticipantsPage`'s four tabs, `ExerciseLibraryPage`, `CourseExercisesPage`. Sorting affordances against the documented `TableSortLabel` convention, sticky headers, column priority, and what 200 rows and a 60-character name do. `DataTable` (122) is used three times, all in one file; everything else builds its own table |
| **S8** | Editors, code and motion | `CodeEditor` (128), `MarkdownEditor` (159) + `MarkdownToolbar` (213), `AnnotatedCodeEditor` (871, 10 dark conditionals), `ReadOnlyCodeSnippet` (122), `SimilarityDiff` (92). Eight files push `oneDark` when dark while light mode uses CodeMirror's *default* theme — there is no custom light theme — plus eight `EditorView.theme()` blocks of per-site overrides and 27 unthemed `fontFamily: 'monospace'` sites. Then the overflow gap: **15 `dangerouslySetInnerHTML` sites, only three carrying the `& pre {overflowX:auto}` / `& table {display:block}` guards** — `CourseExercisePage`, `TeacherFeedback` and `ActivityFeed` inject exercise and feedback HTML without them. Finally motion mechanics: 21 `keyframes` in 6 files against `prefers-reduced-motion` honoured only in `JoinCard` and `RobotFace`, leaving `AutogradeAnimation` unguarded (whether it should exist at all is V3) |
| **S9** | The front door | `easy-kc-theme`, cloned for this unit: the Keycloak login, registration, error, password-reset and ToS screens, last touched 2023, fronting an app redesigned in 2026. Rendered at all four viewports in both themes and compared against the app's own visual language — wordmark, green, type, radius, focus ring — and against the theme it hands over to. `/tos` (EZ-1692) is both an app route and Keycloak's ToS target, so it belongs to J2 and here |

### Track V — Visual direction and design quality (4 units)

The track that carries the `design` class. Not a wrap-up of the others: it asks the question none of them
can, which is whether the choices are the right choices. Every session loads `frontend-design` first,
and every finding carries an argument, an alternative and a reach estimate.

| # | Unit | Scope |
|---|---|---|
| **V1** | Identity: is there one? | The app's visual direction as a whole, judged against what it is for — students under exam pressure, teachers all day, Estonian first, two themes, university hardware. `theme.ts` is 293 lines: stock Roboto, MUI default spacing and breakpoints, a Tailwind green ramp, `borderRadius: 12` contradicted in six places, 25 shadows of which 16 are duplicates. The question is which of that is a decision and which is a default nobody made — and what a deliberate direction for *this* product would be, at a granularity the theme file could actually adopt. The `frontend-design` calibration applies directly: the audit's job is to name where the app landed on the default anyone would have got |
| **V2** | Two products, one app | `LandingPage` (1,225 lines) carries a private dark-only design system — `DARK`/`GREEN`/`GREEN_BRIGHT`, Fraunces, Outfit, JetBrains Mono, 60 of the app's 119 responsive `sx` keys, 7 of its 8 `Container`s, 4 of its 15 `display:{xs}` rules — while the app behind it has none of that and runs on Roboto. `JoinCard` is the only other user of those faces. So the app has one designed surface and twenty-one undesigned ones, three brand greens, two font-loading mechanisms, and a first impression that does not resemble the product. Which direction should win, what the honest cost of unifying is, and what the interim looks like |
| **V3** | Components and patterns that should be different | The unit for "it works and it is still the wrong thing". Candidates the mapping passes already surfaced: `ParticipantsPage` — 1,882 lines, four tabs, 33 `useState`, three `DataTable`s, and the only page in the app that needs a `SelectionToolbar`; `ExerciseSettingsDialog` — 647 lines of deadlines, visibility and per-student exceptions inside a modal; the TSL builder's card-in-card-in-field nesting; a sidebar item that opens a dialog instead of navigating; `ArticlePage` putting view and edit on one URL; the role switcher as three chips with seven hidden navigation rules; `AutogradeAnimation` — 493 lines of animation between a student and their result, on every submission, with no `prefers-reduced-motion` guard; and `window.prompt`/`window.confirm` in a MUI app. Each gets an argument, an alternative, and a reach estimate — not a rewrite instruction |
| **V4** | Type, density and hierarchy | Nine typography overrides, **no `h1`–`h3`, no `body1`, no responsive font sizes, no `responsiveFontSizes()`**, `fontSize: 14`, and a page-title convention (`h5` at 1.5rem/400) hand-rolled in 17 places with `h4` in two — so page title, section title and dialog title are close to indistinguishable. `text.secondary` is used **177 times**, more than every other palette token combined, which is worth asking about: if most text is secondary, the hierarchy is doing no work. Plus table heads at uppercase 0.75rem with 0.05em tracking, `caption` at 0.75rem, `overline` at 0.68rem — read at 390px, in Estonian, by someone under time pressure |

### Track C — Cross-cutting consistency (5 units)

Each looks at one *pattern* across every surface, so each should produce one strong finding rather than
a list.

| # | Unit | Scope |
|---|---|---|
| **C1** | Forms, validation and error copy | 19 dialogs, all hand-rolled `useState` + MUI `TextField`, **no form library, no schema validation, no `<form onSubmit>` convention anywhere**. Eleven have no validation at all; the only field-level validation in the app is inline in `SystemMessagesPage`. Meanwhile `general.somethingWentWrong` is the error copy in 15–19 files while `api/client.ts` (94) parses the `errorBody.code` and `attrs` core populates deliberately and nothing renders them; TSL's save path discards the server's actual reason. One pattern finding, not nineteen dialogs — and the copy half has a norm to cite: errors say what happened and how to fix it |
| **C2** | Loading, empty and error states | One `Skeleton` in the whole app (`StudentGradingView:452`) against 31 files importing `CircularProgress` and no `LinearProgress` anywhere; `RobotPlaceholder` (13), the canonical empty state, used in exactly 2 places, both on one page; `CoursesPage` renders an empty grid for no courses; `GradeTablePage`'s empty string is a jokey ":-)"; `RequireAuth` and `IndexRedirect` hold two near-identical inline loading blocks because there is no shared one. Plus `ErrorBoundary`→`CrashScreen` and the pre-React config-failure screen, neither of which any spec visits. An empty screen is an invitation to act, and most of these are not |
| **C3** | Destructive actions and confirmation | `ConfirmDialog` (58) lives under `features/participants/`, so nothing outside that page can use it — and `AutoAssessTab` reaches for `window.confirm`/`window.prompt` instead. Sweep every destructive action — delete dir, delete exercise, remove from course, unlink Moodle, delete group, delete article, delete message, discard an editor draft, change a TSL test's type — and ask which confirm, which are undoable, which say what will be destroyed, and which are one keystroke away |
| **C4** | Feedback after a mutation | Does the user learn it worked? One `Snackbar` exists (bug-report success, 4s, in `AppLayout`); there is no toast bus and `QueryProvider` (42) has no global `onError`. Trace every mutation to what the user sees on success and on failure, and cross-reference review F-035: a silent success that also fails to refresh is indistinguishable from a failure. Also the naming rule — the button that says Publish should produce a message that says Published |
| **C5** | Keyboard, focus and a11y coverage | Run `a11y.mjs` — `scan`, `checkMainLandmark`, `checkDuplicateLinkNames`, `checkFocusVisible`, **and `contrastFindings` in both themes** — across all 22 routes and the states the journeys reach, against the 2 specs wired to it today and an empty baseline. Then by hand: tab order through the shell and the three biggest dialogs, the focus-trap convention (`CreateDirDialog`'s `TransitionProps.onEntered`, found broken in 4 of 12 dialogs by review E5), Escape-to-close, Enter-to-submit, and the link rule (`spaLinkProps`, EZ-1789). `AppLayout` provides the only `main` landmark in the app; the doubled `:focus-visible` rule in `theme.ts` is the one piece of this already done properly and is the model to cite |

### Track D — Documentation (2 units)

| # | Unit | Scope |
|---|---|---|
| **D1** | UI/UX docs against reality | `web/README.md`, `doc/web/browser-testing.md` (316), `DEVELOPMENT.md`, `doc/testing.md`, `doc/wui/`, `archive/ezspa/`. Three stale claims already found: `doc/testing.md` says "Accessibility tests — none, and cheap to start" while `a11y.mjs` is 376 lines with a ratchet; EZ-1707 says `web/README.md` is the stock Vite template, which it plainly is not; and `doc/review-plan.md` and `doc/review-log.md` (twice) say "13 golden fixture sets" where the directory holds **9** `.json` specs. Also: state plainly which of `doc/wui/` is provenance and which is misleading, since its own README already says none of it describes the current UI |
| **D2** | The consolidated guide | **Written**, per the decision above, as `doc/web/ui-guide.md`. The `ui-conventions` table lives only in the session memory directory, so no human contributor can read the single most load-bearing UI document in the project — and one of its eight rules is explicitly "not guessable". The guide carries: the conventions table with its canonical implementation per rule; the theme's semantic tokens, which switch with mode, which do not, and which are dead; the viewport set and what each surface owes it; the loading / empty / error / confirm patterns with their canonical file; the form and validation pattern; the error-copy rule; the a11y floor including the focus-ring specificity trap; the type scale and what each level is *for*; and whatever direction the V track establishes, stated as tokens rather than adjectives. Plus the part that makes it a checklist rather than prose — **a "how to check" line per rule**, most of them a driver invocation or a grep. It extends `doc/web/browser-testing.md`'s "screenshots are the point" rather than competing with it, and it is written last, from the log |

---

## Seeded leads

Noticed while planning. **Leads, not findings** — each goes through both stages, and several will turn
out to be deliberate. The palette one is kept including its own refutation, as the programme's
cautionary tale.

**S1 — the lead that refutes itself.** `success.light` (`#f0fdf4`), `warning.light` (`#fff8e1`),
`error.light` (`#ffebee`) and `info.light` (`#e3f2fd`) are near-white in *both* modes, and so is the
whole `secondary` triplet. All six have **zero uses in `src/`** — `secondary.main` survives only as the
default env-badge colour in `public/config.json`. So the finding is dead-and-mode-blind palette entries,
not unreadable chips. The live contrast question is `primary.contrastText: '#fff'` on `#16a34a` at
~3.1:1 across 26 sites, plus `warning.main` `#f9a825` with a contrast text MUI computes on its own.

**V1/S2** — the theme's own dark selected-state greens are `rgba(76,175,80,…)` — Material green 500 —
not the `GREEN` ramp `primary` comes from. Three greens, none of them agreeing.

**S2/S6** — `ThemeContext` reads `prefers-color-scheme` once in `getInitialMode` and never subscribes;
`useEmbedTheme` (78 lines, mostly comment) does subscribe and syncs across iframes via the `storage`
event. So the embed follows the OS, the app does not, and one toggle removes "follow system" for good.

**V2/S8** — `@fontsource/roboto` is imported in `App.tsx` *and* `index.html` links Google Fonts for
Sniglet, Fraunces, Outfit and JetBrains Mono. Two font mechanisms; the three display families are used
only by `LandingPage` and `JoinCard`; `Sniglet` carries the wordmark in five places. Review B2 has the
CSP angle; this is the design-system angle.

**J2** — role mismatch in `RequireAuth` is `Navigate to="/courses" replace` with no message, so a shared
teacher URL silently relocates a student. `checkinFailed` renders a bare `somethingWentWrong` with no
retry. `/library` is an ungated bare `Navigate` to a guarded child; `/a/:alias` and `/about` have no
`RequireAuth` and gate on response fields instead.

**C1** — nothing renders `ApiResponseError.errorBody.code`/`attrs`, so every failure in the app is the
same sentence. Core populates those deliberately and `client.ts` already parses them.

**T1** — `changeType()` to TSL seeds `gradingScript`, `assets: [{generated_0.py: ''}]`, `maxTimeSec: 7`
and `maxMemMb: 30`, but no `tsl.json`, so the first compile is of `""` and fails. Settle by execution.

**T3** — duplicate test ids are validated only in `DemoApplication.main()`, never in `compileTSL`, and
`doc/core/tsl-migration/README.md` records **174 of 721** production specs already having them. A spec
can also compile to invalid Python and fail only when a student submits. The injection shape in that
same area is security and goes to `doc/review-log.md`, not here.

**J6/C4** — review F-035: saving a grade does not invalidate the query the grade table and its CSV read,
so the teacher sees a stale number. Already confirmed; this programme's job is the UX consequence.

**Docs** — `web/poc-teacher-grading.html` (76 KB) and `-v2.html` (54 KB) are stale artefacts at the web
root, already logged as a review-programme H3 lead. Mentioned so this programme does not re-file them.

---

## Verification

The programme is a detector, and it gets checked the way its own rules demand.

1. **Baseline.** `npm run lint`, `npm run test:unit`, `npm run test:browser` at the starting sha, judged
   on **exit codes**. Anything already red is recorded as pre-existing.
2. **Prove the instrument.** Render a page, then a variant with a known defect injected *in the driver*
   (not the repo) — force `theme: 'dark'` on `AboutPage`, whose sponsor boxes are `bgcolor: 'white'` —
   and confirm the auditor names it from the PNG alone. Until that passes, no S- or V-track finding is
   trustworthy.
3. **Prove the a11y sweep can fire.** `a11y-baseline.json` is empty, which means either the app is clean
   on the two routes it covers or the detector is inert on the way it is being called. Point it at a
   state with a known violation shape before believing a clean result on twenty new routes.
4. **Count before concluding.** Every finding that blames a token, a component or a pattern names how
   many places use it. The `*.light` lead is why.
5. **Every design finding names its alternative and its reach.** One without them is an unfinished
   finding and stays `UNCERTAIN` until it has both — that is the class's quality gate, and it is the
   only thing that stops the design track collapsing into a wish list.
6. **Watch the finding-rate per unit.** More than ~8 of one shape means the rule is wrong; collapse to
   one finding at the level of the decision, with instances as evidence.
7. **Check every finding against the register.** A duplicate of an open EZ issue is a register hit, not
   a finding, and the log says which id.
8. **Spot-check the log.** Every screenshot path exists; every `CONFIRMED` names the viewport, theme,
   role and language it was seen at. "Reading the code suggests" is `UNCERTAIN`, not `CONFIRMED`.
9. **Done means done.** Every unit `done`, the Refuted appendix populated, `doc/web/ui-guide.md`
   written, and a closing summary: counts by class and severity, the confirmed list ordered by
   consequence *and reach*, register hits separated from new findings, and a proposed triage.

---

## Input to the string and icon audits

Both follow-ons are already filed — **EZ-1785** (Estonian/English strings: terminology, tone,
naturalness) and **EZ-1759** (icon usage, orphaned i18n strings, write down the icon conventions). This
programme does not do their work, but it walks every surface first, so it hands them leads rather than
letting them start cold. A log section collects these without chasing them:

- **for EZ-1785** — strings seen in place that read wrong, plus the layout-relevant half this audit does
  own: at `0cf2d952`, 858 keys with exact parity both ways and 137 where Estonian is ≥30% longer. The
  et/en length ratio per surface falls out of the S track. Also the 149 `tsl.*` keys, which are the
  entire user-facing documentation of TSL and therefore carry more weight than labels usually do.
- **for EZ-1759** — the measured baseline is **222 icon bindings, 105 distinct, 94.6% outlined**, with
  ten filled (`CheckCircle` ×4, `RadioButtonUnchecked` ×2, `Cancel`, `ExpandMore`, `GitHub`, `Menu`),
  which is the documented rule holding. So the leads worth collecting are qualitative: one concept drawn
  with two icons, one icon meaning two things, icon-only controls whose meaning is not guessable, the
  two bespoke `SvgIcon`s in `components/icons.tsx` against the MUI set, and EZ-1584 (a TSL input-type
  icon invisible when there is no input), which sits exactly here.
- **one shared observation, worth stating early:** `general.somethingWentWrong` is the error copy in
  15–19 files. That is a copy problem, a UX problem and an icon problem at once, and whichever audit
  reaches it first should say so rather than each filing a third of it.

---

## What happens after

Not part of this programme, but the reason for it. Three outputs, and they are triaged differently:

- **Journey, theme, responsive, consistency and a11y findings** become EZ issues (`Type: Usability` /
  `Cosmetics`, Subsystem `web`, Assignee `kspar`) or addenda to the twenty-plus already open, with the
  criticals gating a release.
- **Design findings** are a decision, not a backlog: the V track's output is a proposed direction —
  palette, type scale, density, motion, and which components should change shape — ordered by reach, for
  kspar to accept, amend or reject as a whole. What survives becomes `doc/web/ui-guide.md`'s token
  section and a sequence of issues; what does not is recorded as considered-and-rejected, so the next
  audit does not re-propose it.
- **The guide**, written by D2 — the document whose absence is why this audit has to assemble its own
  norm from six sources.

Then the string audit (EZ-1785) and the icon audit (EZ-1759), both inheriting this programme's leads
section and its surface-by-surface screenshots.
