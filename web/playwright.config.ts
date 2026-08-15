/**
 * The browser suite: real Chromium, real router and components, backend faked by route
 * interception. See doc/web/browser-testing.md.
 *
 *   npx playwright test                 # everything (what CI runs)
 *   npx playwright test grade-table     # one spec — the filter is a path substring
 *   npx playwright test --ui            # pick, watch and time-travel through them
 *   npx playwright test --headed        # watch it happen
 *
 * Two settings below are deliberate departures from the framework's defaults, both backed by
 * measurements in this repo rather than taste. They are the first two things a newcomer will want
 * to "fix", so the reasons are here rather than in a commit message.
 */
import { defineConfig } from '@playwright/test'

const PORT = Number(process.env.HARNESS_PORT ?? 5199)
const BASE_URL = process.env.HARNESS_URL ?? `http://localhost:${PORT}`

// Locally this drives the Chrome that's already installed, so there's no 130MB download. CI sets
// HARNESS_BROWSER_CHANNEL='' to use Playwright's own chromium instead, which pins the browser to
// the @playwright/test dependency rather than to whatever the runner image ships.
const channel = process.env.HARNESS_BROWSER_CHANNEL ?? 'chrome'

export default defineConfig({
  testDir: './tests/browser',
  // JavaScript on purpose. These specs are driving a browser, not being typechecked, and Playwright
  // does not typecheck .ts specs either — a .ts extension here would buy nothing but the belief
  // that something was checking them.
  testMatch: '**/*.spec.mjs',

  // One test per file today, so file-level parallelism is all there is. Left off rather than
  // removed, because splitting a spec into several tests is a natural follow-up and this is the
  // line that would then need thinking about: the specs share a screenshot prefix and a check
  // count, neither of which survives being run out of order.
  fullyParallel: false,

  /**
   * Two locally, **one in CI**, and the asymmetry is the point.
   *
   * Locally, measured on an M-series laptop: at 2 the suite is roughly 3x faster than sequential
   * and every spec still passes; at 4, seven specs failed and every per-spec time roughly doubled,
   * because each drives its own Chromium at deviceScaleFactor 2. Those failures are pure
   * contention and look exactly like real ones. The framework's default is cores/2, which on a
   * bigger machine is well past that wall.
   *
   * CI stays sequential because that is what it has always done — the old runner defaulted to
   * `--jobs 1` — and because `retries: 0` makes a contention flake cost a red gate rather than a
   * re-run. The measurement above is a laptop measurement; a shared runner is not that machine.
   * `library-exercise-tsl-static` is the one that proves it: it drives a debounced code editor
   * through ~90 compiles and it is the first thing to miss a wait when something else is running.
   *
   * Override with PW_WORKERS when you want to time something.
   */
  workers: Number(process.env.PW_WORKERS ?? (process.env.CI ? 1 : 2)),

  /**
   * KEEP THIS AT 0. A retry converts an intermittent failure into a green build plus a log line
   * nobody reads, and destroys the one number a deploy gate needs. Decisive here: the flakes this
   * suite will have are mostly *product* timing bugs — a 90ms redirect, a poll that stops, refetch
   * races — exactly the class a retry hides. The escape hatch for a genuinely unstable check is
   * tests/quarantine.json, which names an issue and expires on its own.
   */
  retries: 0,

  // A spec is a whole user journey, not one assertion, and the longest drives a code editor
  // through a dozen states. Generous enough not to be a source of false failures on a loaded
  // runner; finite so a hung page still ends the run.
  timeout: 180_000,

  forbidOnly: !!process.env.CI,
  globalSetup: './tests/support/global-setup.mjs',

  reporter: [
    ['list'],
    // Consumed by the deploy gate: "the run is green" has to be answerable from an artifact
    // rather than from a human reading logs. See doc/testing.md, "What green means".
    ['junit', { outputFile: 'test-results/junit.xml' }],
    ['html', { open: 'never' }],
  ],

  // Playwright's default for both of these is "no limit" — bounded only by the test timeout. That
  // default is actively bad for a gate: a locator that never becomes actionable then burns the
  // whole 180 seconds and reports "Test timeout exceeded" with no line number. That is exactly what
  // library-exercise-tsl-static did during the migration, twice, and it cost an hour to place;
  // bounded, the same stall is a named locator and a stack frame in fifteen seconds. Both numbers
  // are far above anything this app legitimately takes.
  expect: { timeout: 10_000 },

  use: {
    baseURL: BASE_URL,
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    ...(channel ? { channel } : {}),
    // NOTE: `trace` and `screenshot` here would do nothing. They apply to the page fixture, and
    // every spec opens its own context through `launch()` instead — six of them open several. The
    // equivalent lives in tests/support/spec.mjs, keyed off PW_TRACE.
  },

  webServer: {
    // The keycloak-js alias in vite.stub.config.ts is the only genuinely hard part of driving this
    // app with no IdP, and it is why this is a separate vite config rather than a flag.
    command: `npx vite --config vite.stub.config.ts --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
