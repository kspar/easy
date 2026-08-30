/**
 * Verification driver for EZ-1800 batches B and F.
 *
 * X-035 — core answers every failure with a typed code, `client.ts` has always parsed it, and two
 *   call sites in the whole app read it. Everything else said "Midagi läks valesti", whether the
 *   name was taken, the group still had students in it, or the server was down.
 * X-033 — a student opening a teacher's link watched a spinner and then silently arrived somewhere
 *   else. The redirect is right; the silence made the link look broken.
 * X-032 — the removal confirm named the student but never said what happens to their work, which
 *   is the only fact the teacher is actually deciding on.
 * X-038 — the theme toggle destroyed "follow the OS" the first time it was touched, and never
 *   subscribed to the OS at all.
 *
 * The load-bearing assertions are the ones that check the generic sentence is *gone*. A mapped code
 * that silently falls through to "something went wrong" is exactly the state before this fix, and
 * a check that only looks for the specific sentence would not notice the fallback beside it.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x032-x033-x035-x038-verify.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, baseHandlers } from './fixtures.mjs'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const GENERIC_ET = /Midagi läks valesti/
const bodyText = (page) => page.evaluate(() => document.body.innerText)

/**
 * An error envelope shaped exactly as core sends one, fulfilled directly rather than returned:
 * a handler that *returns* a body is always answered 200 by the harness, and the whole point here
 * is the failure path.
 */
const fails = (code, status = 400, attrs = {}) => async ({ route }) => {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify({
      id: 'err-1',
      code,
      attrs,
      log_msg: `internal: ${code} at SomeService.kt:42`,
    }),
  })
}

await withBrowser(async ({ launch }) => {
  // ─── X-035: a typed code becomes a sentence, and the generic one is not also on screen ──────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, fails('NO_COURSE_ACCESS', 403)],
        [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
      ],
      { log: false, contract: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => /pole|access|viga|valesti/i.test(await bodyText(page)), { timeout: 15000 })
    await page.waitForTimeout(400)

    const text = await bodyText(page)
    check(/Sul pole sellele kursusele ligipääsu/.test(text), 'a mapped code produces its own sentence')
    check(!GENERIC_ET.test(text), 'and the generic sentence is not shown instead')
    check(
      !/SomeService\.kt/.test(text),
      'log_msg stays in the server log, where it was written for',
    )
    check(
      (await page.getByRole('button', { name: /Teata|Report/i }).count()) > 0,
      'the reporter is still offered — a named cause is still worth reporting',
    )
    await shoot(page, 'x035-mapped-error')
    await page.close()
  }

  // ─── X-035: an unmapped code still falls back rather than showing anything raw ──────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, fails('SOME_FUTURE_CODE', 400)],
        [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
      ],
      { log: false, contract: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => GENERIC_ET.test(await bodyText(page)), { timeout: 15000 })
    const text = await bodyText(page)
    check(GENERIC_ET.test(text), 'an unknown code falls back to the generic sentence')
    check(!/SomeService\.kt|SOME_FUTURE_CODE/.test(text), 'and never leaks the code or the log line')
    await page.close()
  }

  // ─── X-033: the redirect explains itself ────────────────────────────────────────────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, () => ({ courses: [] })],
        [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
      ],
      { log: false, contract: false },
    )
    // A teacher-only URL, opened as a student — the exact shape of a shared link.
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/grades`)
    await waitUntil(async () => new URL(page.url()).pathname === '/courses', { timeout: 20000 })
    await page.waitForTimeout(600)

    const text = await bodyText(page)
    check(new URL(page.url()).pathname === '/courses', 'the student still lands on their course list')
    check(/pole sinu praeguses rollis saadaval/.test(text), 'and is told why, rather than nothing')
    await shoot(page, 'x033-role-redirect-explained')

    // Said once. A refresh must not re-announce a redirect that already happened.
    await page.reload()
    await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 15000 })
    await page.waitForTimeout(600)
    check(
      !/pole sinu praeguses rollis saadaval/.test(await bodyText(page)),
      'and only once — the flag is cleared from history',
    )
    await page.close()
  }

  // ─── X-032: the removal confirm says what happens to the work ───────────────────────────────────
  {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/participants/, () => ({
          students: [{
            id: 'mari', given_name: 'Mari', family_name: 'Maasikas',
            email: 'mari@example.org', groups: [], created_at: '2026-08-01T10:00:00.000Z',
          }],
          teachers: [], students_pending: [], students_moodle_pending: [],
        })],
        [/\/groups/, () => ({ groups: [] })],
        // `/courses/{id}/invite` returns the invite or null. Without this the catch-all answers
        // with a plain object, which is truthy, so the page formats an absent `expires_at` and
        // throws — the whole tab replaced by the crash screen.
        [/\/invite(\?|$)/, () => null],
        [new RegExp(`/courses/${COURSE_ID}(\\?|$)`), () => ({
          title: 'Programmeerimise alused', alias: null, archived: false, color: '#16a34a', course_code: null,
        })],
        [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
      ],
      { log: false, contract: false },
    )
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/participants`)
    await waitUntil(async () => (await page.getByText('Mari').count()) > 0, { timeout: 20000 })
    await page.waitForTimeout(400)

    // The kebab on Mari's own row, by its accessible name. An earlier version of this clicked
    // every icon button in `main` until a menu appeared, which eventually hit a navigation control
    // and left the page entirely — the failure then read as "the remove action is missing".
    const row = page.locator('tr', { hasText: 'Maasikas' }).first()
    await row.getByRole('button', { name: /Rohkem valikuid/i }).click()
    await page.waitForTimeout(300)
    const item = page.getByRole('menuitem', { name: /Eemalda kursuselt/i }).first()
    const opened = (await item.count()) > 0
    if (opened) await item.click()

    if (!opened) {
      await shoot(page, 'x032-could-not-reach-remove')
      const menuItems = await page.evaluate(() =>
        [...document.querySelectorAll('[role=menuitem], button')]
          .map((b) => (b.innerText || b.getAttribute('aria-label') || '').trim())
          .filter(Boolean).slice(0, 20))
      check(false, `could not reach the remove action — saw: ${JSON.stringify(menuItems)}`)
    } else {
      await page.waitForTimeout(400)
      const text = await bodyText(page)
      check(/Mari/.test(text), 'the confirm still names the student')
      check(
        /Esitused ja hinded jäävad alles/.test(text),
        'and now says what happens to their work, which is the actual decision',
      )
      await shoot(page, 'x032-removal-confirm')
    }
    await page.close()
  }

  // ─── X-038: the theme can follow the OS again, and keeps following it ───────────────────────────
  {
    // colorScheme: 'dark' is the OS preference; no stored themeMode means "system".
    const { page } = await launch({
      role: 'student', language: 'et', viewport: VIEWPORTS.laptop, colorScheme: 'dark',
    })
    // `makeLaunch` always seeds `themeMode`, so a genuinely fresh visitor has to be arranged here.
    // The second init script runs after the harness's and removes what it wrote.
    await page.addInitScript(() => localStorage.removeItem('themeMode'))
    await fakeApi(page, [...baseHandlers(), [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })]], { log: false, contract: false })
    await page.goto(`${BASE_URL}/account`)
    await waitUntil(async () => (await page.getByRole('button', { name: /Vaikimisi/i }).count()) > 0, { timeout: 20000 })

    const stored = () => page.evaluate(() => localStorage.getItem('themeMode'))
    check((await stored()) === null, 'a fresh visitor has no stored override')
    check(
      (await page.getByRole('button', { name: /Vaikimisi/i }).getAttribute('aria-pressed')) === 'true',
      'and the picker shows the app is following the OS',
    )

    // Choose light explicitly, then choose "follow the OS" again — the path that did not exist.
    await page.getByRole('button', { name: /^Hele$/i }).click()
    await page.waitForTimeout(300)
    check((await stored()) === 'light', 'an explicit choice is stored')

    await page.getByRole('button', { name: /Vaikimisi/i }).click()
    await page.waitForTimeout(300)
    check((await stored()) === null, 'choosing to follow the OS clears the override rather than storing a third value')

    // And it is actually following: the OS says dark, so the app is dark.
    const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
    const isDark = (() => {
      const m = bg.match(/\d+/g)
      return m && Number(m[0]) + Number(m[1]) + Number(m[2]) < 250
    })()
    check(isDark, `and follows it — body background is ${bg} with the OS in dark`)
    await shoot(page, 'x038-theme-follows-os')
    await page.close()
  }
})

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}`)
process.exit(failures === 0 ? 0 : 1)
