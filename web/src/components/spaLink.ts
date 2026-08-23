import type { MouseEvent } from 'react'

/**
 * Props that make an element a real link which react-router still handles in-page.
 *
 * **Every navigable thing in this app must support ctrl/cmd+click**, and a bare
 * `onClick={() => navigate(...)}` does not: there is no `href`, so the browser has nothing to open
 * in a new tab, nothing to show in the status bar, nothing to copy from the context menu, and
 * nothing for a screen reader to announce as a link. It also silently swallows middle-click.
 *
 * The failure is invisible in every test that only clicks normally, and invisible in review because
 * the handler looks correct — it navigates, after all. It shows up as "why can't I open two courses
 * side by side", which people work around rather than report.
 *
 * So: a genuine `href`, and an `onClick` that only intercepts a plain left click. Modifier clicks
 * and middle clicks fall through to the browser, which does the right thing with the `href`.
 *
 * Lived in `GradeTablePage.tsx` until 2026-08-16, where it was correct and unreachable — the
 * course cards on `/courses`, the first screen of every session, were still bare `onClick`s.
 *
 * **And it happened again.** By 2026-08-23 there were four implementations of this function —
 * here, in `features/library/links.ts`, and inlined inside `SimilarityPage` and
 * `ExerciseLibraryPage` — plus two more hand-rolled copies of the modifier check in `AppLayout` and
 * `GradeTablePage`, while the sidebar that every page renders used none of them and navigated with a
 * bare `onClick`. Four copies is why: the fix was always available and never obviously missing.
 * This is now the only implementation, and `links.ts` re-exports it rather than defining its own.
 *
 * For a `ListItemButton`, `MenuItem` or anything else MUI, prefer `component={RouterLink} to={…}`:
 * react-router's own Link produces the `href` and leaves modifier clicks to the browser, so it needs
 * none of this. These props are for a plain anchor, where there is no component slot to fill.
 */
export function spaLinkProps(
  href: string,
  navigate: (to: string) => void,
  /**
   * For a link inside something else clickable — a table row that navigates on its own — so the
   * anchor's click does not also trigger the row's handler and navigate twice.
   */
  stopPropagation = false,
) {
  return {
    href,
    onClick: (e: MouseEvent) => {
      if (stopPropagation) e.stopPropagation()
      // Anything but a plain left click is the browser's business: ctrl/cmd opens a new tab, shift
      // a new window, and `button !== 0` covers middle-click paste-and-go.
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
      e.preventDefault()
      navigate(href)
    },
  } as const
}
