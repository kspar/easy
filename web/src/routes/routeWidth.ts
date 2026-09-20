import { useMatches } from 'react-router-dom'

/**
 * How much of the window a route's page may use — EZ-1915.
 *
 * The shell used to cap every page at `lg` (1200px), which on a 2560 monitor is 47% of the window.
 * That is right for a list or a form: a row is a name on the left and a control on the right, and
 * widening it only moves the two apart. It is wrong for the pages where width is the work — code,
 * the grade matrix, two solutions side by side — which sat in the same 1200px beside a thousand
 * empty pixels. One number cannot suit both, so it is a property of the route.
 *
 * Declared on the route rather than by the page, so the shell knows before the page renders and a
 * page never has to reach up into its layout. Capped is the default on purpose: a new route that
 * says nothing gets the width that is safe for prose.
 */
export type RouteHandle = { width?: 'wide' }

export const WIDE: RouteHandle = { width: 'wide' }

/** Whether the matched route asked for the whole window. */
export function useIsWideRoute(): boolean {
  return useMatches().some((m) => (m.handle as RouteHandle | undefined)?.width === 'wide')
}
