/**
 * Verification driver for the X-026 fix, second iteration.
 *
 * The first iteration classified every non-OK_V3 feedback string as an infrastructure outage —
 * refuted in review: plain text is the legitimate answer of every legacy grader and of aae's
 * time/memory verdicts, and `pre_evaluate_error` is the *student's* pre-check failure (their
 * missing/empty/unparseable solution file), not the teacher's. The honest outage signal is
 * `autograde_status === 'FAILED'`, which core sets with no assessment attached.
 *
 * So the contract under test is now:
 *  1. an ordinary OK_V3 result is untouched;
 *  2. the student's own exception stays verbatim;
 *  3. `pre_evaluate_error` is shown verbatim as the student's own error, grade asserted as-is;
 *  4. plain-text legacy feedback renders as the assessment it is, with its real grade;
 *  5. `autograde_status: FAILED` — and only that — produces the calm outage message, both on the
 *     exercise page and as a label on the submission-history row.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x026-grader-failure-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, studentExercise, exerciseDetails, submission, baseHandlers } from './fixtures.mjs'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const OUTAGE_RE = /ei õnnestunud seekord käivitada/

const okV3Fail = () =>
  JSON.stringify({
    result_type: 'OK_V3',
    producer: 'tiivad',
    points: 50,
    pre_evaluate_error: null,
    tests: [
      {
        title: 'Programm töötab ka negatiivsete arvudega',
        status: 'FAIL',
        exception_message: null,
        user_inputs: ['-4', '2'],
        created_files: [],
        actual_output: '6\n',
        converted_submission: null,
        checks: [
          { title: 'Väljund sisaldab õiget summat', status: 'FAIL', feedback: 'Ootasin väljundis stringi "-2", aga seda ei leidnud.' },
        ],
      },
    ],
  })

const okV3Exception = () =>
  JSON.stringify({
    result_type: 'OK_V3',
    producer: 'tiivad',
    points: 0,
    pre_evaluate_error: null,
    tests: [
      {
        title: 'Programm küsib kaks arvu',
        status: 'FAIL',
        exception_message:
          "Traceback (most recent call last):\n  File \"lahendus.py\", line 3, in <module>\n    print(a + b)\nTypeError: unsupported operand type(s) for +: 'int' and 'str'",
        user_inputs: ['2', 'kolm'],
        created_files: [],
        converted_submission: null,
        actual_output: null,
        checks: [],
      },
    ],
  })

// The student's own file failed tiivad's pre-check — the shape core's own test data carries
// (CE 9009): a SyntaxError in the student's solution, before any test ran.
const okV3PreError = () =>
  JSON.stringify({
    result_type: 'OK_V3',
    producer: 'tiivad',
    points: 0,
    pre_evaluate_error: "SyntaxError: invalid syntax (lahendus.py, line 3)\n    for i in range(n\n                  ^",
    tests: [],
  })

// What pygrader/imgrec answers actually look like after aae's legacy parse: plain text.
const legacyPlainText = () => 'Test 1: OK\nTest 2: OK\nKõik testid läbitud.'

async function openWith(launch, { feedback, grade, autogradeStatus = 'COMPLETED', gradeObj }) {
  const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise({ status: 'STARTED' })] })],
      [
        new RegExp(`/exercises/${CE_ID}/submissions/all`),
        () => ({
          submissions: [
            submission({
              autograde_status: autogradeStatus,
              grade: gradeObj !== undefined ? gradeObj : { grade, is_autograde: true, is_graded_directly: false },
              submission_status: 'UNGRADED',
              auto_assessment: feedback == null ? null : { grade, feedback },
            }),
          ],
        }),
      ],
      [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
      [new RegExp(`/exercises/${CE_ID}/inline-comments`), () => ({ inline_comments: [] })],
      [
        new RegExp(`/exercises/${CE_ID}/draft`),
        async ({ route }) => {
          await route.fulfill({ status: 204, body: '' })
          return undefined
        },
      ],
      [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
    ],
    { log: false, contract: false },
  )
  await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
  await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(400)
  return page
}

await withBrowser(async ({ launch }) => {
  // ─── 1. the designed OK_V3 path is untouched ────────────────────────────────────────────────────
  {
    const page = await openWith(launch, { feedback: okV3Fail(), grade: 50 })
    check((await page.getByText(OUTAGE_RE).count()) === 0, 'an ordinary FAIL shows no outage message')
    check((await page.getByText('50 / 100').count()) > 0, 'the real grade is still asserted')
    check((await page.getByText(/Ootasin väljundis stringi/).count()) > 0, 'the check feedback still reaches the student')
    await page.close()
  }

  // ─── 2. the student's own exception stays verbatim ──────────────────────────────────────────────
  {
    const page = await openWith(launch, { feedback: okV3Exception(), grade: 0 })
    check((await page.getByText(OUTAGE_RE).count()) === 0, 'their own crash is not called an outage')
    await page.getByText('Programm küsib kaks arvu').first().click()
    await page.waitForTimeout(400)
    check((await page.getByText(/TypeError: unsupported operand/).count()) > 0, 'their own traceback is shown in full')
    await page.close()
  }

  // ─── 3. pre_evaluate_error is the student's own error, shown verbatim ───────────────────────────
  {
    const page = await openWith(launch, { feedback: okV3PreError(), grade: 0 })
    check((await page.getByText(OUTAGE_RE).count()) === 0, 'a syntax error in their own file is not called an outage')
    check(
      await page.getByText(/SyntaxError: invalid syntax \(lahendus\.py/).first().isVisible(),
      'the SyntaxError naming their own file is in the open',
    )
    check((await page.getByText('0 / 100').count()) > 0, 'the 0 is asserted — it is a true statement here')
    await page.close()
  }

  // ─── 4. legacy plain-text feedback renders as the assessment it is ──────────────────────────────
  {
    const page = await openWith(launch, { feedback: legacyPlainText(), grade: 100 })
    check((await page.getByText(OUTAGE_RE).count()) === 0, 'legacy plain-text feedback is not called an outage')
    check(await page.getByText(/Kõik testid läbitud/).first().isVisible(), 'the plain-text report is shown in the open')
    check((await page.getByText('100 / 100').count()) > 0, 'its real grade is asserted')
    await page.close()
  }

  // ─── 5. autograde_status FAILED — the one true outage — gets the calm message ───────────────────
  {
    const page = await openWith(launch, { feedback: null, autogradeStatus: 'FAILED', gradeObj: null })
    check((await page.getByText(OUTAGE_RE).count()) > 0, 'FAILED shows the outage message instead of silence')
    check((await page.getByText(/0 \/ 100/).count()) === 0, 'no grade is asserted for the outage')

    // The submission-history row carries a label too, not just an unfinished-looking row.
    // The rows render only once the accordion has been opened.
    await page.getByText(/Varasemad esitused/).first().click()
    await waitUntil(
      async () => (await page.getByText('Teste ei õnnestunud käivitada').count()) > 0,
      { timeout: 6000 },
    ).catch(() => {})
    check((await page.getByText('Teste ei õnnestunud käivitada').count()) > 0, 'the history row is labelled')
    await page.close()
  }

  // ─── 5b. a failed RETRY leaves the old assessment in place — the alert must still show ─────────
  {
    const page = await openWith(launch, { feedback: okV3Fail(), grade: 50, autogradeStatus: 'FAILED' })
    check((await page.getByText(OUTAGE_RE).count()) > 0, 'FAILED with a stale old assessment still shows the outage message')
    await page.close()
  }

  // ─── 5c. once a teacher grades the FAILED submission by hand, the alert retires ─────────────────
  {
    const page = await openWith(launch, {
      feedback: null,
      autogradeStatus: 'FAILED',
      gradeObj: { grade: 80, is_autograde: false, is_graded_directly: true },
    })
    check((await page.getByText(OUTAGE_RE).count()) === 0, 'a directly graded submission no longer warns about the outage')
    check((await page.getByText(/80 \/ 100/).count()) > 0, "the teacher's grade is what the student sees")
    await page.close()
  }
})

console.log(failures === 0 ? '\nX-026 verification: all checks passed' : `\nX-026 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
