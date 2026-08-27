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
| `:8080` compile relay | **authorised and working**, verified 2026-08-23. A core is running there with `easy.core.auth-enabled: false`, so **header auth is all that is needed** — no dev token. The recipe, since it cost a few minutes to find: without headers every path returns **401**, *including* `/v2/unauth/versions`, which reads like the endpoint is down; adding the `oidc_claim_*` headers turns that into a 404, and the 401→404 flip is how you tell "the filter rejected me" from "wrong path". Compiles are read-only, so this touches nothing:<br>`curl -X POST -H 'Content-Type: application/json' -H 'oidc_claim_preferred_username: kspar' -H 'oidc_claim_email: kspar@ut.ee' -H 'oidc_claim_given_name: Test' -H 'oidc_claim_family_name: Teacher' -H 'oidc_claim_easy_role: teacher' -d '{"tsl_spec":"…","format":"JSON"}' http://localhost:8080/v2/tsl/compile` |

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
| J1 | Student core loop | **done `1bdd895c`** (2026-08-24) | X-001…X-008, X-028, X-029; R-009, R-010 | The loop and all four edge states walked. 12 candidates, 10 kept, 2 refuted — and both refutations were my own flawed measurements, which is the useful pattern here. The app handles the *closed* state well (R-010) and confirms teacher-graded submissions properly (R-009); what it does not do is honour `TEXT_UPLOAD` at all (X-028) or say that a deadline has passed (X-029). **Scope moved:** `ActivityFeed` and `PreviousSubmissions` in depth go to **J6**, which reads the same components from the teacher's seat — auditing them twice from two seats is the same read, and J6 is where the grading conversation lives |
| J2 | Student periphery & the front door | **done `57c722fa`** (2026-08-26) | X-033 | The role-mismatch redirect is confirmed and worse than the lead said: several seconds of spinner, then a silent landing on /courses (X-033). The rest of the periphery is already accounted for — joining is covered by `join-by-link.spec.mjs` (16 checks) and J7's R-013; `/register` and `/tos` are EZ-1691/EZ-1692, filed; `checkinFailed`'s bare alert folds into C1's error-copy pattern. The S9 login-handover half stays with S9 |
| J3 | Teacher: course lifecycle | **done `57c722fa`** (2026-08-26) | X-034 | The lifecycle has a beginning and no end: creation works (CreateCourseDialog, covered by `courses-page.spec.mjs`), `EditCourseDialog` edits identifier/code/name — and archiving does not exist in the UI at all, though `archived` arrives on every course response (X-034, a gap-in-the-gap-list find). Course colours' mode-blindness was settled in S2 (they read fine on dark) |
| J4 | Teacher: exercise authoring | **done `e58849ca`** (2026-08-26) | — | 3 candidates, **0 kept** — the authoring loop is well built and well covered. Live preview exists (`useMarkdownPreview`, 400ms debounce, rendered in the left column while editing); image/file upload is implemented with paste, drop and a two-mode Image menu — **EZ-1764 was implemented on 2026-08-16 and never resolved; closed during this unit**. The genuine gaps are all filed already: EZ-1732 (math), EZ-1765 (course-exercise instructions have no editor), EZ-1757 (versions), EZ-1760/EZ-1687 (share dialog). Nothing new to add |
| J5 | Teacher: putting an exercise on a course | **done `fc5a3d32`** (2026-08-26) | X-037 | The dialogs are spec-covered (`course-exercises.spec.mjs`: settings, reorder, add-from-library, mass actions); the assessment-tab placeholder turned out implemented — **EZ-1754 closed during this unit**. The keep: soft/hard deadlines are never cross-validated, so hard-before-soft saves silently (X-037). Exceptions UI left as read-verified; nothing anomalous |
| J6 | Teacher: grading | **done `b3e0a832`** (2026-08-24) | X-030, X-031; R-011, R-012 | 5 candidates, 2 kept, 2 refuted (one lead absorbed into X-031). The flow is **better than the plan assumed**: one-click prev/next between students, a roster with "3 hindamata" / "2 / 6 hinnatud" counts (R-011), a GitHub-style `+` comment gutter, and the full conversation — inline comment, feedback, grade, teacher's name — reaching both seats (R-012). Kept: the two navigation chevrons are the only unlabelled icon buttons of nine (X-030), and grading leaves the grade table stale, executed end to end with the exact key mismatch (X-031). **Fixture notes:** stub `/submissions/all/students/{id}` or the pane renders "Esitamata"; cache tests must navigate client-side; hover affordances toggle CSS classes, not DOM nodes |
| J7 | Teacher: roster & groups | **done `a7ca430f`** (2026-08-26) | X-032; R-013 | 3 candidates, 1 kept, 1 refuted, 1 exemplary. The invite flow is the create-with-defaults-then-edit pattern done properly (R-013), and the delete-group dialog names every affected student — a model for C3. Kept: the remove-from-course confirm never says what happens to the student's work (X-032). Moodle link/sync/handover left to the five existing specs and the already-filed EZ-1768/EZ-1778; nothing new to add there. **For V3:** the page carries its 4 tabs and 33 `useState` well at this fixture size — the shape question needs S7's 200-row pass, not this one |
| J8 | Teacher: results out | **done `fc0c2141`** (2026-08-26) | — (X-031 carries the one defect) | 4 candidates, **0 new kept**. The surface is in good shape: every grade cell links to the exact student×exercise view with an accessible name and documented reasoning — **EZ-1706 was implemented and is now closed** (verified by reading + `grade-table.spec.mjs`); `toCsv` is RFC 4180-quoted, semicolon-separated for European locales, and carried by 7 unit tests; the sticky first column and `TableContainer` are the app's best table. Non-findings, recorded: the on-screen Σ column (a completion count) is absent from the CSV — derived and recomputable, no task cost; CSV formula injection is review **F-016**, already filed. The staleness defect is X-031. Similarity left to its spec's 15 checks; nothing new found |
| J9 | Admin | **done `fc5a3d32`** (2026-08-26) | — | Re-mapped post-EZ-1786: the Administration sidebar section now gathers System messages, Reported bugs, Keycloak admin and Operating info, which dissolved the plan's scattered-admin-tools premise. `SystemMessagesPage` ships as designed and is the app's only field-validated form — **EZ-1748 closed during this unit** (fifth implemented-but-unresolved issue). What an admin still cannot do from the UI is already filed: EZ-1761 (executors), EZ-1781 (grading libraries). Articles' view/edit-on-one-URL noted for V3; no new numbers |

### Track T — The TSL builder (7 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| T1 | Entry & first run | **in progress** 2026-08-23 `278d1ee1` | X-015, X-016 | The empty-`tsl.json` lead is **confirmed by execution**: `{"tsl_spec":"","format":"JSON"}` is sent unprompted and Save is disabled. **Remaining before `done`:** what the 12-item preset menu teaches a teacher who has never seen TSL (and its labels disagreeing with the type `Select`'s — "Run the program" vs "Program execution test"), EZ-1734's hardcoded container dropdown, and the read-only view of an existing TSL exercise as a non-owner. **For T6:** the builder gets ~700px of a 1440px viewport because the left half is a static preview card that does not collapse |
| T2 | The test forms | **in progress** 2026-08-24 `72b18060` | X-027 | The enforcement question is settled at all three layers: required fields are marked, not gated, and accepted by the compiler (X-027) — and the one place the builder warns properly (the class-instance "checks nothing" caption) is the model any fix should copy. Nesting comprehensibility was answered by T6's R-008: it reads correctly even at 390px. **Remaining before `done`:** a per-type read of the nine body components in `TslTestBody` against a teacher's vocabulary rather than the model's — X-024 found one label collision by accident, so a deliberate pass is likely to find more; plus `RawBody`'s unknown-type fallback and the `Veateated` error-message expander |
| T3 | Model vs compiler | **in progress** 2026-08-23 `9dbe8dd1` | X-019, X-020; R-007 | The reverse direction is done — nine specs against the real compiler establish that six capabilities work and are unreachable from any form (X-019), and that duplicate ids compile (X-020). R-007 is the near-miss worth reading. **Remaining before `done`:** the forward direction beyond what `library-exercise-tsl-live.spec.mjs` already covers — fields the UI *emits* that the compiler ignores (`definitionCheckValue`, `class_instance_test.className`, the forced `containsWhatArg: 'import'`), and the validation tiivad performs at grading time that neither side surfaces. Leads in hand — the compiler emits its own Estonian default test name into the generated script while the UI derives its own from `testDefaultName`, so one string has two sources; and `emptySpec()` hardcodes `requiredFiles: ['lahendus.py']` rather than reading `solution_file_name` (see X-015) |
| T4 | The feedback loop | **in progress** 2026-08-24 `c9cb3cdb` | X-018, X-023, X-024 | The unit's central question is answered, and the answer is no: nothing reports that a test cannot fail, and the first preset in the menu produces exactly that (X-023). Error messages are kotlinx diagnostics verbatim (X-018). Two menu items are byte-identical in Estonian (X-024). **Remaining before `done`:** the `Generated scripts` tab judged as a preview in its own right — it shows Python, and whether any teacher can read it is the question — and the Testimine round trip end to end, which needs a real grading run and therefore a **write** to core, so ask before doing it. Note the compiler reports `backend_version: "?.?.?"`, which the UI shows verbatim in its synthetic `meta.txt` |
| T5 | State, persistence, escape | **done `fbd7a43e`** (2026-08-24) | X-017, X-021, X-022 | 4 candidates, 4 kept, and every one settled by execution with a control. The router guard is critical (X-017); destructive edits are unconfirmed and unrecoverable (X-021); an invalid spec locks Save even after the exercise stops being TSL (X-022). Two method notes for later units: observe the spec through the **debounced compile payload**, not the JSON tab — the payload is the app's own serialisation and survives the card collapsing; and `{dirs: [], exercises: []}` is **not** `LibraryDirResp`'s shape and crashes `ExerciseLibraryPage.tsx:106`, so look the response up before stubbing the library list |
| T6 | TSL under pressure | **done `bf48e09b`** (2026-08-24) | X-025; R-008 | 2 candidates, 1 kept, 1 refuted — and the refutation is the useful half. The builder does **not** break at 390px in dark in Estonian: zero horizontal overflow across 390/1440/2560 × light/dark, and the app's longest Estonian string renders in full. What is real is scale: 2200px of page height at 1440 **and** at 2560, so extra width buys nothing (X-025). Mild lead onward: 4–5 elements per viewport clip their own content — **S3**/**S8** |
| T7 | The other end | **done `29271a61`** (2026-08-24) | X-026 | 4 failure shapes driven through the student view; 1 finding. The designed path is **good** — an ordinary `OK_V3` FAIL gives per-test accordions, a clear grade and an actionable "Ootasin väljundis stringi …, aga seda ei leidnud", and a student's own traceback is shown in full under "Erind", which is correct and must survive any fix. The finding is the two *infrastructure* failure shapes being rendered as assessment results (X-026). `TeacherFeedback`'s rendering of written teacher feedback was not covered here and belongs to **J1**/**J6**, which read it from both seats |

### Track S — Surfaces (9 units)

| # | Unit | Status | Findings | Notes / inherited leads |
|---|---|---|---|---|
| S1 | The theme as a system | **done `278d1ee1`** (2026-08-23) | X-012, X-013, X-014; R-006 | 4 candidates, 3 kept, 1 refuted. The colour half is the substance: the palette's mode-invariance is a **structural** defect rather than a bad value (X-012), and `text.secondary` fails AA only on `background.default`, which is why 177 sites survived it (X-013). The non-colour half is mostly good news — the radius vocabulary measured coherent (R-006) and the real finding is dead weight rather than incoherence (X-014). Arithmetic in `tests/audit/reports/s1-token-contrast.json`. Original note follows. **From J1:** X-004 already measured the three contrast decisions with real ratios (`#fff` on `#16a34a` = 3.29:1, `#16a34a` on `#f5f5f5` = 3.02:1, `#757575` on `#f5f5f5` = 4.22:1 at 12px). S1 owns the token-level fix and should check whether `GREEN[700]` as `primary.main` breaks anything else. Also settle the derived `#989898` at 2.64:1, which is not a theme value |
| S2 | Dark mode, everywhere | **done `b039eb07`** (2026-08-26) | — ; R-015, and one correction to X-004 | Contrast data was already collected in dark by C5; the visual pass read the named risk surfaces off PNGs, and dark mode is **good**: the course-colour edge bars and activity dots read cleanly, `oneDark` + the green banner + accordions are coherent on the exercise page, JoinCard/RobotFace survive (the `backgroundColor:'white'` never manifests at card scale), and the sponsor plates stand as deliberate (R-002). The one 'dark-only defect' in X-004's table — `pre`/`code` at 3.91:1 — **dissolved on inspection**: its four routes are exactly R-005's crashed pages, so it was React Router's error-page stack trace, not app content (struck in X-004, R-015). Dark mode's real debts remain the mode-blind palette (X-012) and the theme's dead entries (X-014) |
| S3 | Phone | **done `a2588971`** (2026-08-26) | — ; R-014 | All 23 surfaces at 390×844: **zero horizontal overflow on every surface that renders** (R-014 — the predicted table overflow refutes; participants' 479px table scrolls in its own container). Clipping counts are small and benign (join-card 3, account 2, similarity 1). What remains for phones is not containment but the fixed-height/single-column class already filed as X-008/X-025, and interaction-level checks (tap targets, dialogs at 390) which fold into C5's keyboard pass |
| S4 | Laptop — the reference | **done `a2588971`** (2026-08-26) | — | The reference established by the C5 sweep (23 surfaces × 2 themes, laptop) plus this sweep's laptop pass: no overflow, no crashes outside R-005's fixture-induced ones, `main` at its 1180–1200px cap everywhere. Everything wrong at every size was filed from the units that found it |
| S5 | Large monitor | **done `a2588971`** (2026-08-26) | — (pattern already filed) | Measured per-surface at 2560×1440: **every in-shell surface renders `main` at exactly 1200px — 47% of the viewport** — with the fixed 260px drawer beside it. No surface uses `xl`. The pattern finding already exists as X-008 (editor fixed at 200px) + X-025 (TSL builder identical height at 1440 and 2560) under EZ-1527; per the file-at-the-decision rule this unit adds the per-surface evidence table (`reports/s345-viewport-sweep.json`) rather than a third instance. The surfaces that most want the room, for the eventual fix: grades, participants, library, grading split pane, TSL builder |
| S6 | The shell at every size | **done `6edd169d`** (2026-08-26) | X-038; reach data added to X-003 | Re-mapped post-EZ-1786 (the Administration section resolved the scattered-admin premise — see J9). Measured: 17 `ArrowBackOutlined` sites, 1 labelled, with the i18n key already existing (folded into X-003). New: the theme toggle permanently forfeits follow-the-OS, while the embed page does it right (X-038). X-005 (key on wrong element) and EZ-1789 (no hrefs) already carry the shell's other defects; the mobile drawer behaved in every sweep. Original note follows. **From J1:** X-005 (the exercise-list `key` on the wrong element) lives here at `AppLayout.tsx:511`, and two maps earlier the same file does it correctly — count the other lists. Also count the `ArrowBackOutlined` back-link sites for X-003's reach: on pages with no breadcrumbs it is the only way back, and it has no accessible name |
| S7 | Dense data | **done `6edd169d`** (2026-08-26) | — | Absorbed by its neighbours: containment at 390 is clean app-wide (R-014), the grade table is the app's best table (J8), sorting follows the documented `TableSortLabel` convention (review E5 verified it), and the roster carries count chips (R-011). The one thing not driven is a **200-row roster's render performance**, which is a performance question the plan scoped out (`doc/testing.md`: "Performance tests — none, and mostly fine"). Nothing filed |
| S8 | Editors, code and motion | **done `6edd169d`** (2026-08-26) | — (all previously filed) | Everything this unit scoped was measured by earlier units: X-002 (editor unlabelled, shared `CodeEditor`), X-008 (fixed 200px), X-006 (reduced-motion ignored on the reveal), R-015 (oneDark coherent in dark), R-008 (TSL editors contained at 390). The overflow-guard gap on 12 of 15 `dangerouslySetInnerHTML` sites stays a **watch item**: no measured overflow reached it in any sweep because fixture HTML is narrow — a real exercise with a wide table would be the test, and that is one fixture away if it ever matters. Original note follows. **From J1:** X-002 (no accessible name on `.cm-content`) is in the shared `CodeEditor.tsx`, so it reaches the teacher testing tab, the TSL JSON tab and every constructor-code field — check whether one `aria-label` prop fixes all of them. X-008 is the fixed 200 px height. Two unlabelled glyph controls (`|<` and `>|`) sit in the split-pane gutter with no button chrome and no visible affordance — establish what they do and whether anyone would find them |
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
| C1 | Forms, validation and error copy | **done `e58849ca`** (2026-08-26) | X-035 | The pattern finding, as planned: 19 files render the same sentence while the typed error envelope is read in exactly 2 (X-035). The per-form worst cases were measured by their own units — X-018 (raw kotlinx), X-027 (required-not-enforced), and `SystemMessagesPage`'s inline dialog remains the only field-validated form. One shared `errorMessage(err)` is the proposed shape |
| C2 | Loading, empty and error states | **done `e58849ca`** (2026-08-26) | X-036 | Empty states are the finding (X-036): a designed one exists and is used once, `/courses` renders literally nothing, and no empty state names the next action. Loading is adequate (31 spinners, 1 skeleton — a D2 style-guide line, not a defect). The error half was already filed: X-009 (unreachable CrashScreen) and X-035 (one sentence for everything) |
| C3 | Destructive actions and confirmation | **done `e58849ca`** (2026-08-26) | — (instances already filed) | The sweep's instances were all found by their units, and per the file-at-the-decision rule they stay there: X-021 (TSL delete/type-switch, unconfirmed + no undo), X-032 (remove-from-course confirms but hides consequences), X-017/X-001 (unguarded navigation), `window.confirm`/`window.prompt` in `AutoAssessTab` (T7 note). The **models to copy** are as important as the defects: the delete-group dialog (names every affected student), the Cancel guard, and R-010's closed-exercise handling. The structural cause is one line: `ConfirmDialog` lives under `features/participants/` and nothing else can reach it — moving it to `components/` is the enabler for every fix above |
| C4 | Feedback after a mutation | **done `e58849ca`** (2026-08-26) | — (X-031 is the finding) | Traced through the units rather than as a separate sweep: successes are mostly confirmed ("Lahendus esitatud", "Kutselink loodud", "Exercise saved" snackbars — R-009, R-013), failures are the C1 problem (X-035), and the one place success *lies* is X-031's stale grade table. No separate finding earns its number |
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
| EZ-1764 | Feature | Upload images from the editor — **closed during J4**: implemented `336a5eca` (2026-08-16), verified via `markdown-upload.spec.mjs`; the issue had simply stayed Open |
| EZ-1765 | Feature | Course exercise instructions cannot be edited in the web app, only rendered |
| EZ-1757 | Feature | List, view and diff older versions of a library exercise |
| EZ-1702 | Bug | Verify adoc → Markdown conversion of existing exercise texts |
| EZ-1760 | Feature | Mark people you just shared with in the library share dialog |
| EZ-1687 | Usability | Share dialog: autocomplete for teacher emails |

### Course exercise and assessment
| id | Type | Summary |
|---|---|---|
| EZ-1754 | Bug | Assessment tab placeholder — **closed during J5**: renders read-only `AutoAssessTab` since before `df7244af`, spec-covered |
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
| EZ-1706 | Usability | Grade table: link grade cells to the student's submission — **closed during J8**: implemented at `GradeTablePage.tsx:385`, verified by reading and by `grade-table.spec.mjs` |
| EZ-1767 | Bug | Grade table — **verify state**: commit `785a8cc7` reads as a fix ("stops losing students, and learns to sort the same way twice") |

### Admin
| id | Type | Summary |
|---|---|---|
| EZ-1761 | Feature | Admin UI for executors: loads, and container image assignments |
| EZ-1781 | Feature | Let non-core-devs update grading library versions, with rollback, and show the versions |
| EZ-1748 | Feature | Scheduled system messages — **closed during J9**: implemented as designed, two specs cover it |
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
- **Reach measured (S6, `6edd169d`):** 17 `ArrowBackOutlined` sites across 8 files; exactly **one**
  carries an `aria-label` (`GradeTablePage.tsx:172`, using `general.back` — so the i18n key already
  exists and the fix at the other 16 sites is mechanical).
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
  | ~~3.91:1~~ | ~~`#e0e0e0` on `#6d6d6d`~~ | ~~8~~ | **struck during S2**: its 4 routes are exactly R-005's crashed pages — this is React Router's *error page* stack-trace styling, not app content. Dissolves into X-009 |
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

### X-012 The palette cannot be fixed as it stands: no single brand green passes AA in both themes
- Unit: S1
- Surface: app-wide, both themes
- Norm: WCAG 2.1 AA (source 5); and the theme's own claim to be a two-mode palette (source 6)
- Class: design + a11y
- Severity: high
- Reach: `primary.main` has 26 direct use sites and reaches every contained button, chip, tab
  indicator, link and active nav item in the application
- Verdict: CONFIRMED
- What happens: `createAppTheme(mode)` switches only `background`, `text` and `divider`. `primary` is
  the same value in both themes — and the arithmetic says that cannot work, because light and dark
  want the green to move in opposite directions:

  | pairing | current `GREEN[600]` `#16a34a` | `GREEN[700]` `#15803d` |
  |---|---|---|
  | white text on it (buttons, chips) | **3.30:1** fail | **5.02:1** AA |
  | as text on light default `#f5f5f5` | **3.02:1** fail | **4.60:1** AA |
  | as text on dark default `#121212` | 5.68:1 AA | **3.74:1** fail |
  | as text on dark paper `#1e1e1e` | 5.06:1 AA | **3.32:1** fail |

  So darkening one step fixes every light-mode failure and introduces two dark-mode ones. Lightening
  does the reverse. **The defect is not the value, it is that the value is shared.** This is the same
  root cause as the four dead `*.light` tints from R-001: the palette was written as if only the
  background changes between modes.
- Instead: make `primary` mode-aware, which is one ternary in `theme.ts` beside the three that are
  already there. A pairing that clears AA on every axis, using steps already in the ramp:
  `primary.main = GREEN[700]` in light with `contrastText: '#fff'` (5.02:1), and
  `GREEN[400]` `#4ade80` in dark with `contrastText` = `background.default` (**10.75:1 both as text on
  the dark background and as dark text on the green fill**). Note the second half of that: a dark-mode
  primary button wants *dark* text, so `contrastText` has to stop being the hardcoded `#fff` it is
  today — which is the reason this is a palette change rather than a colour swap. Whether the brand
  will accept two greens is kspar's call; the alternative is accepting AA failures in one mode.
- Evidence: `tests/audit/s1-token-contrast.mjs`, report
  `tests/audit/reports/s1-token-contrast.json`, at `1bfdaf9d`. Pure WCAG arithmetic over the values in
  `theme.ts`, cross-checked against axe's own measurements in C5's sweep, which agreed to 0.01
- Register: not previously filed. Supersedes the "just darken the green" reading of X-004

### X-013 `text.secondary` fails AA only on the app background, which is where most of it sits
- Unit: S1
- Surface: app-wide, light theme
- Norm: WCAG 2.1 AA (source 5)
- Class: a11y
- Severity: high
- Reach: **177 use sites — the most-used token in the codebase**, and 49 route-instances of the
  failure in C5's sweep
- Verdict: CONFIRMED
- What happens: `#757575` measures **4.61:1 on `background.paper` `#ffffff`** — a pass — and
  **4.23:1 on `background.default` `#f5f5f5`** — a fail. So the token is not uniformly broken, which
  is why it has survived: every instance inside a Card or Paper is fine, and every instance sitting
  directly on the page background is not. That includes the sidebar footer links, page-level captions
  and the sub-labels under stat numbers.
- Instead: darken the light-mode value. `#6b6b6b` gives 4.89:1 on default and more on paper; `#666666`
  gives 5.27:1. One line, and it is strictly safer than auditing 177 call sites for which background
  each one happens to land on. The dark-mode value needs no change — `#9e9e9e` is 6.99:1 on default
- Evidence: `tests/audit/s1-token-contrast.mjs` at `1bfdaf9d`; failure instances from C5's sweep
- Register: not previously filed

### X-014 A third of the theme is dead surface area, and it is the part a newcomer would reach for first
- Unit: S1
- Surface: `web/src/theme/theme.ts`
- Norm: the app's own majority — the tokens that *are* used are used consistently, so the unused ones
  are the anomaly (source 2)
- Class: design
- Severity: low on its own; the reason to fix it is that each dead entry is a trap for the next person
- Reach: `theme.ts` is the file every future styling decision starts from
- Verdict: CONFIRMED
- What happens: three groups of declarations that look authoritative and do nothing.
  - **The `shadows` scale.** All 25 entries are hand-written, the last 16 are byte-identical copies of
    index 8, and the whole array needs an inline conditional-type cast at `theme.ts:80-85` to typecheck.
    Measured on `/courses` in both themes: **two** elements in the entire page have a `box-shadow`, and
    both are a course-colour inset bar, not a theme elevation. Nothing renders one, because `MuiCard`
    defaults to `variant: 'outlined'`, 83 places pass `variant="outlined"` explicitly, and `elevation`
    appears 7 times in the whole of `src/`. A bespoke elevation scale that nothing elevates.
  - **Six dead palette entries** — the whole `secondary` triplet and the four `*.light` tints — with
    zero uses in `src/`, all mode-blind, all near-white or grey. See R-001.
  - **`MuiCard`'s default hover treatment**, which animates a border and a shadow on every Card
    including the ones that are not interactive.
- Instead: delete the shadow scale and let MUI's default stand until something actually needs
  elevation; delete the six dead palette entries or give them mode-aware values as part of X-012;
  move the Card hover to a variant or an `sx` at the interactive call sites. None of this changes a
  pixel of what currently renders, which is the point — it removes the parts that would mislead the
  next person into thinking there is a system where there is only a leftover.
- Evidence: `tests/audit/s1-measure-shape.mjs` at `278d1ee1`, both themes; use counts by grep at the
  same sha
- Register: not previously filed

### X-015 Choosing TSL greets the teacher with a compiler error they did not cause, and blocks Save
- Unit: T1
- Surface: `/library/exercise/:id` → Automaatkontroll tab, editing (teacher), light, 1440×900, et
- Norm: platform — an empty state is not an error state (source 5); and the app's own behaviour two
  inches lower down the same panel, which renders a correct empty state (source 2)
- Class: journey
- Severity: high
- Reach: every teacher who ever sets up TSL auto-assessment, on their first contact with it
- Verdict: CONFIRMED
- What happens: a teacher opens a freshly created exercise — `CreateExerciseDialog` always makes a
  `TEACHER`-graded one — clicks Muuda, picks **TSL** from Automaatkontrolli tüüp, and immediately gets
  a **red error alert** where the compiler's rejection goes. `changeType()` seeds `gradingScript`,
  `assets: [{generated_0.py: ''}]`, `maxTimeSec: 7` and `maxMemMb: 30`, but **no `tsl.json`** — so
  `useTslSpec` compiles the empty string. Measured request body: `{"tsl_spec":"","format":"JSON"}`,
  one call, sent unprompted. **Salvesta is `disabled: true`.**
  The absurdity is what sits underneath it: the panel already renders the correct empty state,
  *"Teste veel pole."*, and an enabled **+ Lisa test** button. So the screen simultaneously says "you
  have no tests yet, add one" and "the spec is broken", and disables the only button that would let
  the teacher keep their choice. Nothing is wrong except that nothing has been written yet.
- Instead: seed a valid empty spec in `changeType()`. `emptySpec()` already exists in `tslModel.ts` and
  produces `{language, validateFiles, requiredFiles, tslVersion, tests: []}`, which compiles cleanly —
  so the first render becomes the empty state that is already there, with Save available. One detail
  worth getting right in the same edit: `emptySpec()` hardcodes `requiredFiles: ['lahendus.py']` while
  the panel has the real `solution_file_name` two fields above it, and those two silently disagreeing
  is a T3 finding in waiting — seed it from the actual value.
- Evidence: `tests/audit/t1-tsl-first-run.mjs` at `278d1ee1`; shot `t1-04-just-chose-tsl.png`.
  Supporting context: the TSL entry in `autoEvalTypes.ts` is the **only** container with no
  `helpTextKey`, while pygrader's links out to GitHub — so the deepest feature in the app offers no
  explanation at the one moment a teacher has just chosen it and does not yet know what a "test" is
  here
- **Settled against the real compiler** (`:8080`, authorised, header auth — see the Baseline). The
  placeholder is gone; this is what a teacher actually reads, verbatim, for choosing a dropdown option:

  ```
  Expected start of the object '{', but had 'EOF' instead at path: $
  JSON input:
  ```

  A kotlinx parser diagnostic, in English, in an app that defaults to Estonian, ending in an empty
  "JSON input:" because there is no input. It is worse than the placeholder guessed.
- **And the proposed fix is verified, not merely proposed.** `emptySpec()`'s exact output —
  `{"language":"python3","validateFiles":true,"requiredFiles":["lahendus.py"],"tslVersion":"1.0","tests":[]}`
  — compiled against the real compiler returns `feedback: null` and a valid
  `generated_0.py`. So seeding it removes the error rather than moving it
- Register: not previously filed. Adjacent to EZ-1734, which is the same shape one field up: the
  container dropdown is hardcoded, so an unavailable container also fails late rather than early

### X-016 A teacher cannot try a TSL test set until they have saved it
- Unit: T1 (T4 owns the wider feedback-loop question)
- Surface: `/library/exercise/:id` (teacher), editing
- Norm: design judgement, argued (source 6) — authoring and verifying a test set is one task
- Class: journey
- Severity: medium
- Reach: every TSL authoring session
- Verdict: CONFIRMED
- What happens: the Testimine tab — the only place in the application where a teacher can run their
  own tests — is rendered only when `exercise.grader_type === 'AUTO'` **as last saved**. Measured on a
  freshly TSL-configured exercise, the tab strip is exactly `["Ülesanne","Automaatkontroll"]`: no
  Testimine. So the sequence forced on a teacher is write the tests, save them to the library, then go
  and look for a tab that has appeared, and run against the saved version. Combined with X-015 the
  first-run path is: choose TSL, get an error, add a test to clear it, save, and only then discover
  whether any of it works.
- Instead: T4 will take the general form of this. The narrow fix is to gate the tab on the *edited*
  grader type rather than the saved one, and to keep the existing "testing runs against the saved
  version" warning that already exists for the editing case — the warning shows the intent was
  understood; the gate just uses the wrong value
- Evidence: `tests/audit/t1-tsl-first-run.mjs` at `278d1ee1`, tab strip enumerated after choosing TSL
- Register: not previously filed; EZ-1755 and EZ-1756 are about the tab's contents, not its existence

### X-017 A breadcrumb click destroys a half-built TSL test set, while Cancel on the same state asks first
- Unit: T5
- Surface: `/library/exercise/:id` → Automaatkontroll, editing (teacher), light, 1440×900, et
- Norm: the app's **own** behaviour on the identical state — `Cancel` guards it and `beforeunload`
  guards it, so this is asymmetric duplication rather than an omission (source 2); plus the platform
  rule that destructive navigation is confirmed (source 5)
- Class: journey
- Severity: **critical** — unsaved work discarded without warning
- Reach: every TSL authoring session, and every other editing surface reached by the same router
- Verdict: CONFIRMED
- What happens: a teacher edits a TSL spec, then clicks **Ülesandekogu** in the breadcrumb — the most
  natural way out of the page. The URL changes to `/library/dir/root`, no confirmation appears, no
  in-page warning appears, and `editedDraft` is dropped. The same is true of any sidebar item or the
  kebab's course links, because they are all React Router navigations and the guard is not on them.
- The control is what makes this a finding rather than a guess. On the *same* edited state, clicking
  **Tühista** produces a native confirm reading *"Sul on salvestamata muudatusi. Kas viskan need
  ära?"*. So the application knows the state is dirty, knows the sentence to say, says it on one exit,
  and stays silent on the other three. A `beforeunload` handler covers closing the tab, which means the
  only unguarded exits are the in-app ones a teacher actually uses.
- Instead: React Router's `useBlocker` on the editing state, reusing the same string that already
  exists (`library.unsavedChangesConfirm`). One hook, at the level of `ExercisePage`'s `dirty` flag,
  and it closes the whole class rather than the breadcrumb specifically.
- Evidence: `tests/audit/t5-tsl-state-escape.mjs` at `108bdd1f`, steps [1] and [2] — the second is the
  control and it fired. Shot `t5-01-after-breadcrumb-navigation.png`
- Register: not previously filed. **This is X-001's twin**: same defect class, teacher instead of
  student, and a longer piece of work to lose. Whatever fixes one should be checked against the other,
  and EZ-1758's addendum should mention that the pattern is not student-specific

### X-018 The TSL editor's error messages are a Kotlin library's developer diagnostics, shown to teachers verbatim
- Unit: T4
- Surface: `/library/exercise/:id` → Automaatkontroll → the alert above the Testid/TSL/Generated tabs
- Norm: errors say what happened and how to fix it, in the interface's voice (source 5, and the
  `frontend-design` writing guidance the V track cites); Estonian is the app's default language
- Class: copy + journey
- Severity: high
- Reach: every compile failure in the TSL builder, which is every intermediate state of a spec being
  hand-edited in the TSL tab
- Verdict: CONFIRMED
- What happens: `CompileTSL.controller` catches every exception and returns `e.message` as `feedback`,
  and `TslEditor` renders that string verbatim in a red `Alert`. `api/tsl.ts` even documents the
  intent — *"the text is meant to be shown verbatim"*. What the teacher gets is therefore kotlinx's
  own diagnostics. Measured against the real compiler, an unknown key produces:

  ```
  Unexpected JSON token at offset 106: Encountered an unknown key 'somethingTheUiInvented' at path: $
  Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to
  ignore unknown keys.
  JSON input: {"language":"python3","validateFiles":true,…
  ```

  So a teacher who mistypes a field name is told, in English, to set `ignoreUnknownKeys = true` in a
  `Json {}` builder — advice addressed to whoever wrote the compiler, actionable by nobody who will
  ever read it — followed by their whole document echoed back. There is no indication of *which test*
  or which field, which is the one thing the message could usefully have said. An empty spec produces
  the same class of thing (see X-015).
- Instead: three tiers, cheapest first. (1) Keep the raw text but put it behind a "Details" disclosure
  under one written sentence — "There is a problem in the spec on the TSL tab" — so the useful signal
  is not competing with a Kotlin stack idiom. (2) Map the two or three kotlinx shapes that actually
  occur (unknown key, missing field, bad discriminator) to a sentence naming the key and the test,
  which is available in the message already and is the only part a teacher can act on. (3) Set
  `ignoreUnknownKeys = true` on the *read* path if the round-tripping of unknown keys the UI already
  does deliberately is meant to survive — that is a core decision, not a web one, and it is worth
  asking whether the strictness is buying anything here. Tier 1 alone removes most of the damage.
- Evidence: three specs compiled against the real core at `a8626fa4` via header auth; responses
  recorded above and in the commit message. Adjacent: the compiler also emits its *own* Estonian
  default test name (`'Programmi käivituse test'`) into the generated script while the UI derives its
  own display name from `testDefaultName` — two sources for one string, which is a **T3** lead
- Register: not previously filed. EZ-1536 ("Make TSL generated code readable") is about the generated
  Python, not the error text

### X-019 The grader supports six working features no teacher can reach, including case-insensitive output matching
- Unit: T3
- Surface: the TSL builder's forms, versus what `tsl-common`'s model accepts
- Norm: an endpoint or capability no UI reaches is a candidate missing action (source 4)
- Class: journey + design
- Severity: medium
- Reach: every auto-graded exercise; `ignoreCase` and `EQUALS` in particular are wanted by anyone
  checking program output at all
- Verdict: CONFIRMED
- What happens: each of the following was compiled against the real compiler and **works** — it is
  accepted, and the value reaches the generated Python. None of them can be produced by any form; the
  only way to set them is to hand-edit the JSON in the TSL tab, which means knowing they exist.

  | capability | what it does | reachable |
  |---|---|---|
  | `ignoreCase: true` | case-insensitive output matching | no form writes it |
  | `dataCategory: 'EQUALS'` | exact-equality instead of "contains" | dropdown offers 3 of the 4 values |
  | `outputCategory` | which output to check — `LAST_OUTPUT`, `OUTPUT_NUMBER_0…9`, `ALL_OUTPUT`, `ALL_IO` | **13 values, 0 reachable** |
  | `nothingElse` on execution checks | "…and nothing else" | exposed on *static* tests only |
  | `beforeMessage` | the line shown before a check runs | UI always writes `''` |
  | `passedNext` / `failedNext` | branch to another test on pass/fail | no UI at all |

  The first two are the ones that matter. A teacher who wants "the output must contain `yes`, in any
  case" cannot express it, and the workaround is to list every capitalisation in `expectedValue`.
  `outputCategory` matters for the very common shape of a program that prints a prompt and then an
  answer: today every check sees `ALL_IO`, so a prompt containing the expected string passes a test the
  student's answer failed.
- Instead: not all six. `ignoreCase` is a checkbox next to the existing "The values must be in the same
  order" one, and `EQUALS` is a fourth entry in a dropdown that already has three — both are cheap and
  both remove a real workaround. `nothingElse` on execution checks is worth adding for symmetry, since
  the same concept is already a checkbox one tab away and its absence here reads as an oversight
  rather than a decision. `outputCategory` deserves a form only once someone decides what to call it
  in a teacher's words. `passedNext`/`failedNext` is a whole branching feature and omitting it looks
  deliberate — leave it, but write down that it was a choice.
- Evidence: `tests/audit/t3-model-vs-compiler.mjs`, report `tests/audit/reports/t3-model-vs-compiler.json`,
  nine specs against the real compiler at `9dbe8dd1`. Each case checks both that the spec compiled and
  that the value left a trace in the generated Python — see R-007 for why the second half matters
- Register: not previously filed. EZ-1695 ("TSL editor (visual + YAML)") is still open and may be
  where the deliberate-subset decision was recorded; check it before treating any of these as
  oversights

### X-020 Two tests with the same id compile without complaint
- Unit: T3
- Surface: `POST /v2/tsl/compile`, and therefore the TSL tab
- Norm: the compiler's own `validateParseTree()`, which exists to reject exactly this and says
  *"Test ID-s must be unique within the exercise!"* (source 1)
- Class: correctness
- Severity: low for the UI, and the reason it is in this log at all is reach
- Verdict: CONFIRMED
- What happens: a spec containing two tests with `id: 111` compiles cleanly and emits both into the
  generated script. `validateParseTree()` is only called from `DemoApplication.main()`, never from
  `compileTSL`, so the check that exists never runs on the path the application uses. The visual editor
  cannot produce a duplicate (`nextId()` is random over 48 bits) but the JSON tab can, and
  `doc/core/tsl-migration/README.md` records that **174 of 721** production specs already have them.
- Instead: this is a core decision, not a web one — either call the validation from `compileTSL`, or
  delete it and record that ids are not required to be unique. What the *UI* could do meanwhile is
  surface duplicate ids in the TSL tab as a warning, since it already parses the spec there and 174
  live exercises say the situation occurs.
- Evidence: `tests/audit/t3-model-vs-compiler.mjs` at `9dbe8dd1`
- Register: not previously filed. Belongs with the review programme's F1 findings on the compiler
  rather than with the web ones; noted here because T3 is where it surfaced

### X-021 Every destructive edit in the TSL builder is unconfirmed and unrecoverable
- Unit: T5
- Surface: `/library/exercise/:id` → Automaatkontroll → Testid, editing (teacher), et
- Norm: platform — destructive actions confirm, or are undoable (source 5); and the app's own
  `ConfirmDialog` and the `Cancel` guard, which show it knows how (source 2)
- Class: journey
- Severity: high
- Reach: every TSL authoring session; the two actions are on the primary card controls
- Verdict: CONFIRMED
- What happens: two actions, both one click, both silent, neither reversible.
  - **Changing a test's type discards the whole body.** Measured through the compile payload: a
    `program_execution_test` carrying `standardInputData: ["2","3"]`, an input file and one output
    check became a `function_execution_test` with `standardInputData: []` and zero checks. No
    confirmation. The hand-set *name* survives — `changeType` keeps a non-default name deliberately —
    which makes it worse, because the card still says "Minu käsitsi nimetatud test" while everything
    under it is gone. A teacher who picked the wrong type first, filled it in, and then corrected the
    type loses the lot.
  - **Deleting a test asks nothing and offers no way back.** The kebab's Kustuta removes it, the spec
    goes from one test to none, the empty state appears, and a sweep of every visible button and menu
    item finds **no undo, restore or "tagasi" affordance anywhere**.
  There is no model-level undo in the builder at all: the only history is CodeMirror's own, which lives
  inside the JSON tab and the constructor-code fields and dies when those unmount on a tab switch.
- Instead: these want different fixes, which is why they are one finding and not two patches. Deleting
  a test is the classic case for **undo rather than confirm** — remove it, show "Test kustutatud ·
  Võta tagasi" in the snackbar the app already mounts for bug reports, and keep the object for the
  session. Changing a type is the case for **confirm**, and only when the body is non-empty: the
  question "this will clear the fields you have filled in — continue?" is answerable, whereas an undo
  after a type change leaves the UI in a state that is hard to describe. `ConfirmDialog` already
  exists; it is under `features/participants/`, which is C3's finding.
- Evidence: `tests/audit/t5b-tsl-state-rest.mjs` at `fbd7a43e`, observing the debounced compile
  payloads rather than the JSON tab, so the before/after specs are the app's own serialisation. Shots
  `t5b-A2-after-type-switch.png`, `t5b-B1-after-delete.png`
- Register: not previously filed

### X-022 An invalid spec locks Save permanently, even after the exercise stops being a TSL exercise
- Unit: T5
- Surface: `/library/exercise/:id` → Automaatkontroll, editing (teacher), et
- Norm: a control that cannot be used must say why (source 5)
- Class: correctness + journey
- Severity: high — it is a dead end, and the only exits lose work
- Reach: any TSL session where the spec is invalid at the moment the teacher changes their mind about
  auto-assessment, which is exactly when they are most likely to
- Verdict: CONFIRMED, **with a control**
- What happens: break the JSON in the TSL tab and Save correctly disables. Then change
  Automaatkontrolli tüüp to **–** (no auto-assessment at all): the TSL editor unmounts, the error alert
  goes with it, there is no longer any TSL spec in play — and **Save stays disabled, with nothing on
  screen explaining why.** `tslValid` is only ever written by `TslEditor`, so unmounting it leaves the
  flag stuck at `false`, and `isAutoAssessValid` keeps failing. The teacher's only ways out are Cancel,
  which discards everything, or going back to TSL to repair JSON they may not understand — to configure
  a feature they have just decided not to use.
  The control rules out the boring explanation: with the spec left **valid**, the identical sequence
  leaves Save **enabled** after switching to –. So it is the stale flag, not the switch.
- Instead: reset `tslValid` when the container stops being TSL — the same effect as treating a
  non-TSL exercise as trivially valid. Better, since it removes the class rather than the instance:
  derive validity from the current draft instead of latching it in state, so there is nothing to go
  stale.
- Evidence: `tests/audit/t5c-stale-tslvalid.mjs` at `fbd7a43e`, both arms. Note the first attempt at
  this measured the *submission type* select instead of the auto-assessment one and produced a
  meaningless pass — the driver now finds the select by its visible label. Shots `t5c-broken-*.png`,
  `t5c-valid-*.png`.
  Incidental confirmation for EZ-1734: the type options are exactly
  `["–","TSL","Silmused PostgreSQL","Python Grader","Pildituvastus"]` — four hardcoded containers plus
  none, with no reference to what any executor actually has
- Register: not previously filed

### X-023 The first preset in the menu produces a test that checks nothing, and nothing says so
- Unit: T4
- Surface: `/library/exercise/:id` → Automaatkontroll → Testid → Lisa test, editing (teacher), et
- Norm: design judgement, argued (source 6) — a grader that cannot fail is worse than no grader,
  because it looks like it worked
- Class: journey + design
- Severity: **high**
- Reach: every exercise built from a preset, which is the primary way tests are added
- Verdict: CONFIRMED end to end — UI, contract and generated script
- What happens: a teacher opens **Lisa test** and picks the first item in the first group, *"Käivitab
  programmi"*. The resulting test carries `genericChecks: []`, `outputFileChecks: []` and
  `exceptionCheck: null` — it examines nothing. Nothing on screen says so: no alert, no caption, and
  nothing painted in `warning.main`. Sent through the **real compiler** it is accepted without
  complaint and emits `standard_output_checks=[], output_file_checks=[], exception_check=None` with
  `points_weight=1.0`. So a teacher can add a test, save, put the exercise on a course, and every
  student who submits anything at all scores full marks — and the exercise looks configured.
  The app already knows how to say this: `class_instance_test` gets an orange caption when both its
  checkboxes are off, reading that it "compares nothing and passes for everyone". That sentence is
  exactly what is missing here, on the far more common path.
- **This finding is also T4's answer to its own question.** The unit asks whether a teacher can tell a
  working test set from a broken one before a student meets it. There is no preview of *results*, only
  of generated Python; the Testimine tab does not exist until the exercise is saved (X-016); and nothing
  anywhere reports "this test cannot fail". This audit could not answer it either without reading the
  generated script — which is the finding, not a gap in the audit.
- Instead: reuse the existing pattern. A test with no checks gets the same orange caption the
  class-instance case gets, and the Testid tab gets a count of tests-that-check-nothing beside the
  Save button. Neither needs a new component. Longer-term, the honest fix is a way to run the set
  against a deliberately wrong solution and see it fail — but the caption is a day's work and removes
  the silent-failure mode.
- Evidence: `tests/audit/t4-can-it-fail.mjs`, report `tests/audit/reports/t4-can-it-fail.json`, at
  `c9cb3cdb`. Shots `t4-01-preset-menu.png`, `t4-02-fresh-test-no-checks.png`
- Register: not previously filed

### X-024 Two different presets are labelled identically in Estonian
- Unit: T4
- Surface: the Lisa test menu, `et`
- Norm: WCAG's `duplicate link names` rule generalised — two controls that do different things must be
  distinguishable (source 5); and the menu is the primary discovery surface for TSL
- Class: copy
- Severity: high — the teacher cannot form an intention, and the two tests are not similar
- Reach: every teacher adding a test in the app's **default language**
- Verdict: CONFIRMED
- What happens: the menu contains **"Kutsub välja funktsiooni" twice** — under *Käivitab koodi* and
  again under *Mida kood välja kutsub*. They are different presets producing different test types:
  `callFunction` → `function_execution_test`, which *runs* a function in the student's code and checks
  what it returns; and `callsFunction` → `calls_test`, which checks whether the student's code *calls*
  a function at all. Byte-identical labels, opposite intents.
  English escapes by a single letter and a change of mood — `"Call a function"` versus
  `"Calls a function"` — which is a real distinction (you make it run / the code does it) that Estonian's
  *kutsub välja* cannot carry the same way. So this is not a missing translation; it is a naming scheme
  that only works in one language.
- Instead: name them by what the teacher is *checking*, in both languages, rather than by grammatical
  mood — along the lines of "Käivitab funktsiooni ja kontrollib tulemust" versus "Kontrollib, kas kood
  kutsub funktsiooni". The group headers already carry half the distinction; the items should not
  depend on the reader having noticed which group they are in.
- Evidence: menu enumerated from the live DOM by `tests/audit/t4-can-it-fail.mjs` at `c9cb3cdb`, and
  the collision confirmed in `src/i18n/et.json` — `tsl.preset.callFunction` and
  `tsl.preset.callsFunction` are the same string. Shot `t4-01-preset-menu.png`
- Register: not previously filed. **Belongs to EZ-1785 as well** — it is the clearest instance yet of
  the audit's premise that the default language is the untested one, and it is recorded in the leads
  section below

### X-025 The TSL builder's height is the same on a phone-width column and a 2560px monitor
- Unit: T6
- Surface: `/library/exercise/:id` → Automaatkontroll, editing (teacher), et, both themes
- Norm: design judgement, argued (source 6); and EZ-1527's premise that large screens should buy
  something
- Class: responsive + design
- Severity: medium
- Reach: every TSL authoring session, and the same single-column-forever shape is likely to hold
  wherever else the app stacks form sections
- Verdict: CONFIRMED
- What happens: measured rendered page height for **one** expanded test plus one collapsed one:

  | viewport | page height | vs laptop |
  |---|---|---|
  | 390 × 844 (phone) | 2834 px | +29% |
  | 1440 × 900 (laptop) | **2200 px** | — |
  | 2560 × 1440 (monitor) | **2200 px** | **identical** |

  Going from a laptop to a large monitor adds 1120 px of width and removes **zero** pixels of height.
  The builder is a single column of stacked sections at every size, so a teacher on a 2560×1440 screen
  scrolls ~2.4 viewport heights through one test while more than half the screen stays empty. Six
  tests — an ordinary exercise — is on the order of fifteen screens either way, and the shape of the
  test set is never visible at once.
- Instead: spend the width. At `lg` and above the checks could sit in two columns, or the Inputs and
  Checks groups side by side — both are the kind of change `sx={{ display: {xs:'block', lg:'grid'} }}`
  expresses, and the app already uses responsive `sx` objects in eight files. Cheaper still and
  probably worth doing first: a collapse-all/expand-all control and a one-line summary per collapsed
  card (type, points, how many checks), so a teacher can see the whole set without expanding anything.
  That also serves X-023 — "how many of these check nothing" is exactly the summary line's job.
- Evidence: `tests/audit/t6-tsl-under-pressure.mjs`, report
  `tests/audit/reports/t6-tsl-under-pressure.json`, at `bf48e09b`; heights read from the rendered PNG
  dimensions at `deviceScaleFactor: 2`. Shots `t6-{phone,laptop,monitor}-{light,dark}.png`
- Register: **EZ-1527** ("Optimise for large screens") is the general form; this is the instance on the
  app's deepest screen, and it pairs with X-008, which found the same viewport-invariance in the
  student's code editor. Two independent measurements of one habit

### X-026 When the grading infrastructure fails, the student is graded 0 and shown the Docker error
- Unit: T7
- Surface: `/courses/:c/exercises/:ce` (student), Automaatkontroll panel, light, 1440×900, et
- Norm: errors say what happened and how to fix it, in the interface's voice, and never blame the
  reader for something they did not do (source 5)
- Class: journey + copy
- Severity: **high**
- Reach: every student affected by any grader outage — and by construction that is many students at
  once, since infrastructure fails for a whole cohort rather than one person
- Verdict: CONFIRMED
- What happens: two failure shapes, both rendered as though they were the assessment.
  - **Feedback that is not `OK_V3` at all.** `parseOkV3` returns `null`, `tests` becomes `[]`, and the
    raw string is printed in a monospace panel under the heading **Automaatkontroll** with **0 / 100**
    beside it. Measured content reaching the student verbatim: `Traceback (most recent call last):`, a
    `/usr/local/lib/python3.11/site-packages/tiivad/__init__.py` path, `RuntimeError: container image
    tiivad:tsl-compose is missing scripts`, `docker: Error response from daemon: OCI runtime create
    failed`, and `killed`. Nothing on screen says this is not about their program.
  - **`pre_evaluate_error`.** The student gets an alert reading
    `SyntaxError: invalid syntax (generated_0.py, line 3)` — a syntax error in the *teacher's generated
    grading script*, named by a filename the student has never seen and cannot open, presented beside
    their own code with a 0.
  In both cases the sidebar status dot and the 0/100 make it read as a completed attempt that they
  failed. This is review C2/F-019 confirmed on the student's screen rather than in the pipeline.
- **Deliberately not a finding:** an `OK_V3` test whose `exception_message` carries the *student's own*
  traceback renders it in full under "Erind", and that is right — a student debugging a `TypeError` in
  their own code needs exactly that text. The distinction the fix has to preserve is whose stack trace
  it is.
- Instead: branch on it. When feedback does not parse as `OK_V3`, or when `pre_evaluate_error` is set,
  the panel should say — in Estonian, in the app's voice — that the automatic check could not run, that
  it is not the student's fault, and that the teacher has been notified; then keep the raw text behind
  a "Details" disclosure for the teacher who will be asked about it. The grade display wants the same
  treatment: 0/100 for an outage is a wrong statement about a student's work, and "not assessed" is the
  honest one. Whether the attempt is *stored* as a 0 is a core question and belongs with the review
  programme, but the UI should not assert it.
- Evidence: `tests/audit/t7-student-sees-failure.mjs`, report
  `tests/audit/reports/t7-student-sees-failure.json`, at `29271a61`; four failure shapes driven
  through the real student view. Shot `t7-not-ok-v3-raw-container-output.png`
- Register: not previously filed on the web side. Related to review **F-019**, which found raw
  container output entering student-visible feedback; this is the other end of that pipe

### X-027 "Required" in the TSL builder is decoration — nothing enforces it, and a student finds out
- Unit: T2
- Surface: `/library/exercise/:id` → Automaatkontroll → a test card, editing (teacher), et
- Norm: platform — a field marked required is enforced, or it is not marked required (source 5); and
  the app's own `isAutoAssessValid`, which *does* gate Save on three other fields (source 2)
- Class: journey + correctness
- Severity: **high**
- Reach: every required field in the builder — `functionName` on function-execution and function-is
  tests, `className` on class-instance tests, and the scope-implied name on all four static types
- Verdict: CONFIRMED at all three layers
- What happens: a `function_execution_test` with `functionName: ''` — the field the form itself marks
  required — renders exactly as it should: an asterisk on the label *Funktsiooni nimi \**, and three
  elements carrying `Mui-error`, so the field is visibly red. Then:
  - **Save is enabled.** `saveDisabled: false`. `isAutoAssessValid` gates only `solutionFileName`,
    `maxTimeSec` and `maxMemMb`; the red field is not consulted.
  - **The real compiler accepts it.** `feedback: null`, scripts emitted. `functionName` is a
    non-nullable `String` in Kotlin and `""` satisfies that.
  So a teacher can save the exercise, put it on a course, and nothing has objected. tiivad is the first
  thing that will care, at grading time — which is where **X-026** showed the student gets raw output
  and a zero. Three findings, one story: the form knows, the app does not enforce, and the person who
  discovers it is a student.
- **The app already has the right pattern, in one place.** A class-instance check with both checkboxes
  off draws an orange caption in `warning.main`: *"Kui mõlemad on märkimata, ei kontrolli see midagi ja
  läbitakse alati."* Correct severity, correct voice, correct colour — and it is the only instance. So
  the fix is not a new mechanism, it is applying this one consistently.
- Instead: two tiers, and they are different judgements. A blank *required* field should **gate Save**,
  the way the three `isAutoAssessValid` fields already do — the red outline is already computed, so this
  is wiring an existing signal into an existing gate. A test that is merely *pointless* (no checks,
  X-023; a check comparing nothing) should **warn** in the class-instance caption's style and still
  save, because a teacher may be part-way through. The distinction to hold is broken-versus-unfinished:
  today both are silent.
- Evidence: `tests/audit/t2-required-fields.mjs`, report `tests/audit/reports/t2-required-fields.json`,
  at `72b18060`; UI state read from the DOM, compiler verdict from the real core. Shots
  `t2-required-functionname-left-blank.png`, `t2-class-instance-check-that-compares-nothing.png`
- Register: not previously filed

### X-028 Teachers can set the submission type to "File upload" and students get a text box
- Unit: J1
- Surface: `/library/exercise/:id` (teacher, the setting) → `/courses/:c/exercises/:ce` (student, the
  consequence)
- Norm: a setting that is offered is honoured, or it is not offered (source 5)
- Class: journey
- Severity: high — a teacher configuring an upload exercise gets no error and no upload
- Reach: every exercise a teacher sets to `TEXT_UPLOAD`; unknown how many exist, and worth counting in
  the database before triaging
- Verdict: CONFIRMED
- What happens: `AutoAssessTab.tsx:224` offers **Esitamise viis → "Faili üleslaadimine"** ("File
  upload") as one of two options. `grep -rn TEXT_UPLOAD web/src` returns exactly **two** hits: that
  `MenuItem`, and the `SolutionFileType` union in `api/types.ts`. **Nothing on the student side reads
  `solution_file_type` at all.** Driven with `solution_file_type: 'TEXT_UPLOAD'`, the student page
  renders a CodeMirror editor and **no `input[type=file]`** — identical to `TEXT_EDITOR`, with the
  placeholder still reading *"Kirjuta, kopeeri või lohista lahendus siia…"*.
  So a teacher sets up a file-upload exercise, sees it accepted, and their students are asked to paste
  a file's contents into a text box. Nobody is told.
- Instead: the honest short-term fix is to **remove the option** until the student half exists, since an
  ignored setting is worse than an absent one — and to check what existing exercises carry the value.
  If upload is wanted, it is a real feature and belongs in EZ as one; note `ExerciseDetails` already
  ships `solution_file_type` to the client, so the contract is ready and only the UI is missing.
- Evidence: `tests/audit/j1b-student-edge-states.mjs`, report
  `tests/audit/reports/j1b-student-edge-states.json`, at `1bdd895c`; plus the two-hit grep. Shot
  `j1b-text-upload-submission-type.png`
- Register: not previously filed. Distinct from EZ-1764, which is about creating stored files from the
  *markdown* editor

### X-029 A past deadline is shown but never marked as past
- Unit: J1
- Surface: `/courses/:c/exercises/:ce` (student), et
- Norm: platform — state the reader needs should not require arithmetic (source 5); the app already
  knows both the deadline and the date
- Class: journey
- Severity: medium
- Reach: every exercise with a deadline, at every point after it passes
- Verdict: CONFIRMED
- What happens: with a deadline in the past and `is_open: true`, the page shows **"Tähtaeg: 2. aug
  2026. 02:59"** and an enabled *Esita ja kontrolli*. Nothing says the deadline has gone, nothing says
  whether a late submission still counts, and the student has to compare the date against today's to
  find out they are late. Compare the closed case, which the app handles **well**: `is_open: false`
  produces *"See ülesanne on suletud ja ei luba enam uusi esitusi"* and removes the submit button
  entirely. The vocabulary for saying this exists; it just is not used for lateness.
- Instead: mark the state, not just the date — "Tähtaeg möödus 3 nädalat tagasi" using the
  `RelativeTime` component the app already has, and a line on whether late submissions are accepted,
  which is the actual question. The deadline chip in the exercise list wants the same treatment.
- Evidence: same driver and report as X-028, case 3. Shot `j1b-deadline-in-the-past-still-open.png`.
  Incidental string lead for EZ-1785: the rendered date is *"2. aug 2026. 02:59"*, with a trailing
  period after the year
- Register: not previously filed

### X-030 The only unlabelled icon buttons in the grading view are the ones that move between students
- Unit: J6
- Surface: `/courses/:c/exercises/:ce` (teacher), a student's submission open, et
- Norm: the app's **own** practice in the same view — eight sibling icon buttons all carry an
  `aria-label` (source 2); plus WCAG `button-name` (source 5)
- Class: a11y + consistency
- Severity: medium
- Reach: the two controls a teacher uses most while grading a cohort
- Verdict: CONFIRMED
- What happens: the student header reads `‹  Anna Aare ▾  ›`, and the two chevrons are the fast path
  between students — one click instead of a round trip through the roster. Enumerated from the DOM,
  every icon button in the view has an accessible name — *Ülesande sätted*, *Peida vasak paneel*,
  *Peida parem paneel*, *Tagasi nimekirja*, *Märgi vaadatuks*, *Märgi ülevaatamiseks*, *Bold*,
  *Italic* — **except these two, whose `aria-label` is `null`**. So a screen-reader user grading
  thirty submissions is the one user who cannot find the control that exists to make that bearable,
  and they will fall back to the roster round trip.
  This is asymmetric divergence rather than a general omission, which is what makes it worth filing:
  the convention is established and applied eight times in one component, and missed twice.
- Instead: two `aria-label`s, from the strings that presumably already exist for the roster's ordering.
  Worth adding a tooltip at the same time — the chevrons are also the least discoverable control on
  the screen for sighted users, which is how this audit's own first pass missed them and briefly
  concluded there was no next-student affordance at all (see R-011).
- Evidence: `tests/audit/j6-grading-workflow.mjs`, report
  `tests/audit/reports/j6-grading-workflow.json`, at `a47bda36`; accessible names read from the DOM.
  Shot `j6-02-grading-anna.png`
- Register: not previously filed. Same class as X-002/X-003/X-010 — this programme has now found
  icon-only controls without names on four separate surfaces, which argues for the sweep C5 proposes
  rather than four separate fixes

### X-031 Grade a student, open the grade table: the student still shows as ungraded
- Unit: J6
- Surface: `/courses/:c/exercises/:ce` (grading) → sidebar → `/courses/:c/grades`, teacher, et
- Norm: the button that says Salvesta produces a table that says saved (source 5); review F-035 is the
  mechanism, already CONFIRMED — this is its user-visible consequence, driven end to end
- Class: journey + correctness
- Severity: high
- Reach: every grading session that touches the grade table afterwards — checking progress after
  grading is the natural next step, so most of them
- Verdict: CONFIRMED by execution
- What happens: the exact sequence a teacher performs. (1) Open Hinded — Anna shows "–". (2) Navigate
  client-side to the exercise, open Anna, enter 77, Salvesta — the POST lands, the server now holds 77.
  (3) Sidebar → Hinded, seconds later. **The table still shows "–".** Measured: the exercises-list
  endpoint was fetched three times, all before the POST; the return to the table triggered **no
  fetch** — react-query served the cached copy (`staleTime: 30_000`), and `usePostGrade` invalidates
  `['teacher','courses',c,'exercises',ce]` while the table's key is
  `['teacher','courses',c,'exercises',{groupId}]` — a string never prefix-matches an object, so the
  cache was never marked stale.
- Instead: one line in `usePostGrade` (and `usePostFeedback`): invalidate
  `['teacher','courses',c,'exercises']` — the parent prefix — or add the table's exact key. E4 already
  noted the CSV reads the same query, so the export inherits the fix.
- Evidence: `tests/audit/j6b-grade-then-gradetable.mjs`, report
  `tests/audit/reports/j6b-grade-then-gradetable.json`, at `06ddddf0`. Shots `j6b-1-table-before.png`,
  `j6b-3-table-after.png`
- Register: review **F-035** (mechanism, confirmed there by reading). This adds the executed
  user-visible proof and the exact key mismatch

### X-032 Removing a student from a course asks for confirmation but never says what happens to their work
- Unit: J7 (the pattern belongs to C3)
- Surface: `/courses/:c/participants` → select a student → Eemalda kursuselt, teacher, et
- Norm: a destructive confirm says what will be destroyed (source 5); the same page's *delete-group*
  dialog, which lists every affected student by name, shows the app knows how (source 2)
- Class: journey + copy
- Severity: medium
- Reach: every removal — and the question it leaves unanswered is exactly the one a teacher has at
  that moment
- Verdict: CONFIRMED
- What happens: the confirm reads, in full: *"Eemalda Mari Maasikas sellelt kursuselt? Tühista /
  Eemalda."* It names the student — good — but says nothing about their submissions, grades or
  feedback: kept? deleted? restored if the student rejoins by invite link? The teacher deciding
  whether to click is deciding blind on the only fact that matters.
  The contrast is the same page's delete-group dialog, measured in the same run: *"Kustuta rühm 'Rühm
  A'? Need õpilased eemaldatakse rühmast: Mari Maasikas."* — consequence stated, affected people
  listed.
- Instead: one sentence stating the fate of the student's work, whatever core's answer is. If
  submissions survive and rejoining restores them, saying so makes the action safe to use; if they are
  deleted, the confirm is currently hiding the destruction it exists to warn about.
- Evidence: `tests/audit/j7-roster-workflow.mjs`, report `tests/audit/reports/j7-roster-workflow.json`,
  at `a7ca430f`. Shot `j7-03-remove-confirm.png`
- Register: not previously filed

### X-033 A student who opens a teacher's link watches a spinner, then silently lands on "Minu kursused"
- Unit: J2
- Surface: any teacher-only URL opened as a student; measured on `/courses/:c/grades`, et
- Norm: a redirect the user did not ask for is explained, or it reads as a bug (source 5)
- Class: journey
- Severity: medium
- Reach: every teacher-shared link that reaches a student — which is how course URLs move around in
  practice (chat, slides, email)
- Verdict: CONFIRMED
- What happens: a student opens `/courses/119/grades`. They get the loading spinner ("Laen...") for
  several seconds — the guard waits for check-in and role data — and are then deposited on
  `/courses`, with **no alert, no snackbar, no text anywhere** saying why. Measured: path
  `/courses`, alerts `[]`. To the student, the link their teacher sent is simply broken, and nothing
  suggests otherwise; the natural next step is to ask the teacher or re-click the link, which does the
  same thing.
- Instead: keep the redirect, add the sentence — a snackbar or a dismissible banner on arrival: "See
  leht on nähtav ainult õpetajatele." One state flag through the existing `Navigate`, and the app
  already mounts a snackbar in the shell.
- Evidence: `tests/audit/j2-role-redirect.mjs` at `57c722fa`; shot `j2-role-redirect.png`
- Register: not previously filed. The seeded lead from the plan, now measured — including the detail
  the lead missed, that the spinner phase makes it feel like a slow crash rather than a policy

### X-034 Courses cannot end: no way to archive one, and an archived one looks live
- Unit: J3
- Surface: `/courses`, `EditCourseDialog`, teacher/admin
- Norm: the contract — `archived: boolean` arrives on every course response (source 4: a capability
  the API carries that no UI reaches); and the course lifecycle the schema implies (source 4)
- Class: journey
- Severity: medium now, rising every semester — course lists only grow
- Reach: every teacher, cumulatively; a teacher of four years carries every course they have ever run
- Verdict: CONFIRMED (by exhaustive grep, at `57c722fa`)
- What happens: `archived` appears in exactly three places in `src/` — twice in `types.ts`, once in
  `courses.ts`'s fetch type. **Nothing renders it and nothing writes it.** `EditCourseDialog` offers
  identifier, code and name fields only; `CoursesPage` neither filters nor badges archived courses. So
  a teacher cannot end a semester's course from the UI at all — and a course archived by other means
  (the old wui, the DB) renders indistinguishably from a live one, which defeats whatever archiving
  already happened.
- Instead: two small pieces, separable. Display first: archived courses collapse into an "Arhiveeritud"
  section or filter on `/courses` — the flag is already in every response, so this is render-only.
  Write second: an archive action in `EditCourseDialog`, if core has or grows the endpoint.
- Evidence: grep at `57c722fa`; `EditCourseDialog.tsx` field list
- Register: not previously filed — and it is a *gap in the gap list*: the WUI migration checklist
  (EZ-1689…1707) never mentions archiving, so it fell out of the migration unnoticed

### X-035 One error sentence serves the whole application, while the typed error codes go unread
- Unit: C1
- Surface: app-wide — every mutation and most queries
- Norm: errors say what happened and how to fix it (source 5); and the app's own best practice, which
  exists in exactly two places (source 2)
- Class: copy + journey
- Severity: high as a pattern
- Reach: 19 files render `general.somethingWentWrong` ("Midagi läks valesti") as their entire error
  handling; measured at `e58849ca`
- Verdict: CONFIRMED
- What happens: core answers failures with a typed envelope — `{id, code, attrs, log_msg}` — and
  `api/client.ts` parses it into `ApiResponseError.errorBody` on every request. **Exactly two call
  sites read it**: `ShareDialog` branches on `ACCOUNT_NOT_FOUND` ("no such teacher") and
  `BugReportDialog` on `BUG_REPORT_RATE_LIMITED`. Everywhere else — nineteen files — the user gets the
  same sentence whether the course name was taken, the deadline was malformed, the file was too large,
  or the server was down. The two good citizens prove the mechanism costs a few lines; TSL's compile
  path is the extreme opposite case, already filed as X-018 (it shows the raw internals instead).
  Eleven of nineteen dialogs also have no field-level validation at all (mapping pass), so the generic
  sentence is usually the *only* feedback a form gives.
- Instead: not nineteen bespoke handlers — one shared `errorMessage(err)` that maps the codes core
  actually emits (a bounded list, discoverable from `ReqError` in core) to sentences, falls back to the
  generic one, and is adopted opportunistically. The two existing branches fold into it. Pairs with
  X-009: the crash boundary and the error copy are the two halves of "when things go wrong".
- Evidence: greps at `e58849ca` (19 files; 2 consumers); `client.ts:15-18`; X-018 and X-027 as the
  measured worst cases
- Register: not previously filed as such; EZ-1786's bug-report flow is the mitigation for the
  unmappable remainder

### X-036 Empty states range from charming to absent, and none of them says what to do next
- Unit: C2
- Surface: app-wide, first-run and no-data states
- Norm: an empty screen is an invitation to act (source 5, the writing guidance); and the app's own
  best instance (source 2)
- Class: consistency + copy
- Severity: medium as a pattern — empty states are disproportionately what new users see
- Reach: every list surface; a new teacher's first session is almost entirely empty states
- Verdict: CONFIRMED
- What happens: the app has a designed empty state — `RobotPlaceholder`, the robot mascot with a
  message — used in exactly **one file** (`CourseExercisesPage`, both role variants) out of every list
  surface in the app. Elsewhere: `/courses` with no courses renders a **bare empty grid** (nothing at
  all — measured in C5's sweep and pinned by the suite's own `courses-empty-04-empty.png`); the grade
  table's empty text is a joke — *"Kui sel kursusel oleks mõni ülesanne, siis näeksid siin
  hindetabelit :-)"*; the library and dialogs use plain one-line texts. And **none of them, including
  the robot, tells the user the next action** — a teacher with no courses is not told how to get one, a
  grade table without exercises does not link to adding one.
- Instead: one `EmptyState` component (icon/robot, message, optional action button), adopted where the
  lists are. The action line matters more than the artwork: "Lisa esimene ülesanne" on the empty
  exercise list is navigation, not decoration. The `:-)` is a tone decision for kspar — it is at least
  *warm*, which the bare grid is not.
- Evidence: greps at `e58849ca` (`RobotPlaceholder` in 1 feature file; `grades.emptyPlaceholder`
  string); C5 sweep + the suite's own empty-state screenshot for `/courses`. Loading states are the
  same story in miniature — 31 files use bare `CircularProgress`, one uses `Skeleton` — but spinners
  are adequate, so that half is a style-guide line (D2), not a finding
- Register: not previously filed

### X-037 A hard deadline before the soft one saves without a word
- Unit: J5
- Surface: `ExerciseSettingsDialog` (course exercise settings), teacher
- Norm: a form that knows two values are ordered enforces the order (source 5); the dialog already
  validates its other two fields, so the machinery is present (source 2)
- Class: correctness + journey
- Severity: low-medium — an easy mistake with confusing downstream semantics
- Reach: every deadline pair set in the app
- Verdict: CONFIRMED by reading (`ExerciseSettingsDialog.tsx:175-177`)
- What happens: `canSave = thresholdValid && visibleFromValid` — the two deadline pickers are not in
  it, and nothing compares them. A teacher can save `hard_deadline` **before** `soft_deadline` (or
  either in the past, which is sometimes intended but never remarked on). What a hard-before-soft pair
  *means* downstream — submissions rejected before the "soft" deadline arrives — is exactly the kind of
  contradiction a student meets and a teacher cannot explain.
- Instead: one comparison in `canSave` with a field-level error on the hard-deadline picker
  ("Lõpptähtaeg ei saa olla enne pehmet tähtaega"), same pattern as `thresholdValid`.
- Evidence: source at `fc5a3d32`; the dialog's own two validations as the pattern
- Register: not previously filed

### X-038 Choosing a theme once removes "follow the system" forever
- Unit: S6
- Surface: the theme toggle (sidebar menu + Account settings), both themes
- Norm: the app's own embed page, whose `useEmbedTheme` keeps following the OS until overridden and
  documents why in 78 lines (source 2)
- Class: journey
- Severity: low
- Reach: every user who ever touches the toggle
- Verdict: CONFIRMED by reading (`ThemeContext.tsx:10-23`)
- What happens: on first load the app follows `prefers-color-scheme`. The moment a user toggles once,
  `themeMode` is written to localStorage and from then on `getInitialMode` never consults the OS
  again — there is no third state, no "system" option in the toggle, and no way to clear the override
  short of devtools. The app also never *subscribes* to scheme changes, so an OS that switches to dark
  at sunset does nothing even before the first toggle. The embed page does both correctly.
- Instead: a three-state control (Hele / Tume / Süsteemne) where "system" clears the key — or simply
  adopt `useEmbedTheme`'s behaviour, which the codebase already contains and explains.
- Evidence: source at `6edd169d`; contrast with `hooks/useEmbedTheme.ts`
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

`R-006` — **`shape.borderRadius: 12` is contradicted by six component overrides, so the radius
vocabulary is incoherent.** This was a planning lead and it does not survive measurement. The observed
vocabulary on a real page is **8px on controls** (Button, Chip, ListItemButton), **12px on surfaces**
(outlined Paper and Card), **6px on Tooltip** and **50% on avatars and icon buttons** — which is a
scale, not a contradiction: small interactive things are less round than the panels they sit in. It is
*undocumented*, which is a real cost and belongs to **D2**'s guide rather than to a findings list. Two
sub-claims also died: the numbers 8 and 12 inside `styleOverrides` are raw CSS, not `sx` multiples, so
they mean 8px and 12px as written; and the only oddity measured was a single `9px` Box, which is not
worth a line. Radius is one of the more coherent things about this theme.

`R-015` — **Dark mode has a class of hardcoded-fill breakages waiting on the named risk surfaces.**
Read off PNGs at `b039eb07`: it does not. Course colour bars and activity dots read cleanly on dark;
the exercise page's `oneDark` editor, green grade banner and result accordions are coherent; JoinCard
and RobotFace survive; the sponsor plates are R-002's deliberate accommodation. And the one entry in
X-004's sweep table marked "dark mode only" — `pre`/`code` at 3.91:1 — dissolved: its four routes are
exactly R-005's crashed pages, so axe was measuring **React Router's error-page stack trace**, not app
content. Struck from the table. A sweep finding whose route list coincides with the known-crashed set
should always be checked against that set first; this one hid for three units.

`R-014` — **The bare `<Table>`s (participants, library) overflow the page at phone width.** They do
not. Measured across all 23 surfaces at 390×844: **zero horizontal document overflow on every surface
that renders** (the four `+828px` rows in the sweep are R-005's crashed pages — the router error page
is what overflows). The predicted worst case, the participants roster with real fixtures at 390px,
measures a 479px-wide table inside its own scrolling container with `docOverflow: 0`: the table
scrolls, the page does not. The mapping pass's "no `TableContainer`" claim was stale or the page wraps
its table another way; either way the S3 lead dies. Containment at phone width is **clean app-wide**,
which matches R-008's finding for the TSL builder. What phones actually suffer is not overflow but the
S5/X-008/X-025 class — fixed heights and single columns — plus whatever S3's *usability* pass (tap
targets, dialogs) would add; layout containment is not it.

`R-013` — **Getting students into a course is convoluted now that email invites are gone (EZ-1740).**
It is one click. "Loo kutselink" creates the link with sensible defaults (50 uses, one month) and
immediately shows the full URL, *"0 / 50 kasutatud · Kehtib kuni: 26. sept 2026"*, a copy button, and a
*"Näita täisekraanil"* mode for showing the link to a lecture hall — and `EditInviteDialog` adjusts the
limits afterwards. This is the create-with-defaults-then-edit pattern done properly. Two of the first
run's three negative readings were the audit's own: a non-stateful stub answered every GET with "no
invite" so the link could never render, and the probe's regex (`/kehtiv/`) did not match the actual
copy ("Kehtib"). The delete-group dialog from the same run is also worth naming as a model: it lists
every affected student by name.

`R-012` — **There is no visible way for a teacher to add an inline comment on a line of code.** There
is: `AnnotatedCodeEditor` renders a dedicated 28px `.cm-add-comment-gutter` column, and hovering it
reveals a `+` marker per line (`gutter-hovered`, measured `{text: "+", visible: true}`) — the
GitHub-review pattern. The first measurement counted DOM nodes across a hover and saw Δ0, because the
markers exist permanently and are revealed by a CSS class; node-counting is structurally blind to
class toggles. Also verified in the same unit: the whole conversation pipe works — an inline comment,
written teacher feedback, the grade and the teacher's name all reach the student's view, and the
teacher's feed shows the same. The grading conversation is in good shape.

`R-011` — **Grading a cohort is a punishing path: there is no way to reach the next student except
back through the roster, so thirty submissions is thirty round trips.** Refuted twice over. The
grading header has `‹` / `›` chevrons **and** a name dropdown, so moving to the next student is **one
click**, not two. And the roster is genuinely helpful: it carries summary chips reading *"2 lahendatud
· 3 hindamata · 1 esitamata"* and a *"2 / 6 hinnatud"* footer, so a teacher can see how much is left
without counting rows. My driver reported `hasNextAffordance: false` because it searched for *text*
matching `/järgmine|next|edasi/` and the controls are icon-only with no accessible name — the detector
was measuring the absence of a label and I read it as the absence of a feature. The label really is
missing, and that is X-030; the feature is not. Fifth time in this programme that a false negative came
from my setup rather than the app, and the first where the same measurement yielded a real finding
once read correctly.

An earlier attempt at this unit also produced nothing at all because it never stubbed
`/submissions/all/students/{id}` — `StudentGradingView.tsx:459` renders *"Esitamata"* when that list is
empty, so the pane truthfully reported no submissions and the run said `fields=0` about a grading form
that was never asked to render. Both mistakes are recorded in the driver's own comments.

`R-009` — **A teacher-graded submission is never confirmed, because there is no grader to answer.** It
is confirmed: submitting produces a snackbar reading *"Lahendus esitatud"*, and the button label adapts
correctly from *Esita ja kontrolli* to plain *Esita* when `grader_type` is `TEACHER`. The first run of
the driver said otherwise and was worthless — it clicked Submit on an **empty editor**, so no POST
fired and "nothing confirmed the submission" was true only because there was no submission. Typing
first changed the answer completely. Fourth time in this programme that a measurement needed its own
premise checked; the tell was `posted: false` sitting in the output next to a conclusion that assumed
`true`.

`R-010` — **A closed exercise leaves the submit button available.** It does not. `is_open: false`
renders *"See ülesanne on suletud ja ei luba enam uusi esitusi"* at the top of the page and removes the
submit button from the DOM entirely. This is the app handling a state well, and it is the model X-029
asks for on lateness.

`R-008` — **The TSL builder breaks down at 390px, in dark mode, in Estonian — the plan's T6 lead.** It
does not, and this is the most clearly wrong prediction the planning pass made. Measured across
390/1440/2560 × light/dark with a filled-in function-execution test expanded: **zero horizontal
overflow at every combination**, no worst offender at all, and the longest Estonian label in the app
(`tsl.containsName.KEYWORD_WITH_PRECEDING_ARG`, 2.42× its English source) renders in full as *"Programm
otsib reserveeritud võtmesõna koos argumendiga import"* without truncating or wrapping badly. The
three-level nesting reads correctly on a phone because every check is a bordered card, the sections
stack in one column, and the pass/fail message pairs keep their ✓/✗ markers. The `minWidth` values the
lead was suspicious of are doing their job rather than causing overflow. What survives is not a
breakage but a scale problem, filed as X-025 — and 4–5 elements per viewport clip their own content,
which is a mild lead for **S3**/**S8** rather than a finding here.

`R-007` — **`ignoreCase` and `nothingElse` are accepted by the compiler and silently ignored.** They
are not: both reach the generated Python and work. The first run of `t3-model-vs-compiler.mjs` traced
for `ignore_case=True` and found nothing, because check-level flags are emitted as Python **dict
entries** (`'ignore_case':True`) while test-level ones are keyword arguments (`passed_next=222`). Two
findings were one edit away from being wrong in the worst direction — reporting a working feature as
broken. The baseline case is what made it dangerous: it traced `standard_output_checks`, a function
name that is always present, so the detector looked healthy while being unable to see a value. **A
positive control has to exercise the same mechanism the real cases do**, which is the lesson
`doc/testing-log.md` keeps re-teaching and this programme has now paid for twice.

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
  untested one. **X-024 is the proof of that premise**: `tsl.preset.callFunction` and
  `tsl.preset.callsFunction` are byte-identical in `et.json` ("Kutsub välja funktsiooni") while English
  distinguishes them by one letter and a change of grammatical mood ("Call a function" / "Calls a
  function"). A naming scheme that carries meaning in English and collapses in Estonian is the whole
  shape of EZ-1785 in one pair of keys — and worth checking for elsewhere: any English pair
  distinguished only by verb inflection is a candidate.
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
