/**
 * J1, follow-up 2: turn two impressions from the screenshots into numbers, and run the a11y
 * detector on this surface — the plan requires every unit to report its a11y results, and the
 * student exercise page is one of the twenty routes the two a11y-wired specs never visit.
 */
import { withBrowser, fakeApi, a11y, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  submission,
  baseHandlers,
} from './fixtures.mjs'

for (const [name, vp] of [
  ['laptop', VIEWPORTS.laptop],
  ['monitor', VIEWPORTS.monitor],
]) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({ role: 'student', language: 'et', viewport: vp })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
        [
          new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`),
          () => ({ exercises: [studentExercise(), studentExercise({ id: '4148', effective_title: 'Kolmnurga pindala' })] }),
        ],
        [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions: [submission()] })],
        [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
      ],
      { log: false },
    )

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(2000)

    const m = await page.evaluate(() => {
      const ed = document.querySelector('.cm-editor')
      const main = document.querySelector('main')
      const lastEl = [...(main?.querySelectorAll('*') ?? [])]
        .map((e) => e.getBoundingClientRect())
        .filter((r) => r.height > 0)
        .reduce((acc, r) => Math.max(acc, r.bottom), 0)
      return {
        viewport: { w: innerWidth, h: innerHeight },
        editorHeight: ed ? Math.round(ed.getBoundingClientRect().height) : null,
        editorWidth: ed ? Math.round(ed.getBoundingClientRect().width) : null,
        mainWidth: main ? Math.round(main.getBoundingClientRect().width) : null,
        contentBottom: Math.round(lastEl),
        docHeight: document.documentElement.scrollHeight,
      }
    })
    const usedPct = Math.round((m.contentBottom / m.viewport.h) * 100)
    console.log(
      `[${name} ${m.viewport.w}x${m.viewport.h}] editor ${m.editorWidth}x${m.editorHeight}px · ` +
        `main width ${m.mainWidth}px · content reaches ${m.contentBottom}px = ${usedPct}% of viewport height`,
    )

    // scan() returns { gate, contrast } — NOT { found, contrastFindings }. The first version of this
    // driver read the latter, got undefined, printed 0, and would have recorded "this page is
    // accessible" as a fact. Verification item 3 of the plan exists for exactly this.
    const scan = await a11y.scan(page)
    console.log(`[${name}] axe+own gated findings: ${scan.gate.length}`)
    for (const f of scan.gate) console.log(`   - ${f.rule} | ${f.selector} | ${f.summary}`)
    console.log(`[${name}] contrast (run, never gated): ${scan.contrast.length}`)
    for (const f of scan.contrast) console.log(`   - ${f.rule} | ${f.selector} | ${f.summary}`)

    // Positive control: break something on purpose and confirm the detector says so. A clean result
    // from a detector that has never been made to fire is not evidence of anything.
    await page.evaluate(() => {
      const b = document.createElement('button')
      b.id = 'audit-canary' // no accessible name at all
      document.querySelector('main')?.appendChild(b)
      const img = document.createElement('img')
      img.src =
        'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
      document.querySelector('main')?.appendChild(img) // no alt
    })
    const canary = await a11y.scan(page)
    const caught = canary.gate.filter(
      (f) => f.rule === 'button-name' || f.rule === 'image-alt',
    )
    console.log(
      `[${name}] CANARY: detector ${caught.length ? 'FIRES' : 'IS INERT'} ` +
        `(${caught.map((f) => f.rule).join(', ') || 'nothing caught'})`,
    )

    await page.close()
  })
}
