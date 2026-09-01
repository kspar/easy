import { useEffect } from 'react'
import { isRouteErrorResponse, useRouteError } from 'react-router-dom'
import CrashScreen from '../components/CrashScreen.tsx'
import { record } from '../features/bug-report/breadcrumbs.ts'

/**
 * Whatever was thrown, as an Error whose message still says something. `useRouteError()` is not
 * guaranteed an Error: a route can throw a Response-like ErrorResponse or a plain object, and
 * `String({})` is "[object Object]" — which would then be the entire content of the one-click
 * bug report this screen exists to capture.
 */
function asError(thrown: unknown): Error {
  if (thrown instanceof Error) return thrown
  if (isRouteErrorResponse(thrown)) {
    return new Error(`${thrown.status} ${thrown.statusText}${thrown.data ? ` — ${JSON.stringify(thrown.data)}` : ''}`)
  }
  if (typeof thrown === 'string') return new Error(thrown)
  try {
    return new Error(JSON.stringify(thrown))
  } catch {
    return new Error(String(thrown))
  }
}

/**
 * The `errorElement` for the route tree (audit X-009). Without one anywhere, React Router catches
 * every route render error itself and shows its *default* boundary — "Unexpected Application
 * Error!" with a raw exception message, untranslated, and no shell — while the app's own
 * translated CrashScreen with its one-click bug report never renders. The deliberately-outer
 * `ErrorBoundary` in App.tsx stays what it was written to be: the last resort for throws that
 * escape the router entirely.
 */
export default function RouteCrash() {
  const error = useRouteError()
  const asErrorValue = asError(error)

  /**
   * Into the activity buffer, the way `ErrorBoundary` does for the throws it catches.
   *
   * The two boundaries catch disjoint sets — a throw the router handles never reaches the outer
   * one — so a route crash was reaching a report only as whatever React happened to write to
   * `console.error`, which is a warning about a component tree rather than the error with its
   * stack. The reporter is looking at this screen while they file, so the line that says what
   * broke should be the first thing in their log.
   *
   * In an effect, so React's double-invoked render in development does not record it twice.
   */
  useEffect(() => {
    const stack = asErrorValue.stack?.split('\n').slice(1, 4).map((l) => l.trim()).join(' < ')
    record('error', `route crash: ${asErrorValue.message}${stack ? ` | ${stack}` : ''}`)
  }, [asErrorValue.message, asErrorValue.stack])

  return <CrashScreen error={asErrorValue} />
}
