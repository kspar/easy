/**
 * Unit T7 — what the student sees when a TSL test fails.
 *
 * A test set is only as good as the message it produces, and this is the moment that matters most to
 * the application's largest group of users. Four shapes, in increasing order of how badly they are
 * likely to be handled:
 *
 *  1. an ordinary FAIL with OK_V3 feedback — the designed path;
 *  2. a student exception (their code crashed) inside OK_V3;
 *  3. `pre_evaluate_error` set, which is the grader failing before any test ran;
 *  4. feedback that is **not OK_V3 at all** — raw container output. `parseOkV3` returns null for this
 *     and `tests` becomes `[]`; the review programme found raw container output reaching
 *     student-visible feedback (C2/F-019), so the question is whether the student gets a wall of
 *     Docker noise, or nothing at all with a grade attached. Both are bad in different ways and the
 *     difference decides the fix.
 *
 *   HARNESS_PORT=5299 node tests/audit/t7-student-sees-failure.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, CE_ID, studentCourse, studentExercise, exerciseDetails, submission, baseHandlers } from './fixtures.mjs'

const okV3Fail = () =>
  JSON.stringify({
    result_type: 'OK_V3',
    producer: 'tiivad',
    points: 50,
    pre_evaluate_error: null,
    tests: [
      {
        title: 'Programm küsib kaks arvu',
        status: 'PASS',
        exception_message: null,
        user_inputs: ['2', '3'],
        created_files: [],
        actual_output: '5\n',
        converted_submission: null,
        checks: [{ title: 'Väljund sisaldab õiget summat', status: 'PASS', feedback: 'Väljund oli ootuspärane.' }],
      },
      {
        title: 'Programm töötab ka negatiivsete arvudega',
        status: 'FAIL',
        exception_message: null,
        user_inputs: ['-4', '2'],
        created_files: [],
        actual_output: '6\n',
        converted_submission: null,
        checks: [
          {
            title: 'Väljund sisaldab õiget summat',
            status: 'FAIL',
            feedback: 'Ootasin väljundis stringi "-2", aga seda ei leidnud.',
          },
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
          'Traceback (most recent call last):\n  File "lahendus.py", line 3, in <module>\n    print(a + b)\nTypeError: unsupported operand type(s) for +: \'int\' and \'str\'',
        user_inputs: ['2', 'kolm'],
        created_files: [],
        actual_output: null,
        converted_submission: null,
        checks: [],
      },
    ],
  })

const okV3PreError = () =>
  JSON.stringify({
    result_type: 'OK_V3',
    producer: 'tiivad',
    points: 0,
    pre_evaluate_error: 'SyntaxError: invalid syntax (generated_0.py, line 3)',
    tests: [],
  })

/** Not OK_V3 at all — the shape review F-019 worried about. */
const rawContainerOutput = () =>
  [
    'Traceback (most recent call last):',
    '  File "/usr/local/lib/python3.11/site-packages/tiivad/__init__.py", line 12, in <module>',
    "    raise RuntimeError('container image tiivad:tsl-compose is missing scripts')",
    'RuntimeError: container image tiivad:tsl-compose is missing scripts',
    'docker: Error response from daemon: OCI runtime create failed',
    'killed',
  ].join('\n')

const CASES = [
  { name: 'ordinary FAIL (OK_V3)', feedback: okV3Fail(), grade: 50 },
  { name: 'student exception (OK_V3)', feedback: okV3Exception(), grade: 0 },
  { name: 'pre_evaluate_error (OK_V3, no tests)', feedback: okV3PreError(), grade: 0 },
  { name: 'NOT OK_V3 — raw container output', feedback: rawContainerOutput(), grade: 0 },
]

const report = []

for (const c of CASES) {
  await withBrowser(async ({ launch }) => {
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
                autograde_status: 'COMPLETED',
                grade: { grade: c.grade, is_autograde: true, is_graded_directly: false },
                submission_status: c.grade === 100 ? 'COMPLETED' : 'UNGRADED',
                auto_assessment: { grade: c.grade, feedback: c.feedback },
              }),
            ],
          }),
        ],
        [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
        [new RegExp(`/exercises/${CE_ID}/inline-comments`), () => ({ inline_comments: [] })],
        [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
      ],
      { log: false, contract: false },
    )

    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('main').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(3000) // past any reveal

    const seen = await page.evaluate(() => {
      const main = document.querySelector('main')
      const txt = (main?.innerText ?? '').replace(/\s+/g, ' ')
      return {
        mainLength: txt.length,
        text: txt.slice(0, 700),
        // Does anything from a container/stack trace reach the page?
        showsTraceback: /Traceback|OCI runtime|docker:|site-packages/.test(txt),
        showsKilled: /\bkilled\b/i.test(txt),
        // Is there any explanatory framing at all?
        alerts: [...document.querySelectorAll('.MuiAlert-root')].map((a) => a.textContent?.trim().slice(0, 140)),
        accordions: document.querySelectorAll('.MuiAccordion-root').length,
        preBlocks: document.querySelectorAll('main pre').length,
      }
    })

    report.push({ case: c.name, ...seen })
    console.log(`\n[${c.name}]`)
    console.log(`   accordions: ${seen.accordions} · pre blocks: ${seen.preBlocks} · alerts: ${JSON.stringify(seen.alerts)}`)
    console.log(`   leaks a traceback/container noise: ${seen.showsTraceback}${seen.showsKilled ? ' (and the word "killed")' : ''}`)
    console.log(`   main text (${seen.mainLength} chars): ${JSON.stringify(seen.text.slice(0, 320))}`)
    await shoot(page, `t7-${c.name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`)
    await page.close()
  })
}

const path = join(REPORTS, 't7-student-sees-failure.json')
writeFileSync(path, JSON.stringify(report, null, 2))
console.log(`\nreport written to ${path}`)
