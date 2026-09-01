/**
 * A teacher opening a student's submission and saving a grade — the thing this application is for,
 * and until now the one flow with no coverage at all.
 *
 * `CourseExercisePage` had 51 checks across four specs before this: the embed action, teacher
 * testing, the assessment tab and retry. None of them selected a student, read their solution, or
 * saved anything. `doc/testing.md` calls this out as the most expensive kind of bug the app can
 * have, and it is right — a wrong grade is not a crash, it is a number a student is judged on.
 *
 * What gets asserted here is deliberately the **request body**, not the rendering. A grade that
 * looks right on screen and reaches core as a different number, or reaches it twice, or notifies a
 * student twice, is invisible from the DOM.
 *
 *   cd web && npx playwright test course-exercise-grading
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const EX = '9001'
const STUDENT = 's-mari'
const OTHER = 's-jaan'
const SUBMISSION = 'sub-77'

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
  // TEACHER, so the grading form is the point of the page rather than an afterthought to autograding.
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  // Deliberately not 100. A threshold read from a template default instead of the response would
  // still look plausible at 100 and is caught here.
  threshold: 60,
  last_modified: '2026-07-30T12:00:00.000Z',
  student_visible: true,
  student_visible_from: null,
  assessments_student_visible: true,
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: null,
  executors: null,
  has_lib_access: true,
  exception_students: null,
  exception_groups: null,
}

const row = (id, given, family, grade, status) => ({
  student_id: id,
  given_name: given,
  family_name: family,
  groups: [],
  status,
  submission:
    grade === null
      ? { id: `${id}-sub`, submission_number: 1, time: '2026-08-01T10:00:00.000Z', grade: null, seen: false }
      : {
          id: `${id}-sub`,
          submission_number: 1,
          time: '2026-08-01T10:00:00.000Z',
          grade: { grade, is_autograde: false, is_graded_directly: true },
          seen: true,
        },
})

/**
 * The full `ExercisesResp`, not just `{ latest_submissions }`.
 *
 * The app only reads `latest_submissions` off this endpoint, so a two-field fixture worked — and
 * described a response core cannot produce. The contract check named all sixteen missing fields;
 * this is what core actually sends.
 */
const latestStudents = {
  course_exercise_id: CE,
  exercise_id: EX,
  library_title: 'Sum of two numbers',
  title_alias: null,
  effective_title: 'Sum of two numbers',
  grade_threshold: 60,
  student_visible: true,
  student_visible_from: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'TEACHER',
  ordering_idx: 0,
  unstarted_count: 0,
  ungraded_count: 1,
  started_count: 0,
  completed_count: 1,
  latest_submissions: [
    row(STUDENT, 'Mari', 'Maasikas', null, 'UNGRADED'),
    row(OTHER, 'Jaan', 'Tamm', 80, 'COMPLETED'),
  ],
}

/**
 * Mari's grade, as the server would hold it.
 *
 * Mutable, and that is not decoration. The first version of this spec answered every read with
 * `grade: null` however many times the page had saved, so the page never learned the grade had
 * landed and "re-saving an unchanged grade" looked like a bug in the app. It was a bug in the
 * fixture — the exact trap `doc/web/browser-testing.md` records under "model the state change, not
 * just the response".
 */
let savedGrade = null

test('course-exercise-grading', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'ce-grading-' })

  // Every write the page can make, recorded rather than answered blindly. The whole spec is about
  // what lands in these.
  const grades = []
  const feedbacks = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/basic`, () => ({
      title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
      moodle_course_url: null,
    })],
    [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
    // AppLayout fires this during boot, before activeRole settles to teacher. Unstubbed it falls
    // through to the catch-all `{}`, `.then(r => r.exercises)` yields undefined, and react-query
    // logs "Query data cannot be undefined" on every run — which `{ log: false }` then makes the
    // only signal that anything is unstubbed at all.
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    // Writes first: `/submissions/latest/students` and `/submissions/{id}/grade` both contain
    // "/submissions", so a substring handler for the list would swallow the POST.
    [/\/submissions\/[^/]+\/grade$/, ({ method, body, url }) => {
      // The URL as well as the body. `handleSubmit` posts to the *selected* submission, so grading
      // the wrong one — an older attempt, or under the wrong course exercise — produces a perfectly
      // valid body and is invisible to every assertion that only reads bodies.
      if (method === 'POST') {
        grades.push({ body, path: new URL(url).pathname })
        savedGrade = body.grade
      }
      return {}
    }],
    [/\/submissions\/[^/]+\/feedback$/, ({ method, body, url }) => {
      if (method === 'POST') feedbacks.push({ body, path: new URL(url).pathname })
      return {}
    }],
    [/\/submissions\/latest\/students(\?|$)/, () => ({
      ...latestStudents,
      // Mari's row reflects what has been saved. Leaving this frozen is the same trap the detail
      // endpoint fell into below, on the endpoint that drives both the list and the student picker.
      latest_submissions: [
        savedGrade === null
          ? row(STUDENT, 'Mari', 'Maasikas', null, 'UNGRADED')
          : row(STUDENT, 'Mari', 'Maasikas', savedGrade, 'COMPLETED'),
        row(OTHER, 'Jaan', 'Tamm', 80, 'COMPLETED'),
      ],
    })],
    // Keyed on the student in the path. Returning Mari's submission for Jaan would hand the
    // grading view someone else's solution the moment anyone uses the student picker, and the
    // failure would read as a product bug.
    [/\/submissions\/all\/students\//, ({ url }) => ({
      submissions: [{
        id: url.includes(OTHER) ? `${OTHER}-sub` : SUBMISSION,
        submission_number: 1,
        created_at: '2026-08-01T10:00:00.000Z',
        status: url.includes(OTHER) ? 'COMPLETED' : savedGrade === null ? 'UNGRADED' : 'COMPLETED',
        grade: url.includes(OTHER)
          ? { grade: 80, is_autograde: false, is_graded_directly: true }
          : savedGrade === null
            ? null
            : { grade: savedGrade, is_autograde: false, is_graded_directly: true },
      }],
    })],
    [/\/students\/[^/]+\/activities$/, () => ({ teacher_activities: [] })],
    [/\/students\/[^/]+\/inline-comments$/, () => ({ inline_comments: [] })],
    [/\/submissions\/[^/]+$/, () => ({
      id: SUBMISSION,
      solution: 'a = int(input())\nb = int(input())\nprint(a + b)\n',
      submission_number: 1,
      created_at: '2026-08-01T10:00:00.000Z',
      grade: savedGrade === null
        ? null
        : { grade: savedGrade, is_autograde: false, is_graded_directly: true },
      seen: true,
      // A teacher-graded exercise never ran an autograder, so NONE with no assessment is the
      // honest answer — but core sends both fields regardless, and a stub that omits them is
      // describing a response that cannot happen.
      autograde_status: 'NONE',
      auto_assessment: null,
    })],
    [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  // --- the receiving end of the grade table's deep link ------------------------------------------
  // GradeTablePage builds `?student=<id>` on every cell. Nothing has ever checked that the page it
  // links to honours it, which makes the link half-tested from both ends.
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}?student=${STUDENT}`)
  await page.getByText('Sum of two numbers').first().waitFor()

  check(
    'a ?student= link opens that student directly, not the list',
    await waitUntil(async () => (await page.getByText('Mari Maasikas').count()) > 0),
  )
  check(
    'and it is that student rather than the first on the course',
    (await page.getByText('Jaan Tamm').count()) === 0,
  )
  check(
    "the student's solution is shown, not an empty editor",
    await waitUntil(async () => (await page.getByText('int(input())').count()) > 0),
  )
  await shot('01-student-opened')

  // The feedback composer builds its own toolbar rather than using MarkdownToolbar, so the wrap
  // switch every other markdown surface inherits has to be repeated in it — which makes this the
  // one place it can quietly go missing (EZ-1841).
  check(
    'the feedback composer offers the wrap switch its hand-built toolbar has to carry itself',
    await page.getByRole('button', { name: /Wrap long lines/i }).first().isVisible(),
  )
  check(
    'and prose wraps here by default, whatever code is set to',
    await page
      .locator('.cm-content')
      .last()
      .evaluate((el) => el.classList.contains('cm-lineWrapping')),
  )

  // --- the grade field refuses what core would reject --------------------------------------------
  // The important half is that a refused grade sends **nothing**. A page that posts 150 and lets the
  // backend say no is a page that has already changed a grade by the time it finds out.
  // `input[inputmode=numeric]` rather than a role or a label, because the field has **neither**:
  // it is a bare MUI TextField with no `label` and no `aria-label`, so the accessibility tree calls
  // it an unnamed textbox. Worth writing down rather than working around silently — a screen reader
  // announces the one control that sets a student's grade as "edit text, blank".
  const gradeField = page.locator('input[inputmode="numeric"]').first()
  await gradeField.waitFor()

  const saveButton = page.getByRole('button', { name: 'Save', exact: true })

  // Above 100 is typeable (three digits pass the field's own filter) and invalid, which is the
  // case that has to reach the guard rather than the server.
  await gradeField.fill('150')
  await page.waitForTimeout(150)
  check(
    'a grade above 100 leaves Save disabled rather than posting and apologising',
    !(await saveButton.isEnabled()),
  )
  if (await saveButton.isEnabled()) await saveButton.click().catch(() => {})
  await page.waitForTimeout(200)
  check('and sends nothing', grades.length === 0, `${grades.length} sent: ${JSON.stringify(grades[0] ?? null)}`)

  // A negative cannot even be typed: the field's onChange only accepts /^\d{0,3}$/, so the
  // keystrokes are dropped and the value never becomes -5. Asserted on the field rather than on the
  // request, because "no request" here would pass whether or not the guard exists.
  await gradeField.fill('-5')
  await page.waitForTimeout(150)
  check(
    'a negative grade cannot be entered at all',
    (await gradeField.inputValue()) !== '-5',
    `field holds ${JSON.stringify(await gradeField.inputValue())}`,
  )
  check('and still nothing was sent', grades.length === 0, JSON.stringify(grades))

  // --- a real grade, and what actually goes on the wire -------------------------------------------
  await gradeField.fill('75')
  await page.waitForTimeout(150)
  await saveButton.click()

  check(
    'saving a valid grade posts once',
    await waitUntil(() => grades.length === 1),
    `${grades.length} request(s)`,
  )
  check(
    'with the grade as a number, not a string',
    grades[0]?.body.grade === 75,
    JSON.stringify(grades[0] ?? null),
  )
  check(
    'and snake_case notify_student, which is what core reads',
    grades[0] !== undefined && 'notify_student' in grades[0].body,
    JSON.stringify(grades[0] ?? null),
  )
  check(
    'and against the submission on screen, under this course exercise',
    grades[0]?.path === `/v2/teacher/courses/${COURSE}/exercises/${CE}/submissions/${SUBMISSION}/grade`,
    grades[0]?.path,
  )
  // Settled first. `grades.length` reaches 1 inside the route handler — before the mutation has
  // even resolved — so asserting immediately would pass for a regression that posts feedback
  // *after* the grade lands, which is the shape this check is named for.
  await page.waitForTimeout(500)
  check('and nothing was posted to feedback', feedbacks.length === 0, JSON.stringify(feedbacks))
  await shot('02-graded')

  // --- the same grade again is not a change --------------------------------------------------------
  // There is no un-grade endpoint and no reason to re-send an identical number; re-posting would
  // also produce a second teacher activity in the student's feed, out of nothing.
  const afterFirst = grades.length
  // Wait for Save to *become* disabled rather than sleeping for it. It only disables once the
  // mutation's invalidation has refetched the submission and `initialGrade` has caught up — a round
  // trip plus four refetches plus a render. A fixed 150ms wins on a quiet laptop and loses on a
  // loaded runner, and when it loses this spec accuses the app of double-posting grades.
  await gradeField.fill('75')
  await waitUntil(async () => !(await saveButton.isEnabled()), { timeout: 10_000 })
  if (await saveButton.isEnabled().catch(() => false)) await saveButton.click().catch(() => {})
  await page.waitForTimeout(300)
  check(
    're-saving an unchanged grade sends nothing',
    grades.length === afterFirst,
    `${grades.length - afterFirst} extra request(s)`,
  )

  // --- a grade and feedback together notify the student once, not twice ----------------------------
  /**
   * The subtlest thing on this page, and the only place it is visible is the request body.
   *
   * `handleSubmit` sends `notifyStudent: !hasFeedback && notifyStudent` on the **grade**, then the
   * real `notify_student` on the **feedback**. Two posts, one notification — because a teacher
   * saving "72, and here is why" means one message to the student, not a bare number followed by a
   * comment. Get it wrong and every graded-with-comment student is emailed twice; nothing on screen
   * would differ, and nobody would report it as a bug in the grading page.
   */
  const feedbackEditor = page.locator('.cm-content').last()
  await feedbackEditor.click()
  await feedbackEditor.pressSequentially('Good, but watch the input parsing.')
  await gradeField.fill('72')
  await page.waitForTimeout(200)
  await saveButton.click()

  check(
    'a grade plus feedback posts both',
    await waitUntil(() => grades.length === afterFirst + 1 && feedbacks.length === 1),
    `${grades.length - afterFirst} grade(s), ${feedbacks.length} feedback(s)`,
  )
  check(
    'the feedback carries the text as markdown',
    feedbacks[0]?.body.feedback_md === 'Good, but watch the input parsing.',
    JSON.stringify(feedbacks[0] ?? null),
  )
  check(
    'the feedback is the one that notifies',
    feedbacks[0]?.body.notify_student === true,
    JSON.stringify(feedbacks[0] ?? null),
  )
  check(
    'and the grade beside it does not, so the student hears once',
    grades.at(-1)?.body.notify_student === false,
    JSON.stringify(grades.at(-1) ?? null),
  )
  await shot('03-grade-and-feedback')

  // --- back to the list ------------------------------------------------------------------------------
  // The deep link is a URL, so leaving has to change the URL too — otherwise Back lands on the same
  // student and the browser's history is a lie.
  await page.getByRole('button', { name: /back|tagasi/i }).first().click()
  check(
    'going back to the list drops ?student= from the URL',
    await waitUntil(() => !page.url().includes('student=')),
    page.url(),
  )
  check(
    'and the list shows every student again',
    // The list renders "Family, Given" — not the "Given Family" the grading view uses. Asserting
    // the grading view's form here would have failed against a perfectly correct list.
    await waitUntil(async () => (await page.getByText('Tamm, Jaan').count()) > 0),
  )
  await shot('04-back-to-list')

  await close()
})
