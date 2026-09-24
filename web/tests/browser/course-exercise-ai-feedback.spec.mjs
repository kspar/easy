/**
 * The student's "Explain with AI" button and the card it produces (EZ-1712).
 *
 * Three things make the feature safe or unsafe, and they are what is asserted:
 *
 *  - the button appears only when there is something to explain: AI on for the course, the latest
 *    submission graded, and tests failed. Every other state has no button, not a disabled one;
 *  - the explanation arrives in the feed as a card that says AI and names nobody — a card that
 *    looked like the teacher's would be the one way this feature could mislead;
 *  - once explained, the button is gone, so a second click cannot cost a second call.
 *
 *   cd web && npx playwright test course-exercise-ai-feedback
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const SUB = '9001'

const exercise = (aiEnabled) => ({
  effective_title: 'Sum of two numbers',
  text_html: '<p>Read two integers and print their sum.</p>',
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'sum.py',
  solution_file_type: 'TEXT_EDITOR',
  ai_feedback_enabled: aiEnabled,
})

const feedback = (status) => JSON.stringify({
  result_type: 'OK_V3',
  producer: 'tiivad 1.0',
  finished_at: '2026-08-30T10:20:00Z',
  pre_evaluate_error: null,
  points: status === 'FAIL' ? 40 : 100,
  tests: [{
    title: 'Handles negatives',
    status,
    exception_message: null,
    user_inputs: ['-1', '2'],
    created_files: [],
    actual_output: '3',
    converted_submission: null,
    checks: [{ title: '', status, feedback: status === 'FAIL' ? 'Expected 1' : 'OK' }],
  }],
})

const submissions = (grade) => ({
  submissions: [{
    id: SUB,
    number: 1,
    solution: 'print(abs(int(input())) + int(input()))',
    submission_time: '2026-08-30T10:19:00.000Z',
    autograde_status: 'COMPLETED',
    grade: { grade, is_autograde: true, is_graded_directly: true },
    submission_status: 'COMPLETED',
    auto_assessment: { grade, feedback: feedback(grade < 100 ? 'FAIL' : 'PASS') },
  }],
})

const aiFeedback = () => ({
  id: '77',
  submission_id: SUB,
  submission_number: 1,
  created_at: '2026-08-30T10:21:00.000Z',
  provider: 'ANTHROPIC',
  model: 'claude-opus-5',
  feedback_md: 'Your program takes the *absolute value* of the first number, so a negative input is treated as positive.',
  feedback_html: '<p>Your program takes the <em>absolute value</em> of the first number, so a negative input is treated as positive.</p>',
})

test('course-exercise-ai-feedback', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'student', language: 'en', shotPrefix: 'ce-ai-feedback-' })

  let aiEnabled = true
  let grade = 40
  let explained = false
  const explainCalls = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [/\/statistics(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/messages(\?|$)/, () => ({ messages: [] })],

    // Ahead of everything else under the exercise, since its URL contains theirs.
    ['/ai-feedback', ({ url, method, body }) => {
      if (method === 'POST') {
        explained = true
        explainCalls.push({ url, body })
      }
      return aiFeedback()
    }],

    [`/student/courses/${COURSE}/exercises/${CE}/submissions/all`, () => submissions(grade)],
    [`/student/courses/${COURSE}/exercises/${CE}/draft`, ({ route }) => route.fulfill({ status: 204, body: '' })],
    [`/student/courses/${COURSE}/exercises/${CE}/activities`, () => ({
      teacher_activities: [],
      ai_feedback: explained ? [aiFeedback()] : [],
    })],
    [`/student/courses/${COURSE}/exercises/${CE}/inline-comments`, () => ({ inline_comments: [] })],
    [new RegExp(`/student/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise(aiEnabled)],
    [new RegExp(`/student/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises: [] })],
  ], { log: false })

  const open = async () => {
    await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
    await page.getByText('Sum of two numbers').first().waitFor({ timeout: 15000 })
  }
  const explainButton = () => page.getByRole('button', { name: /Explain with AI/i })

  // --- the happy path ---------------------------------------------------------------------------
  await open()
  check('the button is offered on a failed, graded submission', await waitUntil(() => explainButton().isVisible()))
  check('and nothing says AI before anyone asked', (await page.getByText('AI explanation').count()) === 0)
  await shot('01-button')

  await explainButton().click()

  check('clicking it POSTs once', await waitUntil(() => explainCalls.length === 1))
  check(
    'to the submission\'s own endpoint',
    explainCalls[0]?.url.includes(`/student/courses/${COURSE}/exercises/${CE}/submissions/${SUB}/ai-feedback`),
  )
  check('with the UI language in the body', explainCalls[0]?.body?.language === 'en')

  const card = page.getByText('absolute value')
  check('the explanation appears in the feed without a reload', await waitUntil(() => card.isVisible()))
  check('labelled as AI', (await page.getByText('AI explanation').count()) > 0)
  check('with the disclaimer under it', (await page.getByText(/not written by your teacher/i).count()) > 0)
  check(
    'and the heading no longer says "Teacher feedback"',
    (await page.getByRole('heading', { name: /^Teacher feedback$/ }).count()) === 0,
  )
  check('the button is gone once explained', await waitUntil(async () => (await explainButton().count()) === 0))
  await card.scrollIntoViewIfNeeded()
  await shot('02-explained')

  // --- no button when there is nothing to explain -----------------------------------------------
  explained = false
  grade = 100
  await open()
  check(
    'no button when every test passed',
    await waitUntil(async () => (await explainButton().count()) === 0),
  )

  grade = 40
  aiEnabled = false
  await open()
  check(
    'no button when the course has AI switched off',
    await waitUntil(async () => (await explainButton().count()) === 0),
  )
  check('and the failed tests are still shown', (await page.getByText('Handles negatives').count()) > 0)

  await close()
})
