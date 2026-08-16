// Shared plumbing for driving web/ pages in a real Chrome with a faked IdP and
// a faked backend. See doc/web/browser-testing.md for the why and the gotchas.
//
// This file holds the parts a spec calls directly — `launch`, `fakeApi`, `waitUntil`. The parts
// the *runner* owns — the `check` recorder, the check-count ratchet, the contract budget — live in
// spec.mjs as Playwright fixtures, because they need the test's identity and its outcome.
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { checkResponse } from './contract.mjs'
import { neverIndex } from './never-index.mjs'

/**
 * Where the specs navigate. Derived the same way playwright.config.ts derives the stub server's
 * port, and it has to stay that way: specs use this constant rather than Playwright's `baseURL`,
 * because they open their own contexts and several of them build absolute iframe URLs from it.
 *
 * The old runner passed `HARNESS_URL` into every script's environment, which is what made
 * `HARNESS_PORT` work. Playwright's `webServer` does not, so the port has to be read here too —
 * otherwise `HARNESS_PORT=5299` starts vite on 5299 and every spec talks to 5199.
 */
export const BASE_URL =
  process.env.HARNESS_URL ?? `http://localhost:${process.env.HARNESS_PORT ?? 5199}`

const HERE = dirname(fileURLToPath(import.meta.url))
export const SHOTS_DIR = join(HERE, '../screenshots')

/**
 * Open a context and a page with localStorage seeded the way the app expects on boot.
 * Returns `{ ctx, page, shot, close }`.
 *
 * **This is a fixture, not an import.** Specs receive it as `async ({ launch }) => …` so that the
 * runner can close whatever a spec forgets, and so the contexts a failing spec left open can be
 * screenshotted before they go. Calling it more than once in a spec is normal and supported — six
 * of them do, to compare a fresh boot against a returning visitor.
 *
 * `close()` closes this context, not the browser. The browser is worker-scoped and shared by every
 * spec that worker runs, so closing it would take the *next* spec down with it — which is why the
 * old `{ browser }` handle is gone rather than renamed.
 *
 * `shot(name)` writes tests/screenshots/<name>.png, logs the path, and attaches it to the report.
 */
export function makeLaunch(browser, testInfo, register) {
  return async function launch({
    role = 'teacher,admin',
    language = 'en', // the app defaults to Estonian — most selectors assume 'en'
    theme = 'light',
    viewport = { width: 1100, height: 800 },
    colorScheme = 'light',
    reducedMotion,
    shotPrefix = '',
  } = {}) {
    // Creates the directory, and keeps Spotlight out of it — see never-index.mjs for the
    // measurement that made that worth doing.
    neverIndex(SHOTS_DIR)

    const ctx = await browser.newContext({
      viewport,
      deviceScaleFactor: 2, // legible screenshots
      colorScheme,
      ...(reducedMotion ? { reducedMotion } : {}),
    })
    const page = await ctx.newPage()

    await page.addInitScript(
      ([role, language, theme]) => {
        localStorage.setItem('stubRole', role)
        localStorage.setItem('activeRole', role === 'student' ? 'student' : 'teacher')
        localStorage.setItem('language', language)
        localStorage.setItem('themeMode', theme)
      },
      [role, language, theme],
    )

    page.on('console', (msg) => {
      if (msg.type() === 'error') console.log(`  [console error] ${msg.text()}`)
    })
    page.on('pageerror', (err) => console.log(`  [page error] ${err.message}`))

    const shot = async (name) => {
      const path = join(SHOTS_DIR, `${shotPrefix}${name}.png`)
      await page.screenshot({ path, fullPage: false })
      console.log(`  📷 ${path}`)
      // Also in the HTML report, so a CI failure is diagnosable without downloading an artifact
      // and guessing which of 90 files belongs to the run that broke.
      await testInfo.attach(`${shotPrefix}${name}`, { path, contentType: 'image/png' })
      return path
    }

    const handle = { ctx, page, shot, close: () => ctx.close() }
    register(handle)
    return handle
  }
}

/** Fulfil a route with JSON. */
export const json = (route, body, status = 200) =>
  route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })

/**
 * String needles that have already been reported as over-broad, so one mis-stubbed handler does not
 * print once per request for the rest of the run.
 */
const reportedBroadStubs = new Set()

/**
 * Warn when a string needle matched a URL with *more path after it*.
 *
 * This is the single most repeated fixture mistake in this suite. `'/teacher/courses'` looks like
 * an endpoint and is also a prefix of every teacher URL on a course, so it quietly answers the
 * single-exercise request with a course list. The page usually does not care — it reads one field,
 * which happens to be absent — so nothing fails, and the only trace is a contract warning naming
 * an endpoint you were not thinking about. It cost about fifteen minutes each of the three times
 * it happened on 2026-08-16.
 *
 * **A trailing slash means you meant it.** `'/submissions/all/students/'` is a deliberate
 * family-of-URLs stub and is silent; `'/submissions/all/students'` is probably a mistake and says
 * so. That gives the two intentions different spellings rather than making the useful one
 * impossible.
 *
 * Printed even when `log: false`, unlike `[unstubbed]`. That flag exists to silence routine
 * chatter about endpoints a spec deliberately does not stub; this is not routine, and suppressing
 * it would leave the one line worth reading invisible in the specs most likely to need it.
 */
export function isOverbroad(needle, path) {
  // A trailing slash is the caller saying "yes, the whole family".
  if (needle.endsWith('/')) return false
  const at = path.indexOf(needle)
  // Not in the path at all — it matched the query string, which is the caller's business.
  if (at < 0) return false
  const rest = path.slice(at + needle.length)
  // Ends here, or mid-segment: either way nothing was swallowed.
  return rest.startsWith('/')
}

function warnIfOverbroad(needle, url) {
  let path
  try {
    path = new URL(url).pathname
  } catch {
    return
  }
  if (!isOverbroad(needle, path)) return

  const key = `${needle}::${path}`
  if (reportedBroadStubs.has(key)) return
  reportedBroadStubs.add(key)
  console.log(
    `  [broad stub] '${needle}' also matched ${path} — it is answering a deeper endpoint than it ` +
      `names. Anchor it (a regex ending in \`(\\?|$)\`), or end the needle with '/' if matching the ` +
      `whole family is deliberate.`,
  )
}

/**
 * Install a fake backend. `handlers` is an array of [urlSubstring, handler]
 * pairs; handler receives ({ route, url, method, body }) and returns the body to
 * send, or calls route.fulfill itself and returns undefined.
 *
 * Handlers are tried in order, so put the most specific first — and prefer an anchored regex over
 * a bare substring for anything that is a prefix of another endpoint. [warnIfOverbroad] prints a
 * line when a needle turns out to be one.
 *
 * Everything unmatched is fulfilled with {} — an unstubbed request otherwise
 * leaves the page loading forever with no error.
 */
export async function fakeApi(page, handlers, { log = true, contract = true } = {}) {
  const calls = []

  await page.route('**/v2/**', async (route) => {
    const req = route.request()
    const url = req.url()
    const method = req.method()
    let body
    try {
      body = req.postDataJSON()
    } catch {
      body = undefined
    }
    calls.push({ url: url.replace(/^.*\/v2/, ''), method, body })

    for (const [needle, handler] of handlers) {
      const matches =
        typeof needle === 'string' ? url.includes(needle) : needle.test(url)
      if (!matches) continue
      if (typeof needle === 'string') warnIfOverbroad(needle, url)
      const result = await handler({ route, url, method, body })
      if (result === undefined) return // handler fulfilled it
      if (contract) recordContractIssues(method, url, result, body)
      return json(route, result)
    }

    if (log) console.log(`  [unstubbed] ${method} ${url.replace(/^.*\/v2/, '')}`)
    return json(route, {})
  })

  return calls
}

/**
 * Contract issues found while answering stubbed requests, collected across the whole spec.
 *
 * Module-level rather than returned from `fakeApi`, so that the runner can report them without any
 * spec having to ask. That is what makes this cover all 27 specs without editing one of them — a
 * spec that had to opt in would mean 27 edits now and a tax on writing the 28th.
 *
 * A Playwright worker runs many specs in one process and tests within a worker never overlap, so
 * one array is safe — provided it is emptied between them, which [takeContractIssues] does.
 */
let contractIssues = []

/** Hand the collected issues to the runner and start the next spec clean. */
export function takeContractIssues() {
  const taken = contractIssues
  contractIssues = []
  // Reset alongside them, and for the same reason. A Playwright worker runs many specs in one
  // process, so without this the *first* spec to trip a given needle silences that warning for
  // every spec after it — and four specs share COURSE=119/CE=4147, so the overlap is real. CI runs
  // `workers: 1`, which makes one process the whole suite.
  reportedBroadStubs.clear()
  return taken
}

function recordContractIssues(method, url, responseBody, requestBody) {
  try {
    contractIssues.push(...checkResponse(method, url, responseBody, requestBody))
  } catch (e) {
    // Its own severity, not 'warn'. Recording a checker crash as a warning was actively harmful in
    // both directions: for a spec with a budget of 0 it read as "new fixture drift" and pointed
    // the reader at the wrong file, and for a spec with a budget of 40 it collapsed 40 real
    // warnings into 1, printed "below budget — lower this spec's entry", and invited somebody to
    // ratchet the whole mechanism down to nothing. A broken checker fails, and says it is broken.
    contractIssues.push({ severity: 'broken', message: `contract checker itself failed: ${e.message}` })
  }
}

/**
 * Poll `predicate` until it returns truthy or the timeout expires; resolves to the final result.
 *
 * Prefer this over `waitForTimeout` + assert for anything behind a debounce. A fixed sleep that
 * is comfortable on a laptop is a coin flip on a loaded CI runner, and when it loses the failure
 * reads like a product bug rather than a slow machine.
 */
export async function waitUntil(predicate, { timeout = 8000, interval = 100 } = {}) {
  const deadline = Date.now() + timeout
  for (;;) {
    const result = await predicate()
    if (result) return result
    if (Date.now() >= deadline) return result
    await new Promise((r) => setTimeout(r, interval))
  }
}
