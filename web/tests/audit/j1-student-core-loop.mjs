/**
 * Unit J1 — the student core loop. EZ-1791, doc/web/ux-audit-plan.md.
 *
 * The most-travelled path in the application and the one with no browser spec at all: find the
 * course, open the exercise, read the statement, write a solution, submit, watch the grader, read
 * the result. Everything here is Stage 1 — render it, walk it, count the clicks, photograph it.
 *
 *   HARNESS_PORT=5299 node j1-student-core-loop.mjs
 */
import { withBrowser, fakeApi, shoot, collectProblems, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  submission,
  teacherActivity,
  okV3,
  baseHandlers,
} from './fixtures.mjs'

const shots = []
const notes = []
const note = (s) => {
  notes.push(s)
  console.log(`  · ${s}`)
}

/** Count every click the journey costs, so "too many steps" can be a number rather than a mood. */
function clickCounter(page) {
  const state = { n: 0 }
  const orig = page.click.bind(page)
  page.click = async (...a) => {
    state.n++
    return orig(...a)
  }
  return state
}

await withBrowser(async ({ launch }) => {
  // ─── A. arrival: /courses → exercise list → the exercise, as a student with nothing done ───────
  {
    const { page } = await launch({
      role: 'student',
      language: 'et', // the app's default language, and the one no spec runs in
      viewport: VIEWPORTS.laptop,
    })
    const problems = collectProblems(page)
    const clicks = clickCounter(page)

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
        [
          new RegExp(`/exercises/${CE_ID}/submissions/all`),
          () => ({ submissions }),
        ],
        [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
      ],
      { log: false },
    )

    await page.goto(`${BASE_URL}/courses`)
    await page.getByText('Programmeerimise alused').first().waitFor({ timeout: 15000 })
    shots.push(await shoot(page, 'j1-01-courses'))

    await page.click('text=Programmeerimise alused')
    await page.getByText('Kahe arvu summa').first().waitFor({ timeout: 15000 })
    shots.push(await shoot(page, 'j1-02-exercise-list'))

    await page.click('text=Kahe arvu summa')
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(500)
    shots.push(await shoot(page, 'j1-03-exercise-unstarted'))
    note(`clicks from /courses to a writable editor: ${clicks.n}`)

    // What is actually offered on a fresh exercise?
    const buttons = await page.locator('button:visible').allInnerTexts()
    note(`visible buttons on a fresh exercise: ${JSON.stringify(buttons.filter(Boolean))}`)

    // ─── B. write and submit; the await endpoint is held open so the grader animation is real ────
    await page.locator('.cm-content').click()
    await page.keyboard.type('a = int(input())\nb = int(input())\nprint(a + b)')
    shots.push(await shoot(page, 'j1-04-solution-typed'))

    let awaitResolved = false
    await page.route('**/submissions/latest/await', async (route) => {
      await new Promise((r) => setTimeout(r, 2500)) // grading takes time; so should the fixture
      submissions = [submission({ auto_assessment: { grade: 100, feedback: okV3(true) } })]
      awaitResolved = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })
    await page.route('**/submissions', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    const submitBtn = page.getByRole('button', { name: /Esita|Submit/i }).first()
    if (await submitBtn.count()) {
      await submitBtn.click()
      await page.waitForTimeout(900)
      shots.push(await shoot(page, 'j1-05-grading-in-progress'))
      await waitUntil(() => awaitResolved, { timeout: 20000 })
      await page.waitForTimeout(2500) // let the typewriter reveal run
      shots.push(await shoot(page, 'j1-06-graded-result'))
    } else {
      note('NO submit button found on a fresh open AUTO exercise — investigate')
    }

    if (problems.length) note(`console/page problems: ${problems.join(' | ')}`)
    await page.close()
  }

  // ─── C. the draft question: is typed work kept across an in-app navigation? ────────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    const draftCalls = []
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
        [
          new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`),
          () => ({ exercises: [studentExercise()] }),
        ],
        [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions: [] })],
        [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
        [
          new RegExp(`/exercises/${CE_ID}/draft`),
          ({ method }) => {
            draftCalls.push(method)
            return {}
          },
        ],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
      ],
      { log: false },
    )

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.locator('.cm-content').click()
    const TYPED = 'print("kolmveerand tundi tood")'
    await page.keyboard.type(TYPED)
    await page.waitForTimeout(1200) // longer than any plausible autosave debounce

    // Leave via the sidebar, the way a student checking another exercise would.
    await page.getByRole('link', { name: /Minu kursused|My courses/i }).first().click()
    await page.waitForTimeout(800)
    const warned = await page
      .getByText(/salvestamata|unsaved|Discard/i)
      .count()
    shots.push(await shoot(page, 'j1-07-left-the-page'))

    // Come back and see whether the work survived.
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(600)
    const after = (await page.locator('.cm-content').first().innerText()).trim()
    shots.push(await shoot(page, 'j1-08-returned'))

    note(`draft endpoint calls during the whole episode: ${JSON.stringify(draftCalls)}`)
    note(`warning shown when navigating away with typed work: ${warned > 0 ? 'yes' : 'NO'}`)
    note(`editor content after returning: ${JSON.stringify(after)} (typed: ${JSON.stringify(TYPED)})`)
    note(`typed work survived: ${after.includes('kolmveerand') ? 'yes' : 'NO'}`)
    await page.close()
  }

  // ─── D. a graded exercise with teacher feedback, and a failing autograde ───────────────────────
  {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
        [
          new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`),
          () => ({
            exercises: [
              studentExercise({
                status: 'COMPLETED',
                grade: { grade: 80, is_autograde: false, is_graded_directly: true },
              }),
            ],
          }),
        ],
        [
          new RegExp(`/exercises/${CE_ID}/submissions/all`),
          () => ({
            submissions: [
              submission({
                id: '9002',
                number: 2,
                auto_assessment: { grade: 50, feedback: okV3(false) },
                grade: { grade: 50, is_autograde: true, is_graded_directly: false },
                submission_status: 'UNGRADED',
              }),
              submission(),
            ],
          }),
        ],
        [
          new RegExp(`/exercises/${CE_ID}/activities`),
          () => ({ teacher_activities: [teacherActivity()] }),
        ],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
      ],
      { log: false },
    )

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await page.waitForTimeout(2500)
    shots.push(await shoot(page, 'j1-09-graded-with-feedback'))
    await page.close()
  }
})

console.log('\n--- J1 notes ---')
notes.forEach((n) => console.log(n))
console.log(`\n${shots.length} shots written`)
