/**
 * Unit S2 — the dark-mode visual pass over the surfaces the plan names as risks.
 *
 * C5 already collected dark-mode axe/contrast data across all surfaces; what is left is the class a
 * scanner cannot judge: hardcoded fills, CSS-filtered logos, bespoke SVG colours. Those are settled by
 * a human reading the PNG, so this driver only produces the PNGs — for the named risk surfaces, in
 * dark, with real-enough fixtures that the risky element actually renders.
 *
 *   HARNESS_PORT=5299 node tests/audit/s2-dark-shots.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, studentExercise, exerciseDetails, submission, okV3, baseHandlers } from './fixtures.mjs'

const CASES = [
  {
    // course-colors.ts: 12 mode-blind hex swatches; CoursesPage activity dots #43a047 etc.
    name: 'courses-teacher',
    path: '/courses',
    role: 'teacher',
    handlers: [[/\/teacher\/courses(\?|$)/, () => ({
      courses: [
        { ...studentCourse(), color: '#fbc02d', student_count: 12, last_submission_at: '2026-08-26T10:00:00.000Z', moodle_short_name: null },
        { ...studentCourse(), id: '120', title: 'Algoritmid', color: '#7b1fa2', student_count: 40, last_submission_at: null, moodle_short_name: 'ALG' },
      ],
    })]],
  },
  {
    // RobotFace has backgroundColor: 'white'; JoinCard is bespoke.
    name: 'join-by-link',
    path: '/link/ABC123',
    role: 'student',
    handlers: [[/\/link\/|\/invite/, () => ({ course_title: 'Programmeerimise alused' })]],
  },
  {
    // oneDark editor + GradeBanner + AutoTestResults in dark.
    name: 'exercise-student',
    path: `/courses/${COURSE_ID}/exercises/${CE_ID}`,
    role: 'student',
    handlers: [
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise({ status: 'COMPLETED', grade: { grade: 100, is_autograde: true, is_graded_directly: false } })] })],
      [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions: [submission({ auto_assessment: { grade: 100, feedback: okV3(true) } })] })],
      [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
      [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
    ],
  },
  {
    // The landing page's private palette — how does it look when the app is in dark mode?
    name: 'landing',
    path: '/landing',
    role: 'student',
    handlers: [],
  },
]

for (const c of CASES) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({ role: c.role, language: 'et', theme: 'dark', colorScheme: 'dark', viewport: VIEWPORTS.laptop })
    await fakeApi(page, [...baseHandlers(), ...c.handlers, [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })]], { log: false, contract: false })
    await page.goto(`${BASE_URL}${c.path}`)
    await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 12000 })
    await page.waitForTimeout(2500)
    await shoot(page, `s2-${c.name}-dark`)
    await page.close()
  })
}
console.log('done')
