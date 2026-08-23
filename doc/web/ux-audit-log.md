# UI/UX audit: state and findings

The programme's scope and method are in `doc/web/ux-audit-plan.md`, and that file does not change. This
one does: it holds the status of every unit, every finding, and — just as importantly — every candidate
that was refuted, so no later session spends a context window re-finding it.

Both files are **committed**, unlike the code review programme's pair. That gitignore is a security
decision: a list of unfixed access-control and XSS findings is a roadmap, and `kspar/easy` is public.
Nothing here is that. **The carve-out:** anything with a security consequence goes to
`doc/review-log.md` instead, and this log gets a pointer with no detail.

**Ground rules, one line each** (the reasoning is in the plan): report only, never fix in an audit
session; a finding names who it costs, what happens, and its evidence — and for anything proposing a
change of direction, what better looks like; design quality is a first-class class, rated on product
cost and never downgraded for lacking a task cost; file at the level of the decision, not the sighting;
count the uses before writing the consequence; check the known register before writing anything; judge
layout in Estonian; every number carries the sha it was measured at.

---

## Baseline

Filled in by the first session, before any unit is audited.

| | |
|---|---|
| Programme set up | 2026-08-23 |
| Tracking issue | EZ-1791 |
| Starting sha | `df7244af` |
| Sha the plan's sizes were measured at | `0cf2d952` — three commits earlier. **EZ-1786 restructured the sidebar in between**, so `AppLayout`'s figures are known-stale by design; S6 and J9 re-map before auditing |
| `npm run lint` | **exit 0** — 55 problems, **0 errors, 55 warnings**, at `bf673235`. Note EZ-1722 says "38 across 18 files"; the backlog is now 55, so that issue's number is stale |
| `npm run test:unit` | **exit 0** — 15 files, 203 tests, 0 failed |
| `npm run test:browser` | **exit 0** — 39 passed, 1 skipped (`library-exercise-tsl-live`, which skips itself unless `HARNESS_LIVE=1`), 5.3 min |
| Pre-existing failures | **none** |
| Driver harness proven | **yes** — `web/tests/audit/prove-instrument.mjs`. Rendered `/about` in dark and read the three `bgcolor: 'white'` sponsor plates off the PNG against a `rgb(18,18,18)` page |
| a11y sweep proven able to fire | **yes** — `j1-measure.mjs` injects a nameless `<button>` and an `alt`-less `<img>` after scanning, and the second scan reports `button-name` and `image-alt`. Needed: the first version of that driver read `scan().found` / `.contrastFindings`, which do not exist — the real shape is `{ gate, contrast }` — so it printed 0 findings and would have recorded "the student exercise page is accessible" as a fact |
| `easy-kc-theme` cloned | **yes** — `$CLAUDE_JOB_DIR/tmp/easy-kc-theme` (shallow), for S9 |
| `:8080` compile relay authorised | **kspar has approved it in principle and asked to be told when it is needed.** Ask before the first relay in T3/T4 |

### An operational note for every session

Unlike `doc/review-log.md`, this file is tracked, so there is no copy-out-of-the-worktree ritual: edit
it where it lives and commit.

Audit drivers live in **`web/tests/audit/`** — tracked, and invisible to all four gates by
construction (playwright's `testMatch` and `testDir`, `spec-inventory.mjs`, `suite-integrity.test.mjs`
and `eslint .` each miss it for a different reason; the reasoning is in `audit.mjs`'s header). Run the
stub server on **5299**, never the suite's 5199. Two kinds of output:

- **`web/tests/audit/reports/*.json` — tracked.** A finding cites the report that produced it, and a
  citation into a gitignored directory rots on a fresh checkout. A diff between two runs is also how a
  later session tells "we fixed it" from "we stopped looking".
- **`web/tests/screenshots/audit/*.png` — gitignored.** Screenshots a finding leans on get copied
  somewhere durable and their path recorded; a finding whose evidence has evaporated is downgraded to
  `UNCERTAIN` by the next session.

---

## Status

`todo` → `in progress <date> <sha>` → `done <sha>`. A unit is never left half-audited: either it
finishes or it goes back to `todo`.

### Track J — Journeys (9 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| J1 | Student core loop | **in progress** 2026-08-23 `bf673235` | X-001…X-006 | Core loop walked: `/courses` → list → exercise → type → submit → grader → result, plus the draft question and a graded-with-feedback state. **Remaining before `done`:** past-deadline and closed states, a `TEACHER`-graded exercise (no autograde path), `PreviousSubmissions` expanded, `ActivityFeed` from the student's seat in depth, and `solution_file_type: 'TEXT_UPLOAD'`, which is a different editor entirely |
| J2 | Student periphery & the front door | todo | — | Pairs with S9 on the login handover |
| J3 | Teacher: course lifecycle | todo | — | |
| J4 | Teacher: exercise authoring | todo | — | EZ-1732 (math no longer renders) is the one to check first |
| J5 | Teacher: putting an exercise on a course | todo | — | |
| J6 | Teacher: grading | todo | — | `doc/testing.md` priority 1; review F-035 lands here |
| J7 | Teacher: roster & groups | todo | — | Largest file in the repo; V3 will want its notes |
| J8 | Teacher: results out | todo | — | |
| J9 | Admin | todo | — | Re-map first: EZ-1786 changed the admin surface at `df7244af` |

### Track T — The TSL builder (7 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| T1 | Entry & first run | todo | — | The empty-`tsl.json` lead: settle by execution, not by reading |
| T2 | The test forms | todo | — | |
| T3 | Model vs compiler | todo | — | `UNCERTAIN` until the :8080 relay is authorised |
| T4 | The feedback loop | todo | — | Needs the :8080 relay. The unit the request is really about |
| T5 | State, persistence, escape | todo | — | Four candidate criticals; the router-navigation guard is the first |
| T6 | TSL under pressure | todo | — | Do after S1–S3 so the theme baseline exists |
| T7 | The other end | todo | — | Read review F-019 first |

### Track S — Surfaces (9 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| S1 | The theme as a system | todo | — | Do before S2 and the whole V track. **From J1:** X-004 already measured the three contrast decisions with real ratios (`#fff` on `#16a34a` = 3.29:1, `#16a34a` on `#f5f5f5` = 3.02:1, `#757575` on `#f5f5f5` = 4.22:1 at 12px). S1 owns the token-level fix and should check whether `GREEN[700]` as `primary.main` breaks anything else. Also settle the derived `#989898` at 2.64:1, which is not a theme value |
| S2 | Dark mode, everywhere | todo | — | |
| S3 | Phone | todo | — | Do after S4 |
| S4 | Laptop — the reference | todo | — | **Do first in this track after S1**: establishes the reference the others diff against |
| S5 | Large monitor | todo | — | EZ-1527, open since 2022 |
| S6 | The shell at every size | todo | — | Re-map: EZ-1786 restructured the sidebar at `df7244af`. **From J1:** X-005 (the exercise-list `key` on the wrong element) lives here at `AppLayout.tsx:511`, and two maps earlier the same file does it correctly — count the other lists. Also count the `ArrowBackOutlined` back-link sites for X-003's reach: on pages with no breadcrumbs it is the only way back, and it has no accessible name |
| S7 | Dense data | todo | — | |
| S8 | Editors, code and motion | todo | — | Motion *mechanics* here; whether the animation should exist is V3. **From J1:** X-002 (no accessible name on `.cm-content`) is in the shared `CodeEditor.tsx`, so it reaches the teacher testing tab, the TSL JSON tab and every constructor-code field — check whether one `aria-label` prop fixes all of them. X-008 is the fixed 200 px height. Two unlabelled glyph controls (`|<` and `>|`) sit in the split-pane gutter with no button chrome and no visible affordance — establish what they do and whether anyone would find them |
| S9 | The front door | todo | — | Clone `easy-kc-theme` into the job tmp dir first |

### Track V — Visual direction and design quality (4 units)

Load the `frontend-design` skill before each of these. Every finding carries an argument, an alternative
and a reach estimate — a design finding without all three stays `UNCERTAIN`.

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| V1 | Identity: is there one? | todo | — | Do after S1, S2, S4 — it needs their screenshots as evidence |
| V2 | Two products, one app | todo | — | |
| V3 | Components and patterns that should be different | todo | — | Do last in this track: it inherits from J1–J9 and T1–T7 |
| V4 | Type, density and hierarchy | todo | — | |

### Track C — Cross-cutting consistency (5 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| C1 | Forms, validation and error copy | todo | — | One pattern finding, not nineteen dialogs |
| C2 | Loading, empty and error states | todo | — | |
| C3 | Destructive actions and confirmation | todo | — | |
| C4 | Feedback after a mutation | todo | — | Cross-reference review F-035 |
| C5 | Keyboard, focus and a11y coverage | **in progress** 2026-08-23 `79248877` | X-003 (extended), X-004 (extended), X-009, X-010, X-011 | **Promoted and run: the axe sweep is done** — 23 surfaces × 2 themes = 46 scans, `tests/audit/c5-a11y-sweep.mjs`, report at `web/tests/audit/reports/c5-a11y-sweep.json`. **6 distinct gate-level violations, 29 distinct contrast.** Canary fired on the run. **Remaining before `done`:** tab order through the shell and the three biggest dialogs by hand, the `TransitionProps.onEntered` focus-trap convention across the 19 dialogs (review E5 found it broken in 4 of 12), Escape-to-close and Enter-to-submit, and a re-scan of the 15 routes marked `thin` in the JSON with realistic data — those were scanned in their empty state, so a table cell's contrast could not have been seen. Original note follows. **From J1: this is the highest-yield unit in the programme and it should probably be promoted.** The very first non-wired route scanned produced **two gate-level violations** (X-002, X-003) and **ten contrast findings** (X-004). The `a11y` fixture is wired to 2 of 40 specs, so ~20 routes have never been scanned once. Two mechanics to inherit: `scan()` returns `{ gate, contrast }` — not `{ found, contrastFindings }`, which silently reads as "clean" — and always run the canary from `j1-measure.mjs` before believing a zero |

### Track D — Documentation (2 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| D1 | UI/UX docs against reality | todo | — | Three stale claims already identified; see the register's "candidates to close". **From the baseline:** EZ-1722 says "38 across 18 files"; `npm run lint` at `bf673235` reports **55 warnings, 0 errors**, so that issue's number needs updating and the backlog is growing rather than shrinking |
| D2 | The consolidated guide | todo | — | **Last unit.** Writes `doc/web/ui-guide.md` from this log |

---

## The known register

Open EZ issues touching web UI/UX at `df7244af`, grouped by the surface they belong to. **Check this
before writing any finding.** A finding that duplicates one of these is a register hit, and the log
records the id rather than a new number.

Queries used: `project: EZ #Unresolved Subsystem: web` (paged) and
`project: EZ #Unresolved Type: Usability, Cosmetics`.

### Student core loop
| id | Type | Summary |
|---|---|---|
| EZ-1758 | Feature | Student solution drafts are never saved — the endpoints and hooks exist, nothing calls them |
| EZ-1404 | Usability | Add hint about submitting in Thonny on course exercise page |
| EZ-1630 | Cosmetics | Show to student when course has been fully completed |
| EZ-1712 | Supertask | AI-explained autograder feedback for students (BYOK) |

### Entry points and periphery
| id | Type | Summary |
|---|---|---|
| EZ-1691 | Feature | `/register` page is missing |
| EZ-1692 | Bug | `/tos` route is missing and 404s |
| EZ-1701 | Bug | "Account settings" menu item does nothing |
| EZ-1698 | Feature | Anonymous autoassess embed page is missing |
| EZ-1741 | Feature | Notification preferences need somewhere to live |

### Exercise authoring and the library
| id | Type | Summary |
|---|---|---|
| EZ-1732 | Bug | Math in exercise text no longer renders: no MathJax or KaTeX in `web/` |
| EZ-1764 | Feature | Upload images from the editor: the web app has no way to create a stored file |
| EZ-1765 | Feature | Course exercise instructions cannot be edited in the web app, only rendered |
| EZ-1757 | Feature | List, view and diff older versions of a library exercise |
| EZ-1702 | Bug | Verify adoc → Markdown conversion of existing exercise texts |
| EZ-1760 | Feature | Mark people you just shared with in the library share dialog |
| EZ-1687 | Usability | Share dialog: autocomplete for teacher emails |

### Course exercise and assessment
| id | Type | Summary |
|---|---|---|
| EZ-1754 | Bug | Course exercise's assessment tab shows a placeholder instead of the auto-assessment config |
| EZ-1755 | Feature | Teacher testing tab should open with the solution last tested |
| EZ-1756 | Feature | Store the auto-assessment result with each teacher test submission and show it |

### TSL
| id | Type | Summary |
|---|---|---|
| EZ-1695 | Feature | React exercise page: TSL editor (visual + YAML) — still open although the visual editor ships |
| EZ-1734 | Usability | Auto-assessment container dropdown is hardcoded, so unavailable containers fail only at save |
| EZ-1536 | Usability | Make TSL generated code readable |
| EZ-1584 | Cosmetics | TSL compose input type icon should be visible even if there's no input |

### Roster, groups, Moodle
| id | Type | Summary |
|---|---|---|
| EZ-1768 | Bug | Moodle sync poll stops after the first flag change and never restarts |
| EZ-1778 | Bug | Participant table shows a Moodle username for pending students but not for linked ones |
| EZ-1740 | Architecture | Email invites dropped from web on purpose — keep the endpoint until July 2027 |

### Grades and similarity
| id | Type | Summary |
|---|---|---|
| EZ-1706 | Usability | Grade table: link grade cells to the student's submission |
| EZ-1767 | Bug | Grade table — **verify state**: commit `785a8cc7` reads as a fix ("stops losing students, and learns to sort the same way twice") |

### Admin
| id | Type | Summary |
|---|---|---|
| EZ-1761 | Feature | Admin UI for executors: loads, and container image assignments |
| EZ-1781 | Feature | Let non-core-devs update grading library versions, with rollback, and show the versions |
| EZ-1748 | Feature | Scheduled system messages — **verify state**: `SystemMessagesPage` ships |
| EZ-1786 | Feature | Generic bug reporting from the app — in flight at `df7244af` |

### Shell, navigation, conventions
| id | Type | Summary |
|---|---|---|
| EZ-1789 | Bug | Sidebar and account menu items cannot be opened in a new tab: no `href` |
| EZ-1527 | Usability | Optimise for large screens (open since 2022 — S5) |
| EZ-1722 | Architecture | Clear the react-hooks warning backlog in `web/` (38 across 18 files) |

### Accessibility
| id | Type | Summary |
|---|---|---|
| EZ-1636 | Usability | Review aria attrs and other accessibility rules in MD |

### The follow-on audits this programme feeds
| id | Type | Summary |
|---|---|---|
| EZ-1785 | Architecture | Audit the Estonian and English UI strings against each other |
| EZ-1759 | Architecture | Audit icon usage and orphaned i18n strings, write down the icon conventions |

### Candidates to close, found while building the register

A by-product worth its own line: the register itself needs tidying, and D1 should propose it rather than
each unit tripping over the same stale issue.

| id | Why it looks stale |
|---|---|
| EZ-1414 | "Dark theme" (Cosmetics, 2021). Dark mode ships: `ThemeContext` reads `prefers-color-scheme`, there is a toggle in two places, and `theme.ts` switches three token groups. The *quality* of it is this programme's S2 and V1, not this issue |
| EZ-1707 | "`web/README.md` is still the stock Vite template". It plainly is not — it is ~8 KB documenting version stamping, runtime config and the environment badge |
| EZ-1705 | "Set up automated tests for React web". EZ-1766 delivered 41 browser specs, 15 unit suites and five ratchets |

---

## Findings

Numbered `X-001` onwards, in the order they are confirmed — not grouped by unit, so the numbering is
stable and nothing gets renumbered by a later insertion. `CONFIRMED` and `UNCERTAIN` only; refuted
candidates go in the appendix.

Template:

```
### X-001 <one-line statement, from the user's side of the screen>
- Unit: <J1>
- Surface: <route> (<role>), <light|dark>, <viewport>, <et|en>
- Norm: <which of the plan's six sources, and what it says>
- Class: journey | design | theme | responsive | consistency | a11y | docs | copy
- Severity: critical | high | medium | low
- Reach: <how many surfaces the same decision touches>
- Verdict: CONFIRMED | UNCERTAIN
- What happens: <who, doing what, and what they get>
- Instead: <design and pattern findings only: what better looks like, and what it costs>
- Evidence: <screenshot path, click count, computed style, axe rule id — and the sha>
- Register: not previously filed | EZ-1234 (addendum: …)
```

### X-001 A student who types a solution and clicks anything in the sidebar loses the work silently
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student), light, 1440×900, et
- Norm: platform — destructive navigation must be guarded (source 5); and the app's own
  `beforeunload`/`Cancel` guards on the library editor's equivalent state (source 2)
- Class: journey
- Severity: **critical** — the plan's ladder puts unsaved work discarded without warning here
- Reach: every student, every exercise, every session
- Verdict: CONFIRMED
- What happens: a student types a solution, then clicks "Minu kursused" in the sidebar to check
  another exercise. No confirmation appears. On returning to the exercise, the editor is back to its
  placeholder and the work is gone. There is no draft, no undo, no recovery.
- Evidence: `j1-student-core-loop.mjs` at `bf673235`. Typed 29 characters, waited 1.2 s (longer than
  any plausible autosave debounce), navigated via the sidebar link, returned. Draft endpoint calls
  during the whole episode: `[]` — not one. Warning shown: none. Editor content on return:
  `"Kirjuta, kopeeri või lohista lahendus siia..."`, i.e. the placeholder. Shots
  `j1-07-left-the-page.png`, `j1-08-returned.png`
- Register: **EZ-1758** (drafts never saved — the endpoints and hooks exist, nothing calls them).
  Confirmed still true: `useDraft`/`useSaveDraft` exist in `api/exercises.ts:67,110` and
  `grep -rn "useDraft\|useSaveDraft" web/src --include="*.tsx"` returns **nothing**.
  **Addendum worth adding to EZ-1758:** it is currently written as a missing-wiring issue. The
  user-facing fact is data loss on an ordinary navigation, with no warning — which is a different
  severity from "a feature is not connected yet", and is the reason it should not wait

### X-002 The code editor — the student's primary input — has no accessible name
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student), both viewports tested, et
- Norm: WCAG 2.1 AA, `aria-input-field-name` (source 5)
- Class: a11y
- Severity: high
- Reach: every CodeMirror instance in the app — `CodeEditor.tsx` is shared by the student editor, the
  teacher testing tab, the TSL JSON tab and the constructor-code fields
- Verdict: CONFIRMED
- What happens: `.cm-content` is the editable surface a student writes their program into. axe reports
  `aria-input-field-name`: "aria-label attribute does not exist or is empty". A screen-reader user
  tabbing into it is told nothing about what it is.
- Evidence: `j1-measure.mjs` at `bf673235`. **This is a gate-level violation** — it is in `GATE_TAGS`,
  so it would fail CI if this route were wired to the `a11y` fixture. It is not: the fixture is used by
  `courses-page` and `grade-table` only, 2 of 40 specs. Detector proven live by canary in the same run
- Register: not previously filed. EZ-1636 is adjacent but is about aria in rendered Markdown

### X-003 The back arrow beside the exercise title is a link that announces nothing
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student), both viewports, et
- Norm: WCAG 2.1 AA, `link-name` (source 5)
- Class: a11y
- Severity: medium
- Reach: the `ArrowBackOutlined` icon-button pattern used for back-navigation on the pages that have no
  breadcrumbs — S6 should count the sites
- Verdict: CONFIRMED
- What happens: the icon button linking back to the exercise list is in tab order and has no accessible
  text, so it is announced as an unlabelled link. It is also the only way back other than the sidebar.
- Evidence: `j1-measure.mjs` at `bf673235` — `link-name` on
  `.MuiIconButton-root[href$="exercises"]`, "Element is in tab order and does not have accessible
  text". Also a **gate-level** violation
- Register: not previously filed
- **Sweep update (C5, `79248877`):** this is a pattern, not one button. Across 23 surfaces × both
  themes, `link-name` fires on **5 routes** under two fingerprints — the back arrow on
  `exercise-student` and `exercise-teacher`, and a second unnamed icon-link on
  `exercise-list-student`, `exercise-list-teacher` and `similarity` — and `button-name` fires on
  `account` for an icon *button* with no inner text. Six routes, one decision: icon-only controls are
  being shipped without accessible names. All six are gate-level, all six appear in both themes.
  Fixing the shared pattern (an `aria-label` wherever an `IconButton` has no text child) closes all of
  them; EZ-1759's icon audit should inherit this, since a control that needs an accessible name
  usually needs a tooltip too and that is the same edit

### X-004 One palette decision puts ten contrast failures on the student's main page, including the primary button
- Unit: J1 (the decision belongs to S1/V1; filed here because this is where it was measured)
- Surface: `/courses/:c/exercises/:ce` (student), light, 1440×900 and 2560×1440, et
- Norm: WCAG 2.1 AA 4.5:1 for normal text (source 5); and the theme's own claim to be a semantic
  palette (source 6)
- Class: a11y + design
- Severity: high
- Reach: app-wide. `primary.main` has 26 use sites and `text.secondary` **177** — the most-used token
  in the app
- Verdict: CONFIRMED
- What happens: ten `color-contrast` findings on one page, and they are three decisions, not ten bugs:
  - **`#ffffff` on `#16a34a` = 3.29:1** on `.MuiButton-root` (14px) and `.MuiChip-label` (13px). This
    is `primary.contrastText` on `primary.main` — the "Esita ja kontrolli" submit button and the role
    chip. The single most important button in the application fails AA.
  - **`#16a34a` on `#f5f5f5` = 3.02:1** at 21.6px normal weight — green text on the app background.
  - **`#757575` on `#f5f5f5` = 4.22:1** at 12px — `text.secondary`, on the footer links and several
    captions. Plus one derived `#989898` at **2.64:1**.
- Evidence: `j1-measure.mjs` at `bf673235`, ratios from axe's own `color-contrast` output, identical at
  both viewports. **CI can never report this**: `a11y.mjs` excludes `color-contrast` from the gate by
  design ("a design call rather than a deploy blocker"). It is exactly the design call this programme
  exists to make. Planning estimated the button at ~3.1:1 from the hex values; measured 3.29:1
- Instead: three token changes rather than ten patches. `primary.main` needs to darken to about
  `#15803d` (already in the ramp as `GREEN[700]`) for white text to reach 4.5:1, or `contrastText`
  stops being white; green-on-background text needs the darker ramp step; `text.secondary` needs to be
  darker than `#757575` wherever it is used below 14px, which given 177 sites argues for fixing the
  token rather than the sites. Each is one line in `theme.ts`
- Register: not previously filed. EZ-1414 (dark theme) was closed during this session as the feature
  request it was, explicitly not as a statement that the palette is good — this is that debt
- **Sweep update (C5, `79248877`):** measured across 23 surfaces × both themes — **29 distinct
  contrast fingerprints**, and they collapse to a handful of decisions. By route-instances:
  | ratio | foreground on background | instances | what it is |
  |---|---|---|---|
  | **4.22:1** | `#757575` on `#f5f5f5` | **49** | `text.secondary` at 12px and below. The single widest defect in the app |
  | 2.52:1 | `#515451` on `#0a0f0a` | 19 | `LandingPage`'s private dark palette |
  | **3.29:1** | `#ffffff` on `#16a34a` | 18 | `primary.contrastText` on `primary.main` — contained buttons and primary chips |
  | 2.57:1 | `#505350` on `#050905` | 17 | `LandingPage` again |
  | **3.29:1** | `#16a34a` on `#ffffff` | 15 | brand green as text/links on paper, incl. selected tabs |
  | 3.91:1 | `#e0e0e0` on `#6d6d6d` | 8 | **dark mode only** — `pre` and `code` blocks |
  | 3.02:1 | `#16a34a` on `#f5f5f5` | 7 | brand green as text on the app background |
  | 2.67:1 | `#9e9e9e` on `#ffffff` | 1 | a derived grey, not a theme value |
  Three observations that change the fix. The worst offender by reach is not the brand colour but
  **`text.secondary`**, and it is the most-used token in the app. **`LandingPage` contributes 36
  instances from a palette that is not the theme's at all**, so it needs its own pass (V2), not a
  token change. And the `pre`/`code` pair is the only decision that is **dark-specific**, which makes
  it the one place S2 rather than S1 owns

### X-005 The student's sidebar exercise list is keyed on the wrong element
- Unit: J1
- Surface: `AppLayout` sidebar, student inside a course
- Norm: React list reconciliation; and the app's own correct usage two maps earlier
  (`AppLayout.tsx:331` puts `key` on the mapped `Chip`) — source 2
- Class: consistency
- Severity: medium
- Reach: one list, but it is the student's primary navigation between exercises
- Verdict: CONFIRMED
- What happens: `AppLayout.tsx:511` maps exercises to `<ListItem disablePadding>` with **no key**, and
  puts `key={ex.id}` on the `ListItemButton` **inside** it. React therefore has no identity for the
  rows: it logs "Each child in a list should have a unique key prop … passed a child from AppLayout"
  on every render, and when the list updates — a status icon changing from unstarted to completed
  after a submission — it may reuse the wrong node, attaching the `selected` highlight or the status
  icon to the wrong exercise.
- Evidence: source at `bf673235`, plus the console error captured on every page load by
  `j1-student-core-loop.mjs` and `j1-reveal-timing.mjs`. This map runs **only** for a student inside a
  course, which is why 40 browser specs never saw it
- Register: not previously filed. Adjacent to EZ-1722 only in that both are React-hygiene debt

### X-006 The autograde reveal ignores prefers-reduced-motion
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student) after submitting, light, 1440×900, et
- Norm: WCAG 2.1 `prefers-reduced-motion`; and the app's own handling of it in `JoinCard` and
  `RobotFace`, which do respect it (source 2)
- Class: a11y
- Severity: medium
- Reach: every student submission
- Verdict: CONFIRMED
- What happens: `AutogradeAnimation` (493 lines) and `AutoTestResults`' typewriter reveal run at full
  length whether or not the viewer has asked their OS for reduced motion. A student who has made that
  request still sits through the same animated reveal.
- Evidence: `j1-reveal-timing.mjs` at `bf673235`, run twice — once default, once with Playwright's
  `reducedMotion: 'reduce'`, which is the same signal the two components that *do* honour it read.
  Time from the grader answering to the grade appearing: **1535 ms default vs 1531 ms reduced**. Time
  to the last test title finishing: **4291 ms vs 4328 ms**. The preference has no effect, and the
  near-identical numbers are the control that makes this a measurement rather than an impression
- Register: not previously filed

### X-007 After the grader has answered, the result takes another 4.3 seconds to become readable
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student) after submitting, 1440×900, et
- Norm: design judgement, argued (source 6) — the cost of a deliberate flourish on the application's
  highest-frequency action
- Class: design
- Severity: medium
- Reach: every submission by every student — the most frequent single action in the product
- Verdict: CONFIRMED (the numbers), and the *judgement* is offered as a design call, not a defect
- What happens: grading finishes server-side, and the student then waits **1.5 s for the grade** and
  **4.3 s for the full test list**, because titles are typed out character by character with a 300 ms
  pause before each status icon and 350 ms between tests. The first J1 screenshot caught it mid-word:
  "Programm küsib kaks" of "Programm küsib kaks arvu", with no grade anywhere on screen 2.5 s after
  the grader had already answered. A student iterating on an exercise pays this on every attempt.
- Instead: the animation is genuinely good the first time and the argument is not to delete it. Reveal
  the grade immediately and animate the *detail* underneath it; or animate in full only on a student's
  first submission to a given exercise and go straight to the result afterwards; or let the reveal be
  interruptible by a click, which costs one handler. Any of the three keeps the moment and stops
  charging for it repeatedly. Note this also gates X-006: with the grade shown immediately, the
  reduced-motion question stops being about waiting
- Evidence: `j1-reveal-timing.mjs` at `bf673235`; the reveal constants are `STATUS_PAUSE = 300` and
  `NEXT_TEST_PAUSE = 350` in `AutoTestResults.tsx:93-94`. Shots `j1-06-graded-result.png` (mid-reveal),
  `j1-reveal-default-settled.png`
- Register: not previously filed

### X-008 The code editor is a fixed 200 px tall on every screen size
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student), 1440×900 and 2560×1440, et
- Norm: the primary work surface should use the space available (source 6); and EZ-1527's premise
- Class: responsive + design
- Severity: medium
- Reach: every student writing a solution, plus every other `CodeEditor` consumer if the height is
  shared
- Verdict: CONFIRMED
- What happens: the editor measures **627×200 px at 1440×900** and **639×200 px at 2560×1440**. The
  height does not change at all and the width gains 12 px, because `AppLayout` caps content at
  `maxWidth="lg"`. 200 px is roughly eight lines of Python. On a 2560×1440 monitor the page uses 56% of
  the available height and under half the width, while the student writes their program through a
  letterbox and scrolls inside it.
- Instead: the editor should take the height the viewport offers — it is the one element on the page
  whose content is unbounded. A `flex: 1` editor inside a full-height right pane, with a sensible
  minimum, is the shape the split-pane already implies; the collapse controls next to it exist to give
  the editor more *width*, which suggests the intent was there
- Evidence: `j1-measure.mjs` at `bf673235`, `getBoundingClientRect()` on `.cm-editor` at both
  viewports. Shots `j1-03-exercise-unstarted.png`, `j1-06-graded-result.png`
- Register: **EZ-1527** ("Optimise for large screens", open since 2022) is the general form. This is
  the specific instance on the most-used page, and the fixed height means it is not only a
  large-screen problem

### X-009 A render error anywhere in a route shows a developer's error page, not the CrashScreen the app ships
- Unit: C5 (found), C2 (owns the pattern)
- Surface: any route, both themes; observed on `/library/exercise/:id`, `/about` (admin),
  `/courses/:c/participants`, `/library/dir/root`
- Norm: the app's own design — `CrashScreen.tsx` (57 lines) exists, is translated, and offers a
  one-click bug report (source 2)
- Class: journey
- Severity: high
- Reach: every route in the application
- Verdict: CONFIRMED
- What happens: when a component throws during render, the user gets React Router's **default** error
  boundary: the heading "Unexpected Application Error!", the raw exception message
  (`TypeError: Cannot read properties of undefined (reading 'version')`) and, in a dev build, a stack
  trace. Untranslated, in an app whose default language is Estonian. **The whole shell disappears** —
  measured `sidebar present: false` — so there is no nav, no way back, and no route to the bug-report
  dialog. The app's own `CrashScreen` never renders: measured `app CrashScreen: false`,
  `router default boundary: true`.
- Not the obvious cause: `App.tsx:28` deliberately mounts `ErrorBoundary` *outside* `RouterProvider`,
  with a comment saying it is there "so a throw in the router or a layout is caught at all". That
  reasoning is sound and the placement is not the defect. The defect is that **`routes.tsx` defines no
  `errorElement` anywhere** (`grep -c errorElement web/src/routes/routes.tsx` → 0), so React Router
  catches route render errors first and handles them with its own default. The app's boundary only
  ever sees throws that escape the router entirely.
- Instead: an `errorElement` on the layout route rendering `CrashScreen`, which keeps the deliberate
  outer boundary as the last resort it was written to be. One route-config line, and `CrashScreen`
  already exists
- Evidence: `c5-verify-main-landmark.mjs` at `79248877`, with two controls — `/courses` as teacher
  renders normally (`main` 1, sidebar true, no boundary) and `/landing` renders normally outside the
  shell. Shots `c5-mainlandmark-*.png`. Note the crashes themselves were caused by this unit's own
  thin fixtures and are **not** the finding; see R-005
- Register: not previously filed

### X-010 The student's exercise list is a `<ul>` with `<a>` elements as direct children
- Unit: C5
- Surface: `/courses/:c/exercises` (student), both themes
- Norm: WCAG 2.1 AA, axe `list` (source 5)
- Class: a11y
- Severity: medium
- Reach: one list, on a route every student passes through to reach any exercise
- Verdict: CONFIRMED
- What happens: axe reports `list` on `main > ul` — "List element has direct children that are not
  allowed: a". A `<ul>` may only contain `<li>` (plus script/template), so assistive technology is
  given a list whose items are not items; the count and position announcements are unreliable.
- Evidence: `c5-a11y-sweep.mjs` at `79248877`, gate-level, both themes. Exactly the class
  `doc/web/browser-testing.md` warns about — invalid HTML that only announces itself to a checker
- Register: not previously filed

### X-011 The landing page and the embed page have no `main` landmark
- Unit: C5
- Surface: `/landing` and `/embed/exercises/:id`, both themes
- Norm: `a11y.mjs`'s own `checkMainLandmark`, which the project wrote because "without it there is
  nothing to skip to" (source 1)
- Class: a11y
- Severity: medium for `/landing`, low for `/embed`
- Reach: two routes, but `/landing` is the first page an unauthenticated visitor sees
- Verdict: CONFIRMED
- What happens: both render outside `AppLayout`, which is the only place in the app that provides
  `<Container component="main">`. So the two routes that do not use the shell have no landmark, and a
  screen-reader user on the marketing page has nothing to skip the navbar with. For `/embed` the case
  is weaker — it is an iframe fragment by design — but it costs one prop either way.
- Evidence: `c5-a11y-sweep.mjs` and `c5-verify-main-landmark.mjs` at `79248877`; `/landing` measured
  `main elements: 0` with no error boundary and the page rendering correctly, which is what separates
  it from the four refuted routes in R-005
- Register: not previously filed

---

## Refuted

One line each, so a later session does not re-find them. This appendix is what makes the programme
converge.

`R-001` — **The four `*.light` palette tints and the whole `secondary` triplet are near-white in dark
mode.** True, and they have **zero uses in `src/`** at `0cf2d952`; `secondary.main` survives only as the
default env-badge colour in `public/config.json`. So there is no unreadable chip anywhere. What survives
as a real finding for S1 is narrower and different: six dead, mode-blind palette entries that will
mislead the next person who reaches for one. Recorded here before the programme starts, because it was
the planning pass's first candidate and it is the cautionary tale the plan's ground rules cite.

`R-002` — **The three white plates behind the sponsor logos on `/about` are a dark-mode bug.** They
are `bgcolor: 'white'` literals (`AboutPage.tsx:54,57,60`) and they do render as three white blocks on
a `rgb(18,18,18)` page, which is how the instrument was proven. But the logos they hold are black-ink
Estonian government marks with transparent backgrounds; a white plate is a deliberate and *correct*
accommodation, and removing it would make three sponsor logos invisible in dark mode. A designer might
prefer a softer neutral or a hairline border — that is V1's business, not a defect.

`R-003` — **The student exercise page wastes half the screen.** The impression came from reading a
`fullPage` screenshot as though it were the viewport. Measured, content reaches 807 px of a 900 px
laptop viewport — **90%**. What survives from the impression is narrower and real, and is filed as
X-008: the editor's *fixed 200 px height*, and the genuine 2560×1440 case where the same content
reaches only 56%.

`R-004` — **`GET /v2/versions` can return a null `core` and crashes the route.** It cannot: `core` is
non-nullable in both halves of the contract — `val core: ComponentResp` at
`core/ems/service/versions.kt:106` and `core: ComponentVersion` at `web/src/api/versions.ts:66`. The
crash was an invalid fixture written from memory during this programme's own setup, which makes it the
sixth such fixture after the five EZ-1766 found. Read the contract before asserting a shape.

`R-005` — **`main` landmark missing on `/courses/:c/participants`, `/library/dir/root`,
`/library/exercise/:id` and `/about` (admin).** The C5 sweep reported it on all four and it is wrong on
all four: those pages **crashed**, so there was no page to have a landmark. `AppLayout` does provide
`<Container component="main">` and the control proves it — `/courses` as teacher measures `main: 1`.
The crashes were caused by this unit's own `superset()` fallback handing pages a shape the contract
says they will never receive (`versions: []` leaves `core` undefined; something on the library exercise
page called `.trim()` on an absent field). Pages are entitled to trust a non-nullable contract, so
"these pages crash on malformed data" is **not** a finding either. What *is* a finding is what the
crash revealed about the boundary that caught it — filed as X-009. Two of the six original routes
survive as X-011, and the difference between them is exactly the control: `/landing` has no `main` while
rendering perfectly.

---

## The design direction

Filled by the V track, and read as one proposal rather than a list of tickets: palette, type scale,
density, motion, and which components should change shape — ordered by reach, for kspar to accept, amend
or reject as a whole. What survives becomes `doc/web/ui-guide.md`'s token section; **what is rejected is
recorded here as considered-and-rejected**, so a later audit does not re-propose it.

*(empty)*

---

## Leads for the string and icon audits

Collected in passing, not chased. See the plan's own section for why these belong to EZ-1785 and
EZ-1759 rather than here.

### For EZ-1785 (strings)

- Measured at `0cf2d952`: **858 keys, exact parity both ways** (0 missing in either language), and
  **137 where Estonian is ≥30% longer** than English. Worst offender
  `tsl.containsName.KEYWORD_WITH_PRECEDING_ARG` at **2.42×**, inside the densest UI in the app.
- The app defaults to Estonian and every browser spec runs in English, so the default language is the
  untested one.
- The 149 `tsl.*` keys are the *entire* user-facing documentation of TSL — there is no teacher-facing
  guide anywhere — so they carry more weight than labels usually do.

### For EZ-1759 (icons)

- Measured at `0cf2d952`: **222 icon bindings, 105 distinct, 94.6% outlined**, with ten filled
  (`CheckCircle` ×4, `RadioButtonUnchecked` ×2, `Cancel`, `ExpandMore`, `GitHub`, `Menu`). The
  documented outlined-by-default rule is holding, so the quantitative half is done.
- What is left is qualitative: one concept drawn with two icons, one icon meaning two things, icon-only
  controls whose meaning is not guessable, and the two bespoke `SvgIcon`s in `components/icons.tsx`
  against the MUI set. EZ-1584 sits exactly here.
- **From J1**, three icon-only controls on the student's most-used page whose meaning is not guessable
  from the glyph: the robot mark beside the exercise title (presumably "auto-graded", never stated),
  the two `|<` / `>|` split-pane collapse glyphs in the gutter, and the kebab on the `lahendus.py`
  editor header. Two of them are also the subjects of a11y findings X-002 and X-003, so an accessible
  name and a tooltip would be the same edit.

### Shared

`general.somethingWentWrong` is the error copy in 15–19 files while `api/client.ts` parses the
`errorBody.code` and `attrs` core populates deliberately and nothing renders them. That is a copy
problem, a UX problem and an icon problem at once; whichever audit reaches it first should say so rather
than each filing a third of it.
