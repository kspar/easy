// Shared plumbing for driving web/ pages in a real Chrome with a faked IdP and
// a faked backend. See doc/web/browser-testing.md for the why and the gotchas.
import { chromium } from 'playwright'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { mkdirSync } from 'node:fs'

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

  const browser = await chromium.launch({ channel: 'chrome' })
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
export async function fakeApi(page, handlers, { log = true } = {}) {
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
      return json(route, result)
    }

    if (log) console.log(`  [unstubbed] ${method} ${url.replace(/^.*\/v2/, '')}`)
    return json(route, {})
  })

  return calls
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
