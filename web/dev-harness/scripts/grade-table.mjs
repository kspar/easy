/**
 * The grade table's cells link to the submission behind them (EZ-1706), as the WUI's did.
 *
 * First browser coverage for this page, and it is the one `doc/testing.md` calls out as highest
 * consequence: grades are the output of the whole application, and until now nothing checked the
 * table that shows them.
 *
 * What is worth pinning is that each cell goes to *that* student and *that* exercise. A table of
 * links that all work but point one column over is the failure mode here, and it is invisible in a
 * screenshot — the numbers look right either way.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const COURSE = '1'
const CE1 = '10'
const CE2 = '20'

// Two students, two exercises, and deliberately mismatched grades so a cell that links to the wrong
// column or the wrong row is distinguishable rather than plausible.
const students = [
  { student_id: 's1', given_name: 'Mari', family_name: 'Maasikas', groups: [] },
  { student_id: 's2', given_name: 'Jaan', family_name: 'Tamm', groups: [] },
]

const sub = (n, grade, isAutograde) => ({
  id: `sub-${n}`,
  submission_number: n,
  time: '2026-08-01T10:00:00.000Z',
  grade: grade === null ? null : { grade, is_autograde: isAutograde, is_graded_directly: true },
  seen: true,
})

const exercise = (ceId, title, idx, rows) => ({
  course_exercise_id: ceId,
  exercise_id: `ex-${ceId}`,
  library_title: title,
  title_alias: null,
  effective_title: title,
  grade_threshold: 100,
  student_visible: true,
  student_visible_from: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'AUTO',
  ordering_idx: idx,
  unstarted_count: 0,
  ungraded_count: 0,
  started_count: 0,
  completed_count: 0,
  latest_submissions: rows,
})

const exercises = [
  exercise(CE1, 'Sum of two numbers', 0, [
    { ...students[0], status: 'COMPLETED', submission: sub(2, 100, true) },
    // Teacher-graded, so the cell shows the face icon.
    { ...students[1], status: 'COMPLETED', submission: sub(1, 60, false) },
  ]),
  exercise(CE2, 'Fibonacci', 1, [
    { ...students[0], status: 'UNGRADED', submission: sub(1, null, null) },
    // Never submitted — the cell shows "-" and, per the WUI, still links.
    { ...students[1], status: 'UNSTARTED', submission: null },
  ]),
]

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'grades-' })
const check = checker()

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
  [`/teacher/courses/${COURSE}/exercises`, () => ({ exercises })],
  [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
  ['/teacher/courses', () => ({ courses: [] })],
], { log: false })

await page.goto(`${BASE_URL}/courses/${COURSE}/grades`)
await page.getByText('Mari Maasikas').first().waitFor()

// The cells are named for who and what they belong to, which is what makes them findable here and
// usable by anyone reading the table through a screen reader.
const cell = (student, exerciseTitle, grade) =>
  page.getByRole('link', { name: `${student} — ${exerciseTitle} — ${grade}` })

check(
  'a graded cell links to that student and that exercise',
  await waitUntil(async () =>
    (await cell('Mari Maasikas', 'Sum of two numbers', '100').getAttribute('href')) ===
    `/courses/${COURSE}/exercises/${CE1}?student=s1`),
)
check(
  'a different student in the same column links to the other student',
  await waitUntil(async () =>
    (await cell('Jaan Tamm', 'Sum of two numbers', '60').getAttribute('href')) ===
    `/courses/${COURSE}/exercises/${CE1}?student=s2`),
)
check(
  'and the second column links to the second exercise',
  await waitUntil(async () =>
    (await cell('Mari Maasikas', 'Fibonacci', 'no grade').getAttribute('href')) ===
    `/courses/${COURSE}/exercises/${CE2}?student=s1`),
)
check(
  'an unstarted cell is a link too, rather than a dead end',
  await waitUntil(async () =>
    (await cell('Jaan Tamm', 'Fibonacci', 'no grade').getAttribute('href')) ===
    `/courses/${COURSE}/exercises/${CE2}?student=s2`),
)

// Cell contents, unchanged from what the page rendered before the links went in.
check('a teacher-graded cell is marked as such', (await page.locator('svg[data-testid="FaceOutlinedIcon"]').count()) === 1)
await shot('01-table')

// --- following one -----------------------------------------------------------------------------------
await cell('Jaan Tamm', 'Sum of two numbers', '60').click()
check(
  'clicking a cell opens that student in that exercise',
  await waitUntil(() =>
    page.url().endsWith(`/courses/${COURSE}/exercises/${CE1}?student=s2`)),
)

await browser.close()
process.exit(check.summary() ? 0 : 1)
