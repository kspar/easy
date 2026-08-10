/**
 * Build-time constants injected by Vite's `define` (EZ-1709). See `vite.config.ts`.
 *
 * These are substituted textually at build time, so they exist in every bundle and need no runtime
 * fetch — web is the one component that can report its own version without asking core.
 */

/** Product version from the repo-root `VERSION` file, e.g. `4.0`. */
declare const __APP_VERSION__: string
/** Short commit the bundle was built from, e.g. `b14b916`, or `unknown`. */
declare const __APP_COMMIT__: string
/** ISO timestamp of the build. */
declare const __APP_BUILT_AT__: string
