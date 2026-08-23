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
| `npm run lint` | *(not yet run)* |
| `npm run test:unit` | *(not yet run)* |
| `npm run test:browser` | *(not yet run)* |
| Pre-existing failures | *(not yet established)* |
| Driver harness proven | *(no — see plan, "Prove the instrument")* |
| a11y sweep proven able to fire | *(no — `a11y-baseline.json` is empty, which is ambiguous until a known violation is fed to it)* |
| `easy-kc-theme` cloned | *(no — S9)* |
| `:8080` compile relay authorised | **kspar has approved it in principle and asked to be told when it is needed.** Ask before the first relay in T3/T4 |

### An operational note for every session

Unlike `doc/review-log.md`, this file is tracked, so there is no copy-out-of-the-worktree ritual: edit
it where it lives and commit. What *is* gitignored is `web/tests/screenshots/`, so any screenshot a
finding depends on gets copied into the job dir and its path recorded — a finding whose evidence has
evaporated is downgraded to `UNCERTAIN` by the next session.

Audit drivers live in `$CLAUDE_JOB_DIR/tmp/ux-audit/`, **never** in `web/tests/browser/`, which is
ratcheted three ways and would fail the build. Run the stub server on `HARNESS_PORT=5299`.

---

## Status

`todo` → `in progress <date> <sha>` → `done <sha>`. A unit is never left half-audited: either it
finishes or it goes back to `todo`.

### Track J — Journeys (9 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| J1 | Student core loop | todo | — | **Start here.** Most-travelled path in the app, zero browser coverage |
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
| S1 | The theme as a system | todo | — | Do before S2 and the whole V track |
| S2 | Dark mode, everywhere | todo | — | |
| S3 | Phone | todo | — | Do after S4 |
| S4 | Laptop — the reference | todo | — | **Do first in this track after S1**: establishes the reference the others diff against |
| S5 | Large monitor | todo | — | EZ-1527, open since 2022 |
| S6 | The shell at every size | todo | — | Re-map: EZ-1786 restructured the sidebar at `df7244af` |
| S7 | Dense data | todo | — | |
| S8 | Editors, code and motion | todo | — | Motion *mechanics* here; whether the animation should exist is V3 |
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
| C5 | Keyboard, focus and a11y coverage | todo | — | The cheapest real findings in the programme; prove the detector fires first |

### Track D — Documentation (2 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| D1 | UI/UX docs against reality | todo | — | Three stale claims already identified; see the register's "candidates to close" |
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

*(none yet)*

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

### Shared

`general.somethingWentWrong` is the error copy in 15–19 files while `api/client.ts` parses the
`errorBody.code` and `attrs` core populates deliberately and nothing renders them. That is a copy
problem, a UX problem and an icon problem at once; whichever audit reaches it first should say so rather
than each filing a third of it.
