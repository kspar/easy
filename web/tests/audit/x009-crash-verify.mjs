/**
 * Verification driver for the X-009 fix: a route render error now shows the app's own translated
 * CrashScreen with its one-click bug report — inside the shell, so the nav survives — instead of
 * React Router's default "Unexpected Application Error!" page with a raw exception and no way out.
 *
 * The crash is induced the way the audit's C5 verification induced it (see R-005): a deliberately
 * wrong fixture shape that makes the page component throw during render. The fixture is the fault
 * injection, not the subject.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x009-crash-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, baseHandlers } from './fixtures.mjs'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

// The R-005 shape: plausible-looking but wrong for every endpoint, so page components throw.
const superset = () => ({
  courses: [], exercises: [], submissions: [], teacher_activities: [], comments: [],
  inline_comments: [], messages: [], articles: [], students: [], teachers: [], groups: [],
  invites: [], dirs: [], items: [], executors: [], images: [], versions: [], reports: [],
  count: 0, total: 0,
})

const readState = (page) =>
  page.evaluate(() => {
    const txt = document.body.innerText
    return {
      crashScreen: /See leht lõpetas töötamise|This page stopped working/i.test(txt),
      routerBoundary: /Unexpected Application Error/i.test(txt),
      hasSidebar: !!document.querySelector('nav, .MuiDrawer-root'),
    }
  })

await withBrowser(async ({ launch }) => {
  // ─── 1. a crashing route inside the shell: CrashScreen, with the shell alive ────────────────────
  {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(page, [...baseHandlers(), [/\/v2\//, () => superset()]], { log: false, contract: false })
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/participants`, { timeout: 20000 })
    await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 12000 })
    await page.waitForTimeout(1200)

    const s = await readState(page)
    check(s.crashScreen, 'the crash renders the app CrashScreen')
    check(!s.routerBoundary, "React Router's default boundary is gone")
    check(s.hasSidebar, 'the shell survives — nav and sidebar stay on screen')
    check(
      (await page.getByRole('button', { name: /Teata sellest|Report this/i }).count()) > 0,
      'the one-click bug report is offered',
    )
    await page.close()
  }

  // ─── 2. control: a route outside the shell that survives bad data shows no boundary ────────────
  // The embed page tolerates the wrong-shaped fixture (its own isError handling), so this arm
  // cannot claim to exercise the root errorElement — it verifies the wrapper does not swallow a
  // healthy render. The boundary rendering itself is proven by scenario 1; the root wrapper is
  // the same component reached by the same bubbling.
  {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(page, [...baseHandlers(), [/\/v2\//, () => superset()]], { log: false, contract: false })
    await page.goto(`${BASE_URL}/embed/exercises/4242/x`, { timeout: 20000 })
    await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 12000 })
    await page.waitForTimeout(1200)

    const s = await readState(page)
    check(!s.routerBoundary && !s.crashScreen, 'a surviving standalone route shows neither boundary')
    await page.close()
  }

  // ─── 3. control: a healthy route renders normally, no boundary of any kind ──────────────────────
  {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
        [/\/v2\//, () => ({ courses: [] })],
      ],
      { log: false, contract: false },
    )
    await page.goto(`${BASE_URL}/courses`, { timeout: 20000 })
    await waitUntil(async () => (await page.locator('main').count()) > 0, { timeout: 12000 })
    const s = await readState(page)
    check(!s.crashScreen && !s.routerBoundary, 'a healthy route shows neither boundary')
    check(s.hasSidebar, 'and its shell is intact')
    await page.close()
  }
})

console.log(failures === 0 ? '\nX-009 verification: all checks passed' : `\nX-009 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
