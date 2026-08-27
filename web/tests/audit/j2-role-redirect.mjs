/**
 * Unit J2 — what happens when a student opens a teacher-only URL.
 *
 * The seeded lead: `RequireAuth` renders `<Navigate to="/courses" replace />` on a role mismatch, so a
 * student who clicks a teacher's shared link is silently relocated. Measured: land on
 * /courses/:c/grades as a student, record where we end up and whether anything on screen explains it.
 *
 *   HARNESS_PORT=5299 node tests/audit/j2-role-redirect.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, studentCourse, baseHandlers } from './fixtures.mjs'

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
  const calls = await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
    ],
    { log: false, contract: false },
  )
  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/grades`)
  await page.waitForTimeout(8000)
  const state = await page.evaluate(() => ({
    path: location.pathname,
    alerts: [...document.querySelectorAll('.MuiAlert-root, .MuiSnackbar-root')].map((a) => a.textContent?.trim().slice(0, 100)),
    text: (document.querySelector('main')?.innerText ?? '').replace(/\s+/g, ' ').slice(0, 200),
  }))
  console.log(`landed on: ${state.path}`)
  console.log(`v2 calls: ${JSON.stringify(calls.map((c)=>c.url))}`)
  console.log(`alerts/snackbars: ${JSON.stringify(state.alerts)}`)
  console.log(`main text: ${JSON.stringify(state.text)}`)
  await shoot(page, 'j2-role-redirect')
  await page.close()
})
