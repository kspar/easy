import { useLayoutEffect, useState } from 'react'

/**
 * The space between the bottom of `el` and the bottom of the document — the page container's own
 * padding, in practice — read off the ancestors' box properties rather than off the rendered
 * geometry.
 *
 * Geometry cannot answer this once the frame is in place. The first attempt asked
 * `scrollHeight - (top + height)`, which is right while the content overflows and badly wrong when
 * it does not: `scrollHeight` never drops below the viewport, so an empty page reports its unused
 * space as padding, the frame shrinks to make room for it, which leaves more unused space. It
 * settled at a frame 56px tall. Padding and margins are the same whatever the content does.
 *
 * Assumes the frame is the last thing on the page, which it is everywhere this is used.
 */
function spaceBelow(el: HTMLElement): number {
  let total = 0
  for (let node: HTMLElement | null = el.parentElement; node && node !== document.documentElement; node = node.parentElement) {
    const style = getComputedStyle(node)
    total += parseFloat(style.paddingBottom) + parseFloat(style.borderBottomWidth) + parseFloat(style.marginBottom)
  }
  return total
}

/**
 * The height a page may occupy, measured from where it actually starts rather than assumed — so
 * that what is inside scrolls and the window does not (EZ-1835 for the exercise page, EZ-1919 for
 * the grade table).
 *
 * `calc(100vh - 48px)` was the old guess, and it was wrong by the height of everything between the
 * app bar and the panes — the title row — so the statement pane ran past the bottom of the window.
 * Measuring also survives what moves the frame's top edge at runtime: a system message or update
 * banner appearing above the app bar, and a long title wrapping onto a second line when the window
 * narrows. The first changes the page's height, which is why `document.body` is watched; the
 * second does not, which is why the header is too.
 *
 * `frameSx` fills the height outright, for a page that is a frame. `frameHeight` is the same
 * length on its own, for something that should only be *limited* to it — a table two rows long
 * must not be stretched to the bottom of the window. Both are `undefined` while disabled or
 * unmeasured, which leaves the page in ordinary document flow.
 */
export default function useFrameHeight(enabled: boolean) {
  // Callback refs, held in state rather than in a ref object: the frame only exists once the
  // data has loaded, and a `useRef` would still be null on the mount this effect runs in —
  // leaving the page unframed for the rest of its life with nothing to re-trigger the measurement.
  const [frameEl, setFrameEl] = useState<HTMLDivElement | null>(null)
  const [headerEl, setHeaderEl] = useState<HTMLDivElement | null>(null)
  const [top, setTop] = useState<number | null>(null)

  useLayoutEffect(() => {
    // No `setTop(null)` here: whether the frame applies is read off `enabled` below, so the
    // disabled path has nothing to write, and a stale measurement is re-taken on the way back in.
    if (!enabled || !frameEl) return
    const measure = () => {
      // Plus the scroll offset: the rect is viewport-relative, and the first measurement is taken
      // while the page is still an ordinary scrolling document.
      const above = frameEl.getBoundingClientRect().top + window.scrollY
      setTop(Math.round(above + spaceBelow(frameEl)))
    }
    measure()

    const observer = new ResizeObserver(measure)
    observer.observe(document.body)
    if (headerEl) observer.observe(headerEl)
    window.addEventListener('resize', measure)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', measure)
    }
  }, [enabled, frameEl, headerEl])

  const frameHeight = !enabled || top == null ? undefined : `calc(100dvh - ${top}px)`
  return {
    frameRef: setFrameEl,
    headerRef: setHeaderEl,
    frameHeight,
    frameSx: frameHeight == null
      ? undefined
      : { height: frameHeight, minHeight: 0, display: 'flex', flexDirection: 'column' as const },
  }
}
