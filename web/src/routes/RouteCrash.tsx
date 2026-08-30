import { isRouteErrorResponse, useRouteError } from 'react-router-dom'
import CrashScreen from '../components/CrashScreen.tsx'

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
  return <CrashScreen error={asError(error)} />
}
