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
 */
export function spaLinkProps(href: string, navigate: (to: string) => void) {
  return {
    href,
    onClick: (e: MouseEvent) => {
      // Anything but a plain left click is the browser's business: ctrl/cmd opens a new tab, shift
      // a new window, and `button !== 0` covers middle-click paste-and-go.
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
      e.preventDefault()
      navigate(href)
    },
  } as const
}
