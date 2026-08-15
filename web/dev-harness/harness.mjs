// Shared plumbing for driving web/ pages in a real Chrome with a faked IdP and
// a faked backend. See doc/web/browser-testing.md for the why and the gotchas.
import { chromium } from 'playwright'
import { fileURLToPath } from 'node:url'
import { basename, dirname, join } from 'node:path'
import { mkdirSync, readFileSync } from 'node:fs'
import { checkResponse } from './contract.mjs'

export const BASE_URL = process.env.HARNESS_URL ?? 'http://localhost:5199'

const HERE = dirname(fileURLToPath(import.meta.url))
export const SHOTS_DIR = join(HERE, 'screenshots')

/**
 * Launch Chrome and open a page with localStorage seeded the way the app expects
 * on boot. Returns { browser, ctx, page, shot }.
 *
 * `shot(name)` writes screenshots/<name>.png and logs the path.
 */
export async function launch({
  role = 'teacher,admin',
  language = 'en', // the app defaults to Estonian — most selectors assume 'en'
  theme = 'light',
  viewport = { width: 1100, height: 800 },
  colorScheme = 'light',
  reducedMotion,
  shotPrefix = '',
} = {}) {
  mkdirSync(SHOTS_DIR, { recursive: true })

  // Locally this drives the Chrome that's already installed, so there's no 130MB download.
  // CI sets HARNESS_BROWSER_CHANNEL='' to use Playwright's own chromium instead, which pins the
  // browser to the playwright dependency rather than to whatever the runner image ships.
  const channel = process.env.HARNESS_BROWSER_CHANNEL ?? 'chrome'
  const browser = await chromium.launch(channel ? { channel } : {})
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
    return path
  }

  return { browser, ctx, page, shot }
}

/** Fulfil a route with JSON. */
export const json = (route, body, status = 200) =>
  route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })

/**
 * Install a fake backend. `handlers` is an array of [urlSubstring, handler]
 * pairs; handler receives ({ route, url, method, body }) and returns the body to
 * send, or calls route.fulfill itself and returns undefined.
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
 * Contract issues found while answering stubbed requests, collected across the whole script.
 *
 * Module-level rather than returned from `fakeApi`, so that `check.summary()` can report them
 * without any script having to ask. That is what makes this retrofit all 28 existing scripts
 * without editing one of them — a script that had to opt in would mean 28 edits now and a tax on
 * writing the 29th.
 */
const contractIssues = []

/**
 * This script's allowed number of contract warnings, or null if it has no entry.
 *
 * Keyed by script filename, read fresh each run. A script with no entry is not ratcheted — new
 * scripts should not have to think about this on the day they are written, and the entry gets added
 * once its number settles.
 */
function contractBudget() {
  const script = basename(process.argv[1] ?? '')
  // Deliberately NOT wrapped in a try. A bare catch here could not tell "this script has no entry"
  // — the intended case — from "the baseline file is unreadable", and the second silently turns the
  // ratchet into a no-op for all 27 scripts while the suite stays green. That is precisely the
  // failure the ratchet exists to prevent, so a trailing comma or a bad merge must be loud.
  const budgets = JSON.parse(readFileSync(join(HERE, 'contract-baseline.json'), 'utf8'))
  return Object.prototype.hasOwnProperty.call(budgets, script) ? budgets[script] : null
}

function recordContractIssues(method, url, responseBody, requestBody) {
  try {
    contractIssues.push(...checkResponse(method, url, responseBody, requestBody))
  } catch (e) {
    // Its own severity, not 'warn'. Recording a checker crash as a warning was actively harmful in
    // both directions: for a script with a budget of 0 it read as "new fixture drift" and pointed
    // the reader at the wrong file, and for a script with a budget of 40 it collapsed 40 real
    // warnings into 1, printed "below budget — lower this script's entry", and invited somebody to
    // ratchet the whole mechanism down to nothing. A broken checker fails, and says it is broken.
    contractIssues.push({ severity: 'broken', message: `contract checker itself failed: ${e.message}` })
  }
}

/** Assert helper that records rather than throws, so one run reports everything. */
export function checker() {
  const results = []
  const check = (label, ok, detail = '') => {
    results.push({ label, ok, detail })
    console.log(`  ${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`)
    return ok
  }
  check.summary = () => {
    // Contract findings are folded in here, so every script reports them without knowing they
    // exist. Deduplicated: the same endpoint is often stubbed many times in one run, and thirty
    // copies of one message is a wall people learn to scroll past.
    const seen = new Set()
    const unique = contractIssues.filter((i) => !seen.has(i.message) && seen.add(i.message))
    const broken = unique.filter((i) => i.severity === 'broken')
    const fails = unique.filter((i) => i.severity === 'fail')
    // `broken` is excluded from the count on purpose: a crashed checker produces no warnings, and
    // letting that read as "warnings went down" is how the baseline would get ratcheted to zero.
    const warns = unique.filter((i) => i.severity === 'warn')

    for (const b of broken) {
      results.push({ label: `contract: ${b.message}`, ok: false, detail: 'the check did not run — this is not a fixture problem' })
      console.log(`  ❌ contract: ${b.message}`)
    }
    for (const f of fails) {
      results.push({ label: `contract: ${f.message}`, ok: false, detail: '' })
      console.log(`  ❌ contract: ${f.message}`)
    }
    if (warns.length) {
      console.log(`\n  ⚠️  ${warns.length} contract warning(s) — absent fields (normal for a partial stub) or fields core does not send:`)
      for (const w of warns.slice(0, 15)) console.log(`     ${w.message}`)
      if (warns.length > 15) console.log(`     … and ${warns.length - 15} more`)
    }

    // Ratchet: the count may fall, never rise. Without this the warnings are a list nobody reads
    // and new fixture drift joins it silently. With it, paying debt down is rewarded (the baseline
    // must be lowered in the same commit) and adding debt is a build failure.
    // Skipped when the checker itself broke: the count is meaningless then, and "below budget" would
    // be an invitation to lower it.
    const budget = broken.length ? null : contractBudget()
    if (budget !== null) {
      check(
        `contract warnings within budget (${warns.length} ≤ ${budget})`,
        warns.length <= budget,
        warns.length > budget
          ? `new fixture drift. Fix it, or if it is deliberate raise the entry for this script in ` +
            `dev-harness/contract-baseline.json and say why in the commit`
          : '',
      )
      if (warns.length < budget) {
        console.log(
          `  ↓ contract warnings are below budget (${warns.length} < ${budget}) — lower this script's ` +
          `entry in contract-baseline.json to ${warns.length} so the ground gained is kept`,
        )
      }
    }

    const failed = results.filter((r) => !r.ok)
    console.log(
      `\n${results.length - failed.length}/${results.length} checks passed`,
    )
    if (failed.length) {
      console.log('FAILED:')
      for (const f of failed) console.log(`  - ${f.label} ${f.detail}`)
    }
    return failed.length === 0
  }
  return check
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
