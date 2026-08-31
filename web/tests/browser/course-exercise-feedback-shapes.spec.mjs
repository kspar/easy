/**
 * What a student is actually told about each check inside an auto-assessment test — EZ-1834.
 *
 * OK_V3 lets a grader put its meaning in either of a check's two text fields, and the two graders
 * in production choose opposite ones:
 *
 *   silmused   `title` is the subtest's name, and `feedback` is '' when the check passed
 *   tiivad     `title` is '', and the whole message is in `feedback`
 *
 * `TestDetails` used to render `checks.filter(c => c.feedback)` and print `feedback` alone, which
 * is exactly right for one of them and silently deletes the other: on a database exercise every
 * passing subtest vanished and every failing one lost its name. A teacher reported it as "the
 * subtest titles are gone from the UI", and no fixture in the suite could catch it, because every
 * OK_V3 fixture in this repository was tiivad-shaped.
 *
 * So the fixture below is the point of this spec: one assessment carrying all four shapes a check
 * can have — title only, feedback only, both, and neither — plus a test with no checks at all.
 * Assertions are on the *text on screen*, because the bug was a field that never reached the DOM.
 *
 *   cd web && npx playwright test course-exercise-feedback-shapes
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'

/** `ExerciseDetails` — an SQL exercise, since this is the grader whose shape was being dropped. */
const exercise = {
  effective_title: 'Kodutöö 6: indeksid ja trigerid',
  text_html: '<p>Loo indeksid ja trigerid ning esita andmebaasi varukoopia.</p>',
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'backup.sql',
  solution_file_type: 'TEXT_EDITOR',
}

/**
 * silmused' own output shape, down to the details that did the damage: a passing check carries an
 * empty `feedback` string (it has nothing to explain), and a `type: 'message'` check carries no
 * `feedback` key at all. Both were dropped by the filter.
 */
const feedback = JSON.stringify({
  result_type: 'OK_V3',
  producer: 'silmused 1.7.11',
  finished_at: '2026-08-30T10:20:00Z',
  pre_evaluate_error: null,
  points: 60,
  tests: [
    {
      title: 'Indeksid',
      status: 'FAIL',
      exception_message: null,
      user_inputs: [],
      created_files: [],
      actual_output: null,
      converted_submission: null,
      checks: [
        // Title only, and passing: invisible before the fix, and the single most common shape in a
        // silmused assessment.
        { title: 'Tabelil klient on primaarvõti', status: 'PASS', feedback: '' },
        // Title and feedback both: the name said what was checked, the feedback why it failed.
        // Only the second half used to survive.
        {
          title: 'Indeks veerul tellimus.kliendi_id',
          status: 'FAIL',
          feedback: 'Indeksit veerul kliendi_id ei leitud.',
        },
        // No `feedback` key whatsoever — silmused' message check.
        { title: 'Käivitasin kontrollpäringu', status: 'PASS' },
      ],
    },
    {
      title: 'Trigerid',
      status: 'PASS',
      exception_message: null,
      user_inputs: [],
      created_files: [],
      actual_output: null,
      converted_submission: null,
      checks: [
        // The tiivad shape, which has always rendered and must keep rendering unchanged.
        { title: '', status: 'PASS', feedback: 'Triger tellimus_log on olemas.' },
        { title: null, status: 'PASS', feedback: 'Triger käivitub INSERT järel.' },
        // Says nothing in either field. The only shape that may be dropped.
        { title: '', status: 'PASS', feedback: '' },
      ],
    },
    // Nothing to report at all: no checks, no output, no exception. Without a placeholder this
    // accordion opens onto an empty box, which reads as a broken page rather than as a quiet test.
    {
      title: 'Vaated',
      status: 'PASS',
      exception_message: null,
      user_inputs: [],
      created_files: [],
      actual_output: null,
      converted_submission: null,
      checks: [],
    },
  ],
})

const submissions = {
  submissions: [
    {
      id: '9001',
      number: 1,
      solution: '-- pg_dump\nCREATE TABLE klient (id serial primary key);\n',
      submission_time: '2026-08-30T10:19:00.000Z',
      autograde_status: 'COMPLETED',
      grade: { grade: 60, is_autograde: true, is_graded_directly: true },
      submission_status: 'COMPLETED',
      auto_assessment: { grade: 60, feedback },
    },
  ],
}

test('course-exercise-feedback-shapes', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'student', shotPrefix: 'ce-feedback-shapes-' })

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

  // The first failing test auto-expands, so the Indeksid checks are on screen without a click.
  check(
    'the failing test opens by itself',
    await waitUntil(() => page.getByText('Indeksit veerul kliendi_id ei leitud.').isVisible()),
  )
  check(
    'a passing check that carries only a title is shown, not swallowed',
    await page.getByText('Tabelil klient on primaarvõti').isVisible(),
  )
  check(
    'a check with a title but no feedback field at all is shown too',
    await page.getByText('Käivitasin kontrollpäringu').isVisible(),
  )
  check(
    'and a failing check keeps its name alongside the explanation',
    await page.getByText('Indeks veerul tellimus.kliendi_id').isVisible(),
  )
  await shot('01-titles')

  // Only one test is expanded at a time, so the tiivad-shaped one needs a click.
  await page.getByRole('button', { name: /Trigerid/ }).click()
  check(
    'a check that carries only feedback still reads exactly as before',
    await waitUntil(() => page.getByText('Triger tellimus_log on olemas.').isVisible()),
  )
  check(
    'including one whose title is null rather than empty',
    await page.getByText('Triger käivitub INSERT järel.').isVisible(),
  )

  await page.getByRole('button', { name: /Vaated/ }).click()
  check(
    'a test with nothing to report says so instead of opening onto an empty box',
    await waitUntil(() => page.getByText('This test reported no checks').isVisible()),
  )
  await shot('02-empty-test')

  await close()
})
