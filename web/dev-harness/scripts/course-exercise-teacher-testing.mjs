/**
 * The teacher's testing tab, and specifically that it opens with the last solution you tested.
 *
 * Every test run is stored server-side per teacher and always has been, and the tab already listed
 * the history behind a collapsible — but the editor was created with an empty document, so coming
 * back to it meant retyping the solution you had just run. wui opened with the latest attempt
 * loaded and showed when it was tested; this asserts that behaviour is back.
 *
 * Also covers the rebuild cases, which are the ones that quietly cost work: the CodeMirror view is
 * destroyed and recreated when the theme changes, when the language changes, and when the fetched
 * attempt arrives, and none of those may empty it.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const COURSE = '119'
const CE = '4147'
const EX = '9001'

const OLDER = 'print("older attempt")'
const LATEST = 'print("the last thing I tested")'

let submissions = [
  { id: '2', solution: LATEST, created_at: '2026-08-11T09:00:00.000Z' },
  { id: '1', solution: OLDER, created_at: '2026-08-02T09:00:00.000Z' },
]

const exercise = {
  exercise_id: EX,
  title: 'Sum of two numbers',
  title_alias: null,
  text_html: '<p>Read two integers and print their sum.</p>',
  text_md: 'Read two integers and print their sum.',
  instructions_html: null,
  instructions_md: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
  last_modified: '2026-07-30T12:00:00.000Z',
  student_visible: true,
  student_visible_from: null,
  assessments_student_visible: true,
  grading_script: 'python grade.py',
  container_image: 'pygrader',
  max_time_sec: 12,
  max_mem_mb: 44,
  assets: [],
  executors: [{ id: '1', name: 'mock-executor' }],
  has_lib_access: true,
  exception_students: null,
  exception_groups: null,
}

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'ce-testing-' })
const check = checker()

/** Every solution the autoassess endpoint was asked to grade. */
const graded = []

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  ['/participants', () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
  ['/groups', () => ({ groups: [] })],
  [`/teacher/courses/${COURSE}/exercises/${CE}/submissions/latest/students`, () => ({ latest_submissions: [] })],
  ['/submissions/latest', () => ({ latest_submissions: [] })],
  [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
  // Before the bare `/testing/autoassess`, which matches this as a substring.
  [`/exercises/${EX}/testing/autoassess/submissions`, () => ({ count: submissions.length, submissions })],
  [
    `/exercises/${EX}/testing/autoassess`,
    ({ body }) => {
      graded.push(body.solution)
      submissions = [
        { id: String(submissions.length + 1), solution: body.solution, created_at: '2026-08-12T18:00:00.000Z' },
        ...submissions,
      ]
      return { grade: 100, feedback: 'All tests passed' }
    },
  ],
  ['/submissions', () => ({ submissions: [], count: 0 })],
  [`/teacher/courses/${COURSE}/exercises/${CE}`, () => exercise],
  ['/teacher/courses', () => ({ courses: [] })],
], { log: false })

const editor = () => page.locator('.cm-content').first()

async function openTestingTab() {
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await page.getByText('Sum of two numbers').first().waitFor()
  await page.getByRole('tab', { name: 'Testing' }).click()
}

// --- opens with the last attempt --------------------------------------------------------------

await openTestingTab()

check(
  'the editor opens with the solution last tested',
  await waitUntil(async () => (await editor().innerText()).includes('the last thing I tested')),
)
check(
  'and says when that was, so it does not look like stale typing',
  await waitUntil(() => page.getByText(/Last tested/).isVisible()),
)
await shot('01-restored')

// --- a rebuild must not empty it ---------------------------------------------------------------

// Typed, not restored, so this tests the content and not the refetch.
await editor().click()
await page.keyboard.press('ControlOrMeta+a')
await page.keyboard.type('print("halfway through writing this")')

await page.getByRole('button', { name: 'Account menu' }).click()
await page.getByRole('menuitem', { name: /Dark mode|Light mode/ }).click()
await page.keyboard.press('Escape')

check(
  'toggling the theme keeps what was being typed',
  await waitUntil(async () => (await editor().innerText()).includes('halfway through writing this')),
)

// --- picking an older attempt ------------------------------------------------------------------

await page.getByText('Previous tests').click()
// By position, not by text: the labels are relative times ("yesterday", "3 days ago") that depend
// on when the suite runs. Newest first, so the last row is the older attempt.
// Scoped to the collapse: the left nav is built from ListItemButtons too.
const previousRows = page.locator('.MuiCollapse-root .MuiListItemButton-root')
check('both previous attempts are listed', (await previousRows.count()) === 2)
await previousRows.last().click()
check(
  'clicking a previous test loads that solution',
  await waitUntil(async () => (await editor().innerText()).includes('older attempt')),
)

// --- running a test ---------------------------------------------------------------------------

await page.getByRole('button', { name: 'Run tests' }).click()
check(
  'running tests grades what is in the editor',
  await waitUntil(() => graded.length === 1 && graded[0].includes('older attempt')),
  graded[0],
)
check('and shows the feedback', await waitUntil(() => page.getByText('All tests passed').isVisible()))
check(
  'the history picks up the run that just happened',
  await waitUntil(() => page.getByText('Previous tests (3)').isVisible()),
)
await shot('02-after-run')

// --- nothing tested yet ------------------------------------------------------------------------

submissions = []
await openTestingTab()
// Via the placeholder, not innerText: CodeMirror renders the placeholder inside .cm-content, so an
// empty editor reads as "Write, paste or drag your solution here..." rather than as nothing.
check(
  'with no previous attempts the editor is empty',
  await waitUntil(() => page.locator('.cm-placeholder').first().isVisible()),
)
check('and holds none of the earlier solutions', !(await editor().innerText()).includes('print('))
check('and it says so', await waitUntil(() => page.getByText(/No test results yet/).isVisible()))
check('with no last-tested line', (await page.getByText(/Last tested/).count()) === 0)
await shot('03-no-history')

await browser.close()
process.exit(check.summary() ? 0 : 1)
