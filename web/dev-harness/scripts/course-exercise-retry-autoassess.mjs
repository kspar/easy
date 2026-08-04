/**
 * The teacher's "re-run auto-assessment" button (EZ-1215).
 *
 * Core has had this endpoint for years and no frontend ever called it. What is worth testing is not
 * that a button renders, but the three things that make it useful or useless:
 *
 *  - it POSTs to the right URL,
 *  - it refetches afterwards, because core returns no body and the new assessment only shows up in
 *    the reloaded submission,
 *  - it does not appear on a teacher-graded exercise, where core would reject it.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const COURSE = '1'
const CE = '10'
const STUDENT = 'student1'
const SUB = '500'

// Flipped by the retry handler, so the refetch returns something visibly different from the first
// response. Asserting on a value that changes is the only way to tell a real refetch from a button
// that did nothing.
let retried = false
const retryCalls = []

// Field names copied from course-exercise-embed.mjs rather than guessed — an invented `title` is
// how the first run of this script rendered an empty page.
const exercise = (graderType) => ({
  exercise_id: '77',
  title: 'Sum of two numbers',
  title_alias: null,
  text_html: '<p>Read two integers and print their sum.</p>',
  instructions_html: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: graderType,
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
  student_visible: true,
  student_visible_from: null,
  has_lib_access: false,
  exception_students: null,
  exception_groups: null,
})

const submissionDetail = () => ({
  id: SUB,
  solution: 'print(a + b)',
  created_at: '2026-08-01T10:00:00.000Z',
  grade: retried ? { grade: 100, is_autograde: true, is_graded_directly: true } : null,
  seen: true,
  auto_assessment: {
    grade: retried ? 100 : 0,
    feedback: retried ? 'All tests passed on the retry' : 'Executor unavailable',
  },
})

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'ce-retry-' })
const check = checker()

let graderType = 'AUTO'

// Ordered most-specific-first, and that ordering is load-bearing rather than tidy: these match by
// substring, so `/teacher/courses` sits at the very bottom — placed near the top it swallows every
// URL beneath it and the page renders from `{}` while looking like the stubs simply did not work.
await fakeApi(page, [
  ['/account/checkin', () => ({})],

  // Ahead of the submission-detail handler, which its URL also matches. Shadowed the other way
  // round, this whole script would pass against a page that never called the endpoint at all.
  ['retry-autoassess', ({ url, method }) => {
    if (method === 'POST') {
      retried = true
      retryCalls.push(url)
    }
    return {}
  }],

  [`/teacher/courses/${COURSE}/exercises/${CE}/submissions/latest/students`, () => ({
    latest_submissions: [
      {
        student_id: STUDENT,
        given_name: 'Mari',
        family_name: 'Maasikas',
        submission_id: SUB,
        created_at: '2026-08-01T10:00:00.000Z',
        grade: retried ? 100 : 0,
        seen: true,
      },
    ],
  })],

  [`/exercises/${CE}/submissions/all/students/${STUDENT}`, () => ({
    submissions: [{ id: SUB, created_at: '2026-08-01T10:00:00.000Z', grade: retried ? 100 : 0, seen: true }],
  })],

  [`/students/${STUDENT}/activities`, () => ({ teacher_activities: [] })],
  [`/students/${STUDENT}/inline-comments`, () => ({ inline_comments: [] })],
  [`/submissions/${SUB}`, () => submissionDetail()],

  // After everything under it, before the bare `/teacher/courses` at the bottom.
  [`/teacher/courses/${COURSE}/exercises/${CE}`, () => exercise(graderType)],
  [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
  ['/groups', () => ({ groups: [] })],
  ['/participants', () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
  ['/teacher/courses', () => ({ courses: [] })],
], { log: false })

const open = async () => {
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}?student=${STUDENT}`)
  await page.getByText('Sum of two numbers').first().waitFor({ timeout: 15000 })
}

// AutoTestResults renders collapsed, so the feedback is in the DOM but not visible. That is fine
// here: what is being tested is whether the data changed, not whether an accordion is open.
const feedbackCount = (text) => page.getByText(text).count()

await open()

const retryButton = page.getByRole('button', { name: /Re-run auto-assessment/i })
// Polled, not sampled: the title renders from one query and the button from another, so a one-shot
// isVisible() here fails on timing rather than on behaviour. It did, on the first run of this script —
// and the click below still passed, because Playwright's click auto-waits. A check that fails while
// the thing it checks demonstrably works is worse than no check.
check(
  'the button is offered on an auto-graded exercise',
  await waitUntil(() => retryButton.isVisible()),
)
await shot('01-before')

await retryButton.click()

check(
  'clicking it POSTs to the retry endpoint',
  await waitUntil(() => retryCalls.length === 1),
)
check(
  'and to the right URL, with the course, exercise and submission in it',
  retryCalls[0]?.includes(`/teacher/courses/${COURSE}/exercises/${CE}/submissions/${SUB}/retry-autoassess`),
)

// The endpoint returns no body, so the only way the new result reaches the screen is a refetch.
check(
  'the new assessment replaces the old one without a reload',
  await waitUntil(async () => (await feedbackCount('All tests passed on the retry')) > 0),
)
check(
  'and the stale feedback is gone',
  await waitUntil(async () => (await feedbackCount('Executor unavailable')) === 0),
)
await shot('02-after')

// --- a teacher-graded exercise has nothing to re-run ------------------------------------------------
graderType = 'TEACHER'
retried = false
retryCalls.length = 0
await open().catch(() => {})
check(
  'the button is not offered when the exercise is teacher-graded',
  await waitUntil(async () => (await page.getByRole('button', { name: /Re-run auto-assessment/i }).count()) === 0),
)

await browser.close()
process.exit(check.summary() ? 0 : 1)
