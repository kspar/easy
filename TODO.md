# TODOs

A scratchpad for local, in-flight notes — things worth not forgetting while working, but not
worth an issue: cleanups to do before a commit, a spot to revisit, a question to ask someone.
Transient by nature; empty is the normal state.

**Features, bugs, proposals and anything that should survive this working session belong in
YouTrack** — project **EZ** on `easy.youtrack.cloud`. Everything that used to live in this
file has been filed there (EZ-1676 … EZ-1707).

---

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
