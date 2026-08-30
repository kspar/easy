/**
 * Verification driver for the X-006 / X-007 fix.
 *
 * X-007: the grade used to arrive ~1535 ms after the grader had already answered, and the full test
 * list ~4291 ms after, because GradeBanner read a frozen snapshot until the typewriter finished.
 * The banner now reads live data, so the grade lands with the result and the reveal animates the
 * detail underneath it.
 *
 * X-006: `prefers-reduced-motion` had no effect at all — 1535 vs 1531 ms and 4291 vs 4328 ms, the
 * near-identical pair being the control that made it a measurement. A reduced-motion viewer now
 * skips the typewriter entirely and gets the finished list.
 *
 * The measurement is `j1-reveal-timing.mjs`'s, with assertions bolted on, plus two checks the
 * timing pass cannot make:
 *
 *  - the animation still exists on the default path. A fix that simply deleted the reveal would
 *    pass every timing assertion here, so the default run must still show the typewriter taking
 *    visibly longer than the grade.
 *  - the reduced-motion SVGs render their FINISHED frame. Every one of them animates from an
 *    invisible first frame — opacity 0, or a stroke dashed out of view — so the lazy fix (drop the
 *    animation, keep the markup) leaves a blank panel that no timing check would catch.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x006-x007-reveal-verify.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  submission,
  okV3,
  baseHandlers,
} from './fixtures.mjs'

const LAST_TITLE = 'Programm töötab ka negatiivsete arvudega'
// The original measurement used 1200 ms. This one needs to outlast a phase boundary — the panel
// swaps scene at 3000 ms — so the reduced-motion run can be checked for holding still across one.
const GRADING_MS = 4200

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const results = {}

for (const reduced of [false, true]) {
  const label = reduced ? 'reduced' : 'default'
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({
      role: 'student',
      language: 'et',
      viewport: VIEWPORTS.laptop,
      ...(reduced ? { reducedMotion: 'reduce' } : {}),
    })

    let submissions = []
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
        [
          new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`),
          () => ({ exercises: [studentExercise()] }),
        ],
        [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions })],
        [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
        [new RegExp(`/exercises/${CE_ID}/inline-comments`), () => ({ comments: [] })],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
      ],
      { log: false },
    )

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.locator('.cm-content').click()
    await page.keyboard.type('a = int(input())\nb = int(input())\nprint(a + b)')

    let t0 = 0
    await page.route('**/submissions/latest/await', async (route) => {
      await new Promise((r) => setTimeout(r, GRADING_MS))
      submissions = [submission({ auto_assessment: { grade: 100, feedback: okV3(true) } })]
      t0 = Date.now()
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })
    await page.route('**/submissions', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.getByRole('button', { name: /Esita ja kontrolli/i }).first().click()

    // Mid-grading, sampled either side of the panel's 3000 ms scene boundary. Two questions:
    // are the shapes actually painted (an element left on its invisible first frame is the trap
    // the JS branch exists to avoid), and does the scene swap. Opacity is read computed, not from
    // the attribute, so it is caught however it got there.
    const readPanel = () =>
      page.evaluate(() => {
        const svg = [...document.querySelectorAll('svg')]
          .find((s) => s.getAttribute('viewBox') === '0 0 200 100')
        if (!svg) return { shapes: 0, painted: 0, sig: 'none' }
        const shapes = [...svg.querySelectorAll('rect, polygon, circle, path, text')]
        const painted = shapes.filter((el) => parseFloat(getComputedStyle(el).opacity || '0') > 0.1)
        const count = (sel) => svg.querySelectorAll(sel).length
        return {
          shapes: shapes.length,
          painted: painted.length,
          sig: `r${count('rect')}p${count('polygon')}c${count('circle')}t${count('text')}`,
        }
      })

    await page.waitForTimeout(1500) // inside the first phase
    const early = await readPanel()
    await page.waitForTimeout(2100) // past the 3000 ms boundary
    const late = await readPanel()
    results[`${label}Panel`] = { early, late }

    await waitUntil(() => t0 > 0, { timeout: 20000 })
    const gradeVisible = await waitUntil(
      async () => (await page.getByText(/\b100\b/).count()) > 0,
      { timeout: 40000, interval: 25 },
    )
    const t1 = Date.now()

    const lastTitleVisible = await waitUntil(
      async () => (await page.getByText(LAST_TITLE, { exact: false }).count()) > 0,
      { timeout: 40000, interval: 25 },
    )
    const t2 = Date.now()

    results[label] = {
      grade: gradeVisible ? t1 - t0 : null,
      lastTitle: lastTitleVisible ? t2 - t0 : null,
    }
    console.log(
      `[${label}] grade ${results[label].grade ?? 'NEVER'} ms; last test title ${
        results[label].lastTitle ?? 'NEVER'
      } ms after the grader answered`,
    )

    await page.waitForTimeout(400)
    await shoot(page, `x007-reveal-${label}-settled`)
    await page.close()
  })
}

console.log('\nX-007 — the grade stops waiting for the animation')
// Was 1535 ms (default) / 1531 ms (reduced). The remaining delay is the refetch, not a hold.
check(results.default.grade !== null && results.default.grade < 900,
  `default: grade within 900 ms of the grader answering (was 1535) — got ${results.default.grade}`)
check(results.reduced.grade !== null && results.reduced.grade < 900,
  `reduced: grade within 900 ms (was 1531) — got ${results.reduced.grade}`)

console.log('\nX-006 — reduced motion is finally a different experience')
// Was 4328 ms, and 4291 ms on the default path: the preference changed nothing.
check(results.reduced.lastTitle !== null && results.reduced.lastTitle < 1500,
  `reduced: whole test list readable within 1500 ms (was 4328) — got ${results.reduced.lastTitle}`)
check(results.default.lastTitle !== null && results.reduced.lastTitle !== null &&
  results.default.lastTitle > results.reduced.lastTitle + 800,
  `the preference now makes a difference: default ${results.default.lastTitle} ms vs reduced ` +
  `${results.reduced.lastTitle} ms (was 4291 vs 4328, i.e. none)`)

console.log('\nThe reveal was kept, not deleted')
check(results.default.lastTitle !== null && results.default.lastTitle > 1500,
  `default: the typewriter still runs — ${results.default.lastTitle} ms to the last title`)

console.log('\nThe reduced-motion panel renders its finished frame, not its blank first one')
const d = results.defaultPanel
const r = results.reducedPanel
check(d.early.shapes > 0, `the grading panel's SVG is on screen (${d.early.sig})`)
check(r.early.shapes > 0, `same panel under reduced motion (${r.early.sig})`)
check(r.early.painted === r.early.shapes,
  `reduced: every shape is painted, none stuck at opacity 0 — ${r.early.painted}/${r.early.shapes}`)

console.log('\nThe scene stops taking turns when motion is reduced')
check(d.early.sig !== d.late.sig,
  `default: the scene still swaps across the 3s boundary (${d.early.sig} → ${d.late.sig})`)
check(r.early.sig === r.late.sig,
  `reduced: the scene holds across the same boundary (${r.early.sig} → ${r.late.sig})`)

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}`)
process.exit(failures === 0 ? 0 : 1)
