/**
 * J1, follow-up: how long after the grader has answered does the student wait before the result is
 * readable?
 *
 * The first J1 pass photographed the result panel mid-typewriter — the first test's title was still
 * being typed out character by character and no grade was on screen yet, 2.5s after the await
 * endpoint had already returned. That is a measurement, not a screenshot, so it gets measured.
 *
 * t0 = the moment /submissions/latest/await resolves, i.e. grading is finished server-side.
 * t1 = the moment the grade is on screen.
 * t2 = the moment the last test's title has finished typing.
 *
 * Also run with reducedMotion: 'reduce', because AutogradeAnimation is 493 lines of animation with
 * no prefers-reduced-motion guard, and a student who has asked the OS for less motion should not be
 * waiting longer for their grade than one who has not.
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

for (const reduced of [false, true]) {
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
      await new Promise((r) => setTimeout(r, 1200)) // a plausible real grading time
      submissions = [submission({ auto_assessment: { grade: 100, feedback: okV3(true) } })]
      t0 = Date.now()
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })
    await page.route('**/submissions', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.getByRole('button', { name: /Esita ja kontrolli/i }).first().click()

    // t1 — a grade is on screen. The autograde grade is 100; look for it as text.
    await waitUntil(() => t0 > 0, { timeout: 20000 })
    const gradeVisible = await waitUntil(
      async () => (await page.getByText(/\b100\b/).count()) > 0,
      { timeout: 40000, interval: 50 },
    )
    const t1 = Date.now()

    // t2 — the final test title has finished typing.
    const lastTitleVisible = await waitUntil(
      async () => (await page.getByText(LAST_TITLE, { exact: false }).count()) > 0,
      { timeout: 40000, interval: 50 },
    )
    const t2 = Date.now()

    const label = reduced ? 'reducedMotion=reduce' : 'default motion'
    console.log(
      `[${label}] grader answered → grade on screen: ${gradeVisible ? `${t1 - t0} ms` : 'NEVER (40s)'}`,
    )
    console.log(
      `[${label}] grader answered → last test title complete: ${
        lastTitleVisible ? `${t2 - t0} ms` : 'NEVER (40s)'
      }`,
    )
    await page.waitForTimeout(400)
    await shoot(page, `j1-reveal-${reduced ? 'reduced' : 'default'}-settled`)
    await page.close()
  })
}
