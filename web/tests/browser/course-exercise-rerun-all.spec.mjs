/**
 * Re-running the tests on every submission of one course exercise, from the students tab.
 *
 * The per-submission button already had a spec (course-exercise-retry-autoassess). What is new here
 * is a loop, and a loop is where the interesting failures are — none of which are visible from a
 * screenshot of the finished state:
 *
 *  - **it runs them one at a time.** The obvious implementation fires every POST at once, looks
 *    identical when it finishes, and hands core a whole course's worth of grading in one breath.
 *    Asserted by counting requests in flight, not by counting requests.
 *  - **it respects the group filter.** The teacher picks a group and the button's blast radius has
 *    to shrink with it. A version that re-runs the whole course anyway is silent — the rows the
 *    teacher can see all update correctly.
 *  - **grades move while it runs**, rather than all at the end. That is the entire reason this is
 *    driven from the browser instead of by a bulk endpoint, so a version that only refreshes once
 *    at the end has lost the feature while passing every other check.
 *  - **it skips students with nothing submitted**, who have no submission id to re-run.
 *
 *   cd web && npx playwright test course-exercise-rerun-all
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '204'
const CE = '5150'
const EX = '9100'

const MARI = { id: 's-mari', given: 'Mari', family: 'Maasikas', sub: 'sub-mari', group: 'g1' }
const JAAN = { id: 's-jaan', given: 'Jaan', family: 'Tamm', sub: 'sub-jaan', group: 'g2' }
// No submission at all: there is nothing to re-run for Kati, and a loop that does not notice would
// POST to `/submissions/undefined/retry-autoassess`.
const KATI = { id: 's-kati', given: 'Kati', family: 'Vaher', sub: null, group: 'g1' }

/**
 * Four more, used only by the circuit-breaker phase.
 *
 * The breaker gives up after three failures in a row, so proving it needs a queue longer than three
 * — otherwise "it stopped" and "it ran out of students" are the same observation.
 */
const CROWD = ['a', 'b', 'c', 'd', 'e'].map((k, i) => ({
  id: `s-${k}`,
  given: `Uus${i}`,
  family: `Õpilane${i}`,
  sub: `sub-${k}`,
  group: 'g1',
}))

/** Who the course has. Swapped wholesale by the circuit-breaker phase. */
let roster = []
/** When true every re-run request 500s — a grader that is down, not one bad submission. */
let failEveryRetry = false

const GROUPS = [
  { id: 'g1', name: 'Rühm A' },
  { id: 'g2', name: 'Rühm B' },
]

/**
 * The grade core is holding for each submission, as a number.
 *
 * Mutable, and the whole spec turns on it: the re-run handler moves these, and the students list is
 * answered from them. A fixture that returned a constant would make "the grades update live" and
 * "the grades never update" look exactly the same on screen.
 */
let grades = {}

/** Retry requests, in the order core received them. */
let retryCalls = []
/** The most requests this run ever had open at once. Above 1 and the loop is not a loop. */
let maxInFlight = 0
let inFlight = 0

/**
 * One submission's grading, held open until the spec lets go of it.
 *
 * Two checks below have to be made in the window where one submission is in flight and the next is
 * not — "the grade lands while the next one is still running", and "cancel stops the ones behind
 * it". Racing for that window is a race this spec would lose on a loaded machine, so the window is
 * held open rather than waited for.
 */
let heldSubmission = null
let releaseHold = () => {}
let held = Promise.resolve()

/** Hold `submissionId`'s grading open; call the returned function to let it finish. */
const holdGradingOf = (submissionId) => {
  heldSubmission = submissionId
  held = new Promise((r) => { releaseHold = r })
  return () => releaseHold()
}

const resetServer = () => {
  roster = [MARI, JAAN, KATI]
  failEveryRetry = false
  grades = { [MARI.sub]: 20, [JAAN.sub]: 40 }
  retryCalls = []
  maxInFlight = 0
  inFlight = 0
  heldSubmission = null
  releaseHold()
}

const row = (s) => ({
  student_id: s.id,
  given_name: s.given,
  family_name: s.family,
  groups: [GROUPS.find((g) => g.id === s.group)],
  status: s.sub === null ? 'UNSTARTED' : grades[s.sub] >= 60 ? 'COMPLETED' : 'STARTED',
  submission:
    s.sub === null
      ? null
      : {
          id: s.sub,
          submission_number: 1,
          time: '2026-08-01T10:00:00.000Z',
          grade: { grade: grades[s.sub], is_autograde: true, is_graded_directly: true },
          seen: true,
        },
})

/** The full `ExercisesResp` core sends, filtered by `?group=` the way core filters it. */
const latestStudents = (url, graderType) => {
  const group = new URL(url).searchParams.get('group')
  const students = roster.filter((s) => !group || s.group === group)
  return {
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
    grader_type: graderType,
    ordering_idx: 0,
    unstarted_count: students.filter((s) => s.sub === null).length,
    ungraded_count: 0,
    started_count: students.filter((s) => s.sub !== null && grades[s.sub] < 60).length,
    completed_count: students.filter((s) => s.sub !== null && grades[s.sub] >= 60).length,
    latest_submissions: students.map(row),
  }
}

const exercise = (graderType) => ({
  exercise_id: EX,
  title: 'Sum of two numbers',
  title_alias: null,
  text_html: '<p>Read two integers and print their sum.</p>',
  text_md: 'Read two integers and print their sum.',
  instructions_html: null,
  instructions_md: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: graderType,
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
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
})

test('course-exercise-rerun-all', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'ce-rerun-all-' })

  let graderType = 'AUTO'
  resetServer()

  await fakeApi(page, [
    ['/account/checkin', () => ({})],

    // Above the submission-detail and course-exercise handlers, whose URLs this one also matches.
    // Shadowed the other way round, every check below would pass against a page that never called it.
    ['retry-autoassess', async ({ route, url, method }) => {
      if (method !== 'POST') return {}
      inFlight++
      maxInFlight = Math.max(maxInFlight, inFlight)
      retryCalls.push(url)
      if (failEveryRetry) {
        inFlight--
        await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
        return undefined // fulfilled by hand
      }
      // Core grades inside the request. The delay is what makes "one at a time" observable: without
      // it every response is instant and a parallel implementation looks sequential.
      await new Promise((r) => setTimeout(r, 150))
      const submissionId = url.match(/submissions\/([^/]+)\/retry-autoassess/)?.[1]
      if (submissionId === heldSubmission) await held
      if (submissionId in grades) grades[submissionId] = 100
      inFlight--
      return {}
    }],

    [`/teacher/courses/${COURSE}/exercises/${CE}/submissions/latest/students`,
      ({ url }) => latestStudents(url, graderType)],

    [`/courses/${COURSE}/groups`, () => ({ groups: GROUPS })],

    // Enough of one student's grading view to render. Only needed so that clicking a row mid-run
    // does not land on a screen that throws — if it did, the error boundary would take the page down
    // with it and "the run survived" would be untestable rather than false.
    [`/submissions/all/students/`, () => ({ submissions: [] })],
    ['/inline-comments', () => ({ inline_comments: [] })],
    ['/activities', () => ({ teacher_activities: [] })],
    [new RegExp(`/submissions/(${MARI.sub}|${JAAN.sub})(\\?|$)`), ({ url }) => ({
      id: url.includes(MARI.sub) ? MARI.sub : JAAN.sub,
      submission_number: 1,
      solution: 'print(a + b)',
      created_at: '2026-08-01T10:00:00.000Z',
      seen: true,
      autograde_status: 'COMPLETED',
      grade: { grade: grades[url.includes(MARI.sub) ? MARI.sub : JAAN.sub], is_autograde: true, is_graded_directly: true },
      auto_assessment: { grade: 100, feedback: 'ok' },
    })],

    // After everything under it, before the bare `/teacher/courses` at the bottom.
    [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise(graderType)],
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    ['/participants', () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
    ['/teacher/courses', () => ({ courses: [] })],
  ], { log: false })

  // No `?student=`, so the students tab rather than one student's grading view.
  const open = async () => {
    await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
    await page.getByText('Sum of two numbers').first().waitFor({ timeout: 15000 })
  }

  const rerunButton = page.getByRole('button', { name: 'Re-run all tests' }).first()
  // Scoped to the dialog: the toolbar button and the dialog's confirm button carry the same label,
  // and an unscoped locator would resolve to the one already on screen.
  const confirmButton = page.getByRole('dialog').getByRole('button', { name: 'Re-run all tests' })
  const gradeChip = (s) =>
    page.getByRole('button', { name: new RegExp(`${s.family}, ${s.given}`) })

  await open()

  check(
    'the button is offered on an auto-graded exercise',
    await waitUntil(() => rerunButton.isVisible()),
  )
  await shot('01-students-tab')

  // --- the whole course -------------------------------------------------------------------------
  const letJaanFinish = holdGradingOf(JAAN.sub)
  await rerunButton.click()

  check(
    'it confirms first, naming how many submissions it is about to re-grade',
    await waitUntil(async () => (await page.getByRole('dialog').innerText()).includes('2')),
  )
  check(
    'and Kati, who has submitted nothing, is not one of them',
    !(await page.getByRole('dialog').innerText()).includes('3'),
  )
  await shot('02-confirm')

  await confirmButton.click()

  // Sampled while it runs rather than after: the finished state of a parallel run and a sequential
  // one are identical, and this is the only window in which they differ.
  check(
    'the first submission is marked as running',
    await waitUntil(async () => (await page.getByRole('progressbar').count()) > 0),
  )
  await shot('03-running')

  // Jaan's grading is held open at this point, so this is squarely inside the window where the first
  // student is finished and the second is not. The endpoint returns no body, so a grade can only
  // reach the screen by a re-read — and a version that re-reads once at the end fails here while
  // passing every check below it.
  check(
    "Mari's new grade lands while Jaan is still being graded",
    await waitUntil(async () => /\b100\b/.test(await gradeChip(MARI).innerText())),
  )
  check(
    'and Jaan still shows his old grade at that moment',
    /\b40\b/.test(await gradeChip(JAAN).innerText()),
  )
  await shot('04-mid-run')

  letJaanFinish()

  check(
    'every submission on screen is re-run',
    await waitUntil(() => retryCalls.length === 2),
  )
  check(
    "and Jaan's new grade lands too",
    await waitUntil(async () => /\b100\b/.test(await gradeChip(JAAN).innerText())),
  )
  check(
    'one at a time, never two at once',
    maxInFlight === 1,
  )
  check(
    'in the order the list shows them — Maasikas before Tamm',
    retryCalls[0]?.includes(MARI.sub) && retryCalls[1]?.includes(JAAN.sub),
  )
  check(
    'and nothing is posted for the student with no submission',
    !retryCalls.some((u) => u.includes('undefined') || u.includes(KATI.id)),
  )
  check(
    'the run reports what it did when it finishes',
    await waitUntil(async () => (await page.getByRole('alert').count()) > 0),
  )
  await shot('05-done')

  // --- reading a student's code while the run continues ------------------------------------------
  // A sixty-student run takes minutes, and the obvious thing to do with those minutes is look at
  // someone's code. That click swaps this list out for the grading view, so a run owned by the list
  // would die there — silently, leaving a screen indistinguishable from one that finished.
  resetServer()
  await open()
  const letMariGo = holdGradingOf(MARI.sub)

  await rerunButton.click()
  await confirmButton.click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await waitUntil(() => retryCalls.length === 1)

  await page.getByRole('button', { name: /Vaher, Kati/ }).click()
  check(
    'clicking a student mid-run opens their submission',
    await waitUntil(async () => page.url().includes('student=')),
  )
  letMariGo()

  check(
    'and the run carries on behind it rather than dying with the list',
    await waitUntil(() => retryCalls.length === 2),
  )
  // Waited for rather than sampled, and not only to avoid a race in the check itself: `retryCalls`
  // is appended when a request *arrives*, so returning here on the count alone would leave Jaan's
  // grading in flight into the next phase, where it would land on top of the reset fixtures.
  check(
    'so every submission is still re-graded',
    await waitUntil(() => grades[MARI.sub] === 100 && grades[JAAN.sub] === 100),
  )
  await shot('05-run-survives-navigation')

  // --- cancelling partway through ---------------------------------------------------------------
  // A teacher who started this on the wrong exercise needs a way out, and the only useful meaning of
  // "cancel" here is that the submissions behind the current one are never touched. Mari's grading is
  // held open so the cancel lands squarely in the middle of the run rather than after it.
  resetServer()
  await open()
  const letMariFinish = holdGradingOf(MARI.sub)

  await rerunButton.click()
  await confirmButton.click()

  // The confirmation dialog's own button is also called Cancel, and it lingers for the length of
  // MUI's close transition. Waiting it out is what makes the locator below unambiguous — without
  // this, the click lands on a dialog that is on its way out and the run carries on regardless.
  await page.getByRole('dialog').waitFor({ state: 'detached' })

  const cancelButton = page.getByRole('button', { name: /^Cancel$/ })
  check(
    'a run in progress offers a way to stop it',
    await waitUntil(() => cancelButton.isVisible()),
  )
  await shot('06-cancellable')
  await cancelButton.click()
  letMariFinish()

  check(
    'cancelling leaves the submissions behind the current one alone',
    await waitUntil(async () => {
      // Given time to get it wrong: without the pause a loop that ignores the cancel entirely still
      // has its second POST ahead of it, and this passes against exactly the bug it is here for.
      await new Promise((r) => setTimeout(r, 600))
      return retryCalls.length === 1 && retryCalls[0].includes(MARI.sub)
    }),
  )
  // Core grades inside the request and finishes whether or not anyone is still listening, so a
  // cancel that dropped the in-flight request would lose a result that had already been written —
  // the row would keep showing 20 until something else happened to refetch it.
  check(
    'the submission already being graded still has its result recorded',
    await waitUntil(async () => /\b100\b/.test(await gradeChip(MARI).innerText())),
  )
  check(
    'and says so rather than claiming the whole run finished',
    await waitUntil(async () => {
      const text = await page.getByRole('alert').innerText()
      return text.includes('Stopped') && text.includes('1 of 2')
    }),
  )
  check(
    "Jaan's grade is untouched",
    grades[JAAN.sub] === 40,
  )
  await shot('07-cancelled')

  // --- one group only ---------------------------------------------------------------------------
  resetServer()
  await open()

  await page.getByRole('button', { name: /Rühm|Groups/ }).first().click()
  await page.getByRole('menuitem', { name: 'Rühm A' }).click()

  check(
    'filtering to a group leaves only its students on screen',
    await waitUntil(async () => (await gradeChip(JAAN).count()) === 0),
  )

  await rerunButton.click()
  check(
    'and the confirmation says which group it is about to re-grade',
    await waitUntil(async () => (await page.getByRole('dialog').innerText()).includes('Rühm A')),
  )
  await confirmButton.click()

  check(
    "only the shown group's submissions are re-run",
    // Settled first, for the same reason as the cancel check above: `retryCalls.length === 1` is
    // true a hundred milliseconds into a run that is about to POST for Jaan as well, so asserting
    // it the moment it goes true passes against exactly the bug this check exists to catch.
    await waitUntil(async () => {
      await new Promise((r) => setTimeout(r, 600))
      return retryCalls.length === 1 && retryCalls[0].includes(MARI.sub)
    }),
  )
  check(
    "and Jaan, who is filtered out, is left alone",
    grades[JAAN.sub] === 40,
  )
  await shot('08-group-only')

  // --- a grader that is down stops the run rather than being asked once per student --------------
  // The failures this loop actually meets are systemic — the executor is down, the token expired —
  // and they apply to everyone still queued. Without a limit a class of two hundred means two
  // hundred doomed POSTs, and for an expired session, two hundred goes at the app's sign-in
  // recovery while the loop grinds on.
  resetServer()
  roster = CROWD
  grades = Object.fromEntries(CROWD.map((s) => [s.sub, 30]))
  failEveryRetry = true
  await open()

  await rerunButton.click()
  await confirmButton.click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })

  check(
    'a grader failing for everyone stops the run instead of working through the class',
    await waitUntil(async () => {
      await new Promise((r) => setTimeout(r, 800))
      return retryCalls.length === 3
    }),
  )
  check(
    'and it says the grader is at fault rather than the submissions',
    await waitUntil(async () => (await page.getByRole('alert').innerText()).includes('wrong with the grader')),
  )
  await shot('09-grader-down')

  // --- a teacher-graded exercise has nothing to re-run -------------------------------------------
  resetServer()
  graderType = 'TEACHER'
  await open().catch(() => {})
  check(
    'the button is not offered when the exercise is teacher-graded',
    await waitUntil(async () => (await page.getByRole('button', { name: 'Re-run all tests' }).count()) === 0),
  )

  await close()
})
