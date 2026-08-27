# The web UI guide

How `web/` looks and behaves, written down. Until 2026-08 the only UI conventions document lived
outside the repo, in one contributor's tooling memory — this file is those conventions plus what the
EZ-1791 UI/UX audit established, in checklist form. **Every rule carries a "check" line**: a command or
a driver invocation that tells you whether the rule holds, because a rule nobody can check is a rumour.

Two companion documents: `doc/web/browser-testing.md` (the harness, and "screenshots are the point"),
and `doc/web/ux-audit-log.md` (the evidence behind most rules here, cited as `X-…`/`R-…`).

The audit drivers referenced below live in `web/tests/audit/` and run against a stub server:

```sh
cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
HARNESS_PORT=5299 node tests/audit/<driver>.mjs
```

---

## Conventions with a canonical implementation

Each rule names the file that already does it right. **Copy that file.**

| Rule | Canonical | Check |
|---|---|---|
| **Outlined** MUI icons by default; filled only for active/selected states | anywhere in `src/` | icon census: 94.6% outlined at `0cf2d952`; `grep -rhoE "from '@mui/icons-material/\w+'" src \| grep -v Outlined` |
| **British English** dates — `enGB` from `date-fns/locale`, never `enUS` | — | `grep -rn enUS src` must return nothing |
| Everything navigable is a **real link**: `component={RouterLink}` on MUI components, `spaLinkProps()` on plain anchors. Bare `onClick` is for *actions* only | `components/spaLink.ts`; guarded by `nav-cmd-click.spec.mjs` | middle-click it |
| Sorting: `TableSortLabel` in tables; outlined `Button` + Menu elsewhere | `ExerciseLibraryPage.tsx` | look |
| Filtering: `Chip` + Menu, labelled by the **category name**, not "All" | `ExerciseLibraryPage.tsx` | look |
| Group-filter selection persists per course | `hooks/useSavedGroup.ts` | switch pages, filter should survive |
| Single-field dialogs: `autoFocus` + Enter-submit with `isPending` guard **and** `TransitionProps.onEntered` refocus — MUI's focus trap steals a bare `autoFocus` | `CreateDirDialog.tsx` | open the dialog, type immediately; review E5 found this broken in 4 of 12 dialogs |
| **Icon-only controls carry an `aria-label`** (and usually a tooltip) | `GradeTablePage.tsx:172` (`general.back`) | `tests/audit/c5-a11y-sweep.mjs` — `button-name`/`link-name` are gate-level; the audit found 16 of 17 back arrows unlabelled (X-003) and the grading chevrons as the only unlabelled two of nine (X-030) |
| Destructive dialogs **name what will be destroyed** | the delete-group dialog in `ParticipantsPage` ("Need õpilased eemaldatakse rühmast: …") | read the dialog; X-032 is the counterexample |
| Deleting wants **undo**; changing-with-loss wants **confirm** | — (X-021's proposal) | a silent irreversible one-click edit fails both |
| Unsaved work is guarded on **every** exit, including router navigation (`useBlocker`), not only `Cancel`/`beforeunload` | the string exists: `library.unsavedChangesConfirm` | X-001/X-017: type, navigate via sidebar, expect a warning |
| MUI dialogs, never `window.confirm`/`window.prompt` | any of the 19 dialogs | `grep -rn "window.confirm\|window.prompt" src` (X-042's sites are the debt) |
| No prettier — house style is no semicolons, single quotes; `npm run lint` is the gate | `eslint.config.js` | `npm run lint` |

## The theme (`src/theme/theme.ts`)

What actually switches with mode: `background`, `text`, `divider`, and per-component borders. What
does **not**: every semantic colour. Two consequences the audit measured:

- **The green is decided: one green, `GREEN[700]` `#15803d`** (kspar, 2026-08-28 — X-012, two-green
  proposal rejected). White-on-it passes AA in both modes (5.02:1); as text it passes light and is
  large-text-only on dark surfaces (3.74/3.32) — so **small green text on dark uses a lighter ramp
  step via `primary.light`**, a shade rule, not a second green. The same green propagates to the
  manifest, `index.html` and `easy-kc-theme`, retiring the other three green families. Do not "fix"
  green contrast locally — it is this token decision.
- **`text.secondary` (`#757575`) passes on paper, fails on the page background** at small sizes
  (4.23:1 — X-013). Use it for metadata and captions only, never running text.
- Six palette entries are **dead**: the whole `secondary` triplet and all four `*.light` tints (R-001,
  X-014). Do not reach for them; they are mode-blind near-whites.
- The **radius vocabulary is intentional** (R-006): 8px interactive controls, 12px surfaces, 6px
  tooltips, 50% avatars. Follow it.
- The `shadows` scale is dead — cards are outlined, nothing elevates (X-014). Do not start elevating.
- The doubled `:focus-visible:focus-visible` rule in `MuiCssBaseline` is load-bearing specificity —
  read its comment before touching it.

Check: `node tests/audit/s1-token-contrast.mjs` recomputes every ratio.

## Type hierarchy

Page title = `h5` today, hand-rolled in 17 places (X-041). Until a shared `PageHeader` exists: page
title `h5`, section/card title `h6`, and **never** introduce a fourth level ad hoc. `text.secondary`
is not a hierarchy tool.

## Viewports

The app is tested and known-good for **containment** at: **390×844**, **768×1024**, **1440×900**,
**2560×1440** (R-014: zero horizontal overflow app-wide; the page never scrolls sideways — a wide
table scrolls in its own container, see `GradeTablePage`'s `TableContainer` + sticky first column).
Two standing debts to not repeat: don't fix an element's height (X-008's 200px editor), and don't
assume `lg` is the biggest screen (X-025, EZ-1527 — content caps at 1200px on a 2560 display).

Check: `HARNESS_PORT=5299 node tests/audit/s345-viewport-sweep.mjs`.

## Loading, empty, error

- **Loading**: `CircularProgress` is the app's idiom (31 files); `Skeleton` only where layout jump
  hurts (`StudentGradingView`). Either is fine; pick one per surface.
- **Empty**: an empty screen is an invitation to act. Message + **next action** ("Lisa esimene
  ülesanne"), robot optional (`RobotPlaceholder`). The bare empty grid on `/courses` is the
  counterexample (X-036).
- **Error copy**: map `ApiResponseError.errorBody.code` where a sentence exists — `ShareDialog`'s
  `ACCOUNT_NOT_FOUND` branch is the pattern — and fall back to `general.somethingWentWrong` (X-035).
  Never render a raw exception string to a user (X-018, X-026): raw detail goes behind a "Details"
  disclosure, and infrastructure failures are *not the user's fault and must say so*.
- **Route errors**: `routes.tsx` defines no `errorElement`, so today a render crash shows React
  Router's developer page instead of `CrashScreen` (X-009). When fixed: `CrashScreen` is the boundary
  for routes; the outer `ErrorBoundary` in `App.tsx` stays as last resort (its placement comment is
  correct).

## Accessibility floor

Gate-level (would fail CI if the route were wired to the `a11y` fixture): accessible names on all
controls and inputs — including CodeMirror's `.cm-content` (X-002) — valid list structure (X-010), a
`main` landmark on every page including those outside `AppLayout` (X-011). Run
`tests/audit/c5-a11y-sweep.mjs` after touching chrome; **always trust its canary line before its
zeros** — `a11y.scan()` returns `{gate, contrast}`, and reading fields that don't exist reports a
clean page (the audit did it once).

Motion: honour `prefers-reduced-motion` the way `JoinCard` and `RobotFace` do; the autograde reveal is
the standing violation (X-006/X-007).

## Estonian first

The app defaults to Estonian and every spec runs in English, so **judge layout and naming in
Estonian**. 137 of 858 strings are ≥30% longer in `et`; and a naming scheme that only distinguishes
things in English is broken (X-024: "Call a function" / "Calls a function" are both "Kutsub välja
funktsiooni"). Check: `launch({ language: 'et' })` in any driver.

## Fixtures, when driving the app

Lessons the audit paid for, so you don't:

- **Shapes come from `api/types.ts` / `api-shapes.json`, never memory** — seven invented fixtures were
  wrong (R-004, R-005 and friends). A non-nullable field stubbed as `null` crashes the route and then
  *everything* you measure is the error page (R-015).
- The grading pane needs `/submissions/all/students/{id}` or it truthfully renders "Esitamata".
- Cache behaviour needs **client-side navigation** — one `goto()` wipes what you're testing (X-031's
  driver).
- Hover affordances toggle CSS classes, not DOM nodes (R-012); positive controls must exercise the
  same mechanism as the real case (R-007).
