/**
 * Embedding from a course exercise.
 *
 * wui offered this and the React app did not, so the only route to a snippet was via the library.
 * The dialog is the same one the library page opens; what differs is that it has to fetch the
 * library exercise itself — the course exercise response carries neither the embed flag nor the
 * starting code — and that it knows which course it was opened from.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const EX = '9001'

/** The course exercise view. Deliberately without the embed fields — that is the point. */
let courseExercise = {
  exercise_id: EX,
  title: 'Sum of two numbers',
  title_alias: null,
  text_html: '<p>Read two integers and print their sum.</p>',
  instructions_html: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
  student_visible: true,
  student_visible_from: null,
  has_lib_access: true,
  exception_students: null,
  exception_groups: null,
}

/** The library exercise, which is where the embed settings actually live. */
const libraryExercise = {
  dir_id: '77',
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: true,
  owner_id: 'kspar',
  last_modified: '2026-07-30T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Sum of two numbers',
  text_html: '<p>Read two integers and print their sum.</p>',
  text_md: 'Read two integers and print their sum.',
  anonymous_autoassess_template: 'a = int(input())\n',
  grading_script: 'x',
  container_image: 'pygrader',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [],
  executors: [],
  on_courses: [
    { id: '148', title: 'Algoritmid', alias: null, course_exercise_id: '5944', course_exercise_title_alias: null },
    { id: COURSE, title: 'Programmeerimise alused', alias: null, course_exercise_id: CE, course_exercise_title_alias: null },
  ],
  on_courses_no_access: 0,
}

test('course-exercise-embed', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'ce-embed-' })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    // Anchored: unanchored it also answers `.../submissions/latest/students` with a single
    // exercise, which core never does. Flagged by fakeApi's [broad stub] warning.
    [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => courseExercise],
    // Before the plain `/exercises/${EX}` handler below, which would otherwise match this URL as a
    // substring and hand the embed page a library exercise. It has a title and text, so the preview
    // still looked plausible — it just quietly lost `submit_allowed` and never showed the editor.
    ['anonymous/details', () => ({
      title: libraryExercise.title,
      text_html: libraryExercise.text_html,
      anonymous_autoassess_template: libraryExercise.anonymous_autoassess_template,
      submit_allowed: true,
    })],
    [`/exercises/${EX}`, () => libraryExercise],
    [`/lib/dirs/${libraryExercise.dir_id}/parents`, () => ({ parents: [] })],
    ['/participants', () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
    ['/groups', () => ({ groups: [] })],
    // `/submissions/latest/students` first, because it is the one endpoint under `/submissions/`
    // that answers with something else entirely: core sends the whole ExercisesResp, and the hook
    // reads `.latest_submissions` off it. Falling through to the family stub below yields
    // `undefined` and react-query logs "Query data cannot be undefined" on every run.
    //
    // Anchoring the course-exercise handler silenced fakeApi's [broad stub] warning without fixing
    // what the warning pointed at — the request simply moved one handler down. Caught in review.
    [/\/submissions\/latest\/students(\?|$)/, () => ({ latest_submissions: [] })],
    ['/submissions/', () => ({ submissions: [], count: 0 })],
    // These two map a field off the response, so the catch-all's `{}` yields undefined and
    // react-query complains. Not what this script tests, but an unexplained console error is a
    // place real ones go to hide.
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    ['/latest', () => ({ submissions: [], count: 0 })],
  ], { log: false })

  async function openPage() {
    await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
    await page.getByText('Sum of two numbers').first().waitFor()
  }

  await openPage()

  const embedButton = page.getByRole('button', { name: 'Embedding' })
  check('the course exercise page offers embedding', await embedButton.isVisible())

  await embedButton.click()
  const dialog = page.getByRole('dialog')
  await dialog.waitFor()
  check('it opens the same embed dialog', await dialog.getByText('Snippet options').isVisible())
  check(
    'and it loaded the library exercise the course response does not carry',
    await waitUntil(() => dialog.getByRole('switch', { name: 'Allow embedding' }).isChecked()),
  )

  // The course you came from is the link you almost always want.
  const linkField = dialog.getByRole('combobox', { name: 'Course' })
  check(
    'the current course is preselected',
    (await linkField.innerText()).includes('Programmeerimise alused'),
  )
  const snippet = () => dialog.locator('.cm-content').filter({ hasText: '<iframe' }).first().innerText()
  check('so the snippet already carries the course link', (await snippet()).includes(`course=${COURSE}`))
  check('and the course exercise id', (await snippet()).includes(`exercise=${CE}`))

  await linkField.click()
  const options = page.getByRole('option')
  // nth(1), not first: "No link" stays at the top as the neutral choice, so the current course is
  // first among the actual courses — ahead of Algoritmid, which precedes it in the API response.
  check(
    'the current course is listed first among the courses',
    (await options.nth(1).innerText()).includes('Programmeerimise alused'),
  )
  check('and marked as the one you are on', (await options.nth(1).innerText()).includes('(this course)'))
  await page.keyboard.press('Escape')

  // The starting code only appears once testing is on — it exists to pre-fill an editor that is
  // otherwise not there. From a course page its reach is least obvious, so it says so out loud: a
  // teacher here has every reason to assume they are editing something course-specific.
  await dialog.getByRole('switch', { name: 'Allow submitting and testing' }).click()
  check(
    'the starting code warns that it is shared across courses',
    await waitUntil(() => dialog.getByText(/belongs to the exercise, not to this course/).isVisible()),
  )
  // Assert on what the preview actually renders, not just that an iframe exists. Without this the
  // stub shadowing above went unnoticed: the frame showed a title and text and looked fine.
  const preview = dialog.frameLocator('iframe')
  check(
    'the preview shows the solution editor once testing is on',
    await waitUntil(async () => (await preview.locator('.cm-content').count()) > 0),
  )
  check(
    'and the preview carries the course link',
    await preview.getByRole('link', { name: /Sum of two numbers\s+Lahendus/ }).isVisible(),
  )
  await shot('01-dialog')

  await page.keyboard.press('Escape')
  await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)

  // No library access means no embed settings to read or change, so the action is not offered.
  courseExercise = { ...courseExercise, has_lib_access: false }
  await openPage()
  check(
    'no embedding action without library access',
    (await page.getByRole('button', { name: 'Embedding' }).count()) === 0,
  )

  // --- the shortcut through to the library exercise -------------------------------------------------
  // It pointed at /library/<id>, which matches no route, so it silently landed on NotFoundPage. The
  // assertion follows the link rather than checking the href, because a URL that looks right and
  // resolves to nothing is exactly the failure that shipped.
  courseExercise = { ...courseExercise, has_lib_access: true }
  await openPage()
  const libLink = page.getByRole('link', { name: "Open in exercise library" })
  check('the library shortcut is a real link', (await libLink.count()) === 1)
  check(
    'and it addresses the library exercise route',
    (await libLink.getAttribute('href')) === `/library/exercise/${EX}/Sum-of-two-numbers`,
  )
  await libLink.click()
  check(
    'following it opens the exercise, not the not-found page',
    await waitUntil(() => page.getByRole('tab', { name: 'Exercise' }).isVisible()),
  )
  check('and the url is the library exercise', page.url().includes(`/library/exercise/${EX}/`))

  await close()
})
