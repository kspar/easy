# TODOs

A scratchpad for local, in-flight notes — things worth not forgetting while working, but not
worth an issue: cleanups to do before a commit, a spot to revisit, a question to ask someone.
Transient by nature; empty is the normal state.

**Features, bugs, proposals and anything that should survive this working session belong in
YouTrack** — project **EZ** on `easy.youtrack.cloud`. Everything that used to live in this
file has been filed there (EZ-1676 … EZ-1707).

---

## `npm run build` in web/ is broken — 18 TypeScript errors

Found while removing `:wui` (2026-07-31), unrelated to that change — confirmed pre-existing by
re-running `tsc -b` against the previous commit's `package.json`. `npm run dev` is fine because
Vite doesn't typecheck; only `npm run build` (`tsc -b && vite build`) fails, so this has been
rotting unnoticed. Nothing can be released from `web/` until it's fixed.

Reproduce: `cd web && npx tsc -b --force`

Five clusters:

- **`erasableSyntaxOnly` violations (5)** — `api/client.ts:12-13`,
  `course-exercise/AnnotatedCodeEditor.tsx:130,131,230`. All constructor parameter properties
  (`constructor(public status: number, ...)`). Declare the fields and assign in the body.
- **`RelativeTime.tsx` (3)** — `Locale` used as a type without importing it
  (`import type { Locale } from 'date-fns'`), and the local `t` param is typed
  `(key: string) => string` so the `{ count }` interpolation calls don't typecheck. Type it as
  i18next's `TFunction`.
- **"Expected 1 arguments, but got 0" (4)** — `ActivityFeed.tsx:219,628`,
  `AnnotatedCodeEditor.tsx:730`, `PreviousSubmissions.tsx:54`. Not yet diagnosed; likely one
  shared helper that gained a required parameter.
- **`AppLayout.tsx:169,184`** — `courses` possibly `undefined`, needs a guard.
- **Two unused imports** — `useCallback` in `AutogradeAnimation.tsx:1`, `useState` in
  `useRecentExercises.ts:1`.
- **`utils/jwt-proxy.ts:9`** — `Buffer` with no Node types. This file runs in the Vite dev
  server, not the browser, so it wants `@types/node` plus `"node"` in the right tsconfig's
  `types` — worth checking it's scoped to `tsconfig.node.json` and not leaked to app code.

Once green, worth deciding how to keep it green — `tsc -b` isn't in CI (`.github/workflows`
only runs Qodana), which is how 18 errors accumulated.

## Audit icon usage and write down what each icon means

Prompted by a real collision: the teacher course-exercise list used a clock
(`ScheduleOutlined`) for "becomes visible at", but a clock face was our **deadline** icon in
the old WUI (`Icons.pending`). Fixed on the list by moving to `VisibilityOutlined`, but nothing
stops the next page from re-overloading it — there's no written convention to check against.

To do:
- Sweep every `@mui/icons-material` import across `web/src` and list which icon is used for
  what. (The WUI `Icons.kt` set is gone with the module — no longer a source to reconcile
  against, only whatever precedent survives in `web/`.)
- Flag the collisions and the synonyms — same meaning drawn with different icons is as
  confusing as one icon meaning two things.
- Write the result up as a short table in `doc/web/` — icon → meaning → don't-use-for. Enough
  that a reviewer can say "that's the deadline icon" without guessing.
- Candidates already known to be worth deciding on:
  - clock family (`ScheduleOutlined`, `AccessTime`, `HourglassEmptyOutlined`, `TimerOutlined`)
    — deadline vs closing time vs ungraded/pending are distinct concepts sharing a metaphor
  - eye family (`VisibilityOutlined` / `VisibilityOffOutlined`) — visible / hidden /
    scheduled-visible, currently eye + a date for the third
  - `RobotIcon` / `TeacherFaceIcon` — auto vs teacher grading; consistent so far, keep it
- Also worth noting the repo already has a rule that outlined icons are the default and filled
  ones mean active/selected (see CLAUDE.md) — the doc should restate that in one place.

If this turns out to be more than an afternoon, file it in EZ instead of leaving it here.
