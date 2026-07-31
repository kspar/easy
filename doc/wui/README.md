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

## Still-live source artwork

These are the source SVGs for marks the React UI still draws (as
`web/src/assets/logo.svg` and inline icon components). Edit these if the marks ever change:

- `design/new_logo_final.svg`
- `design/auto_icon_final.svg` — the robot / auto-grading mark
- `design/missing_feedback_icon_final.svg`
- `design/bg_new_final.svg`
