/**
 * Soft wrap as a setting, and the two scopes it is kept in.
 *
 * Every CodeMirror in the app used to wrap, which is right for prose and wrong for code: a wrapped
 * line hides where the line ends, so indentation stops lining up and a column number stops meaning
 * anything. Code now defaults to not wrapping, markdown still does, and each is remembered
 * separately in this browser.
 *
 * The assertions are on CodeMirror's own `cm-lineWrapping` class — the class `EditorView.lineWrapping`
 * exists to add — and on the stored keys, because the bug this guards against is not "the toggle
 * does nothing on screen" but "the two scopes are one setting wearing two names".
 *
 *   cd web && npx playwright test editor-soft-wrap
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const EX = '4242'
const DIR = '77'

const CODE_KEY = 'editor.softWrap.code'
const MD_KEY = 'editor.softWrap.markdown'

/** One very long line, so wrapping or not is a visible fact about the layout. */
const longLine = `INSERT INTO public.klient VALUES (1, ${"'x'".repeat(120)});`

const exercise = {
  effective_title: 'Kodutöö 6',
  text_html: '<p>Esita varukoopia.</p>',
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'backup.sql',
  solution_file_type: 'TEXT_EDITOR',
}

const submissions = {
  submissions: [
    {
      id: '9001',
      number: 1,
      solution: `${longLine}\n${longLine}\n`,
      submission_time: '2026-08-30T10:19:00.000Z',
      autograde_status: 'COMPLETED',
      grade: { grade: 60, is_autograde: true, is_graded_directly: true },
      submission_status: 'COMPLETED',
      auto_assessment: null,
    },
  ],
}

const libExercise = {
  dir_id: DIR,
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-07-30T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Sum of two numbers',
  text_html: '<p>Read two numbers and print their sum.</p>',
  text_md: `Read two numbers and print their sum. ${'and again '.repeat(40)}`,
  anonymous_autoassess_template: '',
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: [],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

const wraps = (page) =>
  page.locator('.cm-content').first().evaluate((el) => el.classList.contains('cm-lineWrapping'))

const stored = (page, key) => page.evaluate((k) => localStorage.getItem(k), key)

test('editor-soft-wrap', async ({ launch, check }) => {
  // --- code: a student's solution ------------------------------------------------------------
  const { page, shot, close } = await launch({ role: 'student', shotPrefix: 'wrap-' })
  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [/\/statistics(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/statistics\/common(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/messages(\?|$)/, () => ({ messages: [] })],
    [`/student/courses/${COURSE}/exercises/${CE}/submissions/all`, () => submissions],
    [`/student/courses/${COURSE}/exercises/${CE}/draft`, ({ route }) => route.fulfill({ status: 204, body: '' })],
    [`/student/courses/${COURSE}/exercises/${CE}/activities`, () => ({ teacher_activities: [] })],
    [`/student/courses/${COURSE}/exercises/${CE}/inline-comments`, () => ({ inline_comments: [] })],
    [new RegExp(`/student/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise],
    [new RegExp(`/student/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await waitUntil(() => page.locator('.cm-content').first().isVisible())

  check('a solution does not wrap by default', !(await wraps(page)))

  const overflows = await page
    .locator('.cm-scroller')
    .first()
    .evaluate((el) => el.scrollWidth > el.clientWidth + 4)
  check('so a long line runs off to the side and the editor scrolls sideways', overflows)
  await shot('01-code-unwrapped')

  await page.getByRole('button', { name: /More options/i }).click()
  await page.getByRole('menuitem', { name: /Wrap long lines/i }).click()
  check('the menu switches wrapping on', await waitUntil(() => wraps(page)))
  check('and records it under the code key', (await stored(page, CODE_KEY)) === '1')
  check('leaving the markdown setting alone', (await stored(page, MD_KEY)) === null)
  await shot('02-code-wrapped')

  // The point of persisting it: the next visit is the one that would otherwise undo the choice.
  await page.reload()
  await waitUntil(() => page.locator('.cm-content').first().isVisible())
  check('and it survives a reload', await waitUntil(() => wraps(page)))
  await close()

  // --- markdown: the exercise text, in a fresh browser with nothing stored --------------------
  const teacher = await launch({ role: 'teacher,admin', shotPrefix: 'wrap-md-' })
  await fakeApi(teacher.page, [
    ['/account/checkin', () => ({})],
    ['/preview/markdown', ({ body }) => ({ content: `<p>${body.content}</p>` })],
    ['/teacher/courses', () => ({ courses: [] })],
    [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Algoritmid' }] })],
    [new RegExp(`/exercises/${EX}(\\?|$)`), () => libExercise],
  ], { log: false })

  await teacher.page.goto(`${BASE_URL}/library/exercise/${EX}/sum`)
  await teacher.page.waitForSelector('text=Sum of two numbers')
  await teacher.page.getByRole('button', { name: 'Edit', exact: true }).click()
  await teacher.page.getByRole('button', { name: 'Save', exact: true }).waitFor()

  check('markdown wraps by default, which is the opposite default', await wraps(teacher.page))

  await teacher.page.getByRole('button', { name: /Wrap long lines/i }).click()
  check('and the same switch turns it off here', await waitUntil(async () => !(await wraps(teacher.page))))
  check('recorded under the markdown key', (await stored(teacher.page, MD_KEY)) === '0')
  check(
    'with the code setting untouched — two settings, not one',
    (await stored(teacher.page, CODE_KEY)) === null,
  )
  await teacher.shot('03-markdown-unwrapped')
  await teacher.close()
})
