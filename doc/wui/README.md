# doc/wui — ARCHIVED

Documentation and design material for `:wui`, the Kotlin/JS web UI that served Lahendus until
2026. The module itself was removed from the repo when the React UI under [`web/`](../../web)
took over; the framework it was built on is kept as source in
[`archive/ezspa/`](../../archive/ezspa).

Nothing here describes the current UI. Kept for the record.

## Historical — describes software that no longer exists

- `design/markused.txt` — the original design spec, in Estonian. Segoe UI, `#4DAB54`, a fixed
  1920×1080 layout. None of it applies to the React UI, which uses MUI and its own theme.
- `design/CodeMirrorKasutamine.md` — notes taken while migrating WUI from CodeMirror 5 to 6.
  `web/` is also on CodeMirror 6, so the concepts still rhyme, but the examples here are
  CDN-and-plain-JS against the old WUI setup rather than anything you can paste into `web/`.
- `design/old-design/*.png` — screenshots of the pre-v3 UI.
- `design/bg_old.svg`, `design/lahendus_logo_old.svg` — superseded artwork.

## Original design files for marks still in use

These are the designers' originals for marks the React UI still shows. **Nothing builds from
them** — the live assets are independent, hand-optimised redraws:

| design file | what renders today |
| --- | --- |
| `design/new_logo_final.svg` | `web/src/assets/logo.svg` — a separate, much smaller file |
| `design/auto_icon_final.svg` (robot / auto-grading) | `RobotIcon` in `web/src/components/icons.tsx` — one inline `<path>` |
| `design/missing_feedback_icon_final.svg` | no direct equivalent found in `web/` |
| `design/bg_new_final.svg` | not referenced by `web/` |

Checked rather than assumed: `auto_icon_final.svg` is drawn with `<g>`/`<circle>`/`<rect>`
and shares no path data with `RobotIcon`. So editing anything here changes nothing that
ships — treat these as provenance and as the starting point if a mark is ever reworked, and
expect to re-optimise by hand afterwards.
