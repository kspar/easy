/**
 * Unit J1, the states the first pass did not reach.
 *
 *  1. `solution_file_type: 'TEXT_UPLOAD'` — a different submission mechanism entirely. No browser spec
 *     covers the student exercise page at all, so this is very likely the least-exercised path a real
 *     student can be put on.
 *  2. `is_open: false` — the exercise is closed. Can they still submit, and does the page say why not?
 *  3. a deadline in the past with `is_open: true` — late submission. Is the state legible?
 *  4. `grader_type: 'TEACHER'` — submitting where **nothing will answer**. There is no autograde
 *     animation and no result panel, so the question is whether anything at all confirms the work
 *     landed. That is C4's question on the app's most-used action.
 *
 *   HARNESS_PORT=5299 node tests/audit/j1b-student-edge-states.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, studentExercise, exerciseDetails, submission, baseHandlers } from './fixtures.mjs'

const PAST = '2026-08-01T23:59:00.000Z'

const CASES = [
  {
    name: 'TEXT_UPLOAD submission type',
    details: exerciseDetails({ solution_file_type: 'TEXT_UPLOAD' }),
    listed: studentExercise(),
    submissions: [],
  },
  {
    name: 'closed exercise (is_open false)',
    details: exerciseDetails({ is_open: false }),
    listed: studentExercise({ is_open: false }),
    submissions: [],
  },
  {
    name: 'deadline in the past, still open',
    details: exerciseDetails({ deadline: PAST }),
    listed: studentExercise({ deadline: PAST }),
    submissions: [],
  },
  {
    name: 'teacher-graded — nothing will answer',
    details: exerciseDetails({ grader_type: 'TEACHER' }),
    listed: studentExercise({ grader_type: 'TEACHER' }),
    submissions: [],
    submitAndWatch: true,
  },
]

const report = []

for (const c of CASES) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
    let submissions = c.submissions
    let posted = false
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
        [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [c.listed] })],
        [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions })],
        [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
        [new RegExp(`/exercises/${CE_ID}/inline-comments`), () => ({ inline_comments: [] })],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => c.details],
      ],
      { log: false, contract: false },
    )
    await page.route('**/submissions', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      posted = true
      submissions = [
        submission({
          autograde_status: 'NONE',
          grade: null,
          submission_status: 'UNGRADED',
          auto_assessment: null,
        }),
      ]
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('main').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(1800)

    const before = await page.evaluate(() => {
      const main = document.querySelector('main')
      return {
        hasCodeMirror: !!main?.querySelector('.cm-content'),
        hasFileInput: !!main?.querySelector('input[type=file]'),
        buttons: [...document.querySelectorAll('button')]
          .filter((b) => b.offsetParent !== null)
          .map((b) => ({ label: (b.innerText || b.getAttribute('aria-label') || '').trim().slice(0, 40), disabled: b.disabled }))
          .filter((b) => b.label),
        alerts: [...document.querySelectorAll('.MuiAlert-root')].map((a) => a.textContent?.trim().slice(0, 120)),
        text: (main?.innerText ?? '').replace(/\s+/g, ' ').slice(0, 400),
      }
    })

    let after = null
    if (c.submitAndWatch) {
      // Type something first. The first run clicked Submit on an empty editor, no POST fired, and the
      // result was a meaningless "nothing confirmed the submission" — because there was no submission.
      await page.locator('.cm-content').first().click()
      await page.keyboard.type('a = int(input())\nb = int(input())\nprint(a + b)')
      await page.waitForTimeout(600)
      const submit = page.getByRole('button', { name: /Esita/i }).first()
      if (await submit.count()) {
        await submit.click()
        await page.waitForTimeout(3000)
        after = await page.evaluate(() => {
          const main = document.querySelector('main')
          return {
            snackbars: [...document.querySelectorAll('.MuiSnackbar-root')].map((s) => s.textContent?.trim().slice(0, 120)),
            alerts: [...document.querySelectorAll('.MuiAlert-root')].map((a) => a.textContent?.trim().slice(0, 120)),
            text: (main?.innerText ?? '').replace(/\s+/g, ' ').slice(0, 400),
          }
        })
      }
    }

    report.push({ case: c.name, before, after, posted })
    console.log(`\n[${c.name}]`)
    console.log(`   CodeMirror: ${before.hasCodeMirror} · file input: ${before.hasFileInput}`)
    console.log(`   buttons: ${JSON.stringify(before.buttons)}`)
    console.log(`   alerts: ${JSON.stringify(before.alerts)}`)
    console.log(`   text: ${JSON.stringify(before.text.slice(0, 260))}`)
    if (after) {
      console.log(`   -- after submitting (POST fired: ${posted}) --`)
      console.log(`   snackbars: ${JSON.stringify(after.snackbars)}`)
      console.log(`   alerts: ${JSON.stringify(after.alerts)}`)
      console.log(`   text: ${JSON.stringify(after.text.slice(0, 260))}`)
    }
    await shoot(page, `j1b-${c.name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`)
    await page.close()
  })
}

const path = join(REPORTS, 'j1b-student-edge-states.json')
writeFileSync(path, JSON.stringify(report, null, 2))
console.log(`\nreport written to ${path}`)
