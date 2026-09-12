/**
 * What `public/boot-guard.js` puts on `window` (EZ-1908).
 *
 * Both are optional on purpose. The guard is a separate request that a blocked or missing file
 * would leave unanswered, and the app has to boot either way — losing the guard costs an automatic
 * reload, not the application.
 */

interface Window {
  /** Set by `boot-guard.js` once `main.tsx` reports a render. Read by the guard, not by the app. */
  __easyAppBooted?: boolean
  __easyBootGuard?: {
    /** Tell the guard the app is up, so it stops treating a missing asset as a failed boot. */
    booted(): void
  }
}
