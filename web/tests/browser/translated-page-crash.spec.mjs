/**
 * The page read through Chrome's translator, while a submission is being graded — EZ-1884.
 *
 * Four bug reports in two days, three students, one exception:
 *
 *     NotFoundError: Failed to execute 'removeChild' on 'Node':
 *     the node to be removed is not a child of this node
 *
 * all of them 1.5–3.6 s after `submission accepted; waiting for auto-assessment`, which is the
 * moment `AutogradeAnimation` swaps its status line for "Done!". The route boundary caught the
 * throw and replaced the page with the crash screen, so the grade the grader had already produced
 * was never shown; two of the three students resubmitted repeatedly trying to get past it.
 *
 * The cause is not in the swap. It is that a page translator **replaces the text nodes React is
 * holding references to** with `<font>` elements of its own, so when React later deletes one of
 * those text nodes out of a parent that survives — which is what `{cond ? 'text' : <El/>}` asks
 * for — the node it hands to `removeChild` is no longer there. See `src/components/SafeText.tsx`.
 *
 * ## What this script does, and why it has to do it that way
 *
 * It translates the page the way Chrome does — every text node under the status line replaced by a
 * `<font>` carrying the same words — at the one moment that matters: after the grading panel is up
 * and before the result lands. The `submissions/latest/await` handler is held open to make that
 * window controllable rather than a race against a 3-second animation phase.
 *
 * Two things are load-bearing about the assertions:
 *
 *  - **It counts the nodes it replaced** and fails if that count is zero. A simulation that
 *    silently found nothing to translate would leave every assertion below it passing on a page
 *    nobody had touched — the exact shape of green-because-it-did-nothing this suite's ratchet
 *    exists for.
 *  - **It watches the console, not only `pageerror`.** React Router catches this throw to render
 *    its boundary, so it never becomes an uncaught page error — the first version of that check
 *    passed against the unfixed code while the crash screen was on screen behind it.
 *
 * Verified as a real detector: with `SafeText` reverted to a bare `{t(…)}` the translated run
 * throws `NotFoundError: Failed to execute 'removeChild' on 'Node'` and renders the crash screen,
 * which is what the three students saw.
 *
 *   cd web && npx playwright test translated-page-crash
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'

/** The three phase strings the status line cycles through, in English — see i18n/en.json. */
const PHASE_TEXTS = [
  'Preparing the environment',
  'Running tests',
  'Analysing results',
]

const exercise = {
  effective_title: 'Kodutöö 1.1: õunad',
  text_html: '<p>Read two integers and print their sum.</p>',
  instructions_html: null,
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  is_open: true,
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
}

/** Nothing submitted yet: the run starts from an empty editor, as the reporters' did. */
const noSubmissions = { submissions: [] }

/** What the refetch returns once the grader has answered. */
const graded = {
  submissions: [
    {
      id: '9001',
      number: 1,
      solution: 'print(int(input()) + int(input()))',
      submission_time: '2026-09-04T12:06:41.000Z',
      autograde_status: 'COMPLETED',
      submission_status: 'COMPLETED',
      grade: { grade: 100, is_autograde: true, is_graded_directly: true },
      auto_assessment: {
        grade: 100,
        feedback: JSON.stringify({
          result_type: 'OK_V3',
          producer: 'tiivad 2.0',
          pre_evaluate_error: null,
          points: 100,
          tests: [
            {
              title: 'Adds two numbers',
              status: 'PASS',
              exception_message: null,
              user_inputs: [],
              created_files: [],
              actual_output: null,
              converted_submission: null,
              checks: [{ title: 'prints 5', status: 'PASS', feedback: '' }],
            },
          ],
        }),
      },
    },
  ],
}

test('translated-page-crash', async ({ launch, check }) => {
  const { page, shot, close } = await launch({
    role: 'student',
    language: 'en',
    shotPrefix: 'translated-',
  })

  // Console errors as well as `pageerror`, and that is not belt-and-braces. React Router *catches*
  // this throw to render its boundary, so it never becomes an uncaught page error — a detector
  // watching only `pageerror` passed happily against the unfixed code while the crash screen was
  // on screen behind it. The console is where the caught one shows up.
  const errors = []
  page.on('pageerror', (err) => errors.push(err.message))
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })

  await fakeApi(page, handlers(), { log: false })
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)

  const submit = page.getByRole('button', { name: /Submit and test/i })
  await waitUntil(() => submit.isVisible())

  // --- the honest language declaration ---------------------------------------------------------
  //
  // Asserted here rather than in a spec of its own because it is the other half of the same bug:
  // `index.html` ships `lang="et"` and nothing used to change it, so an English UI was served as
  // an Estonian document — which is what makes the browser offer the translation that then breaks
  // React. Nothing below depends on this passing; it is the invitation, not the crash.
  const declaredLang = await page.evaluate(() => document.documentElement.lang)
  check(
    'an English UI declares itself English, so the browser stops offering to translate it',
    declaredLang === 'en',
    `<html lang> is "${declaredLang}"`,
  )

  // --- submit, then translate the page while the grader is still thinking ----------------------

  await page.locator('.cm-content').first().fill('print(int(input()) + int(input()))')
  await submit.click()

  // The panel is up once the status line is showing one of its phases.
  const statusLine = page.locator('p').filter({ hasText: new RegExp(PHASE_TEXTS.join('|')) })
  await waitUntil(() => statusLine.isVisible())
  await shot('grading')

  const replaced = await translate(statusLine)

  check(
    'the translator simulation actually replaced text nodes — a zero here would make the rest of ' +
      'this script pass against an untranslated page',
    replaced > 0,
    `${replaced} replaced`,
  )

  // --- the other half of the fix: the line must still step ---------------------------------------
  //
  // Not crashing is not enough: a status line that survives by going stale has stopped being a
  // progress indicator. With the text a bare child of the `<p>`, a phase change is an in-place
  // write to the text node React remembers — detached by the translator — so the line freezes on
  // whichever phase it was translated in. Inside a `SafeText` span it does not, because React
  // assigns `textContent` on an element whose children are a single string, which replaces the
  // translator's `<font>` with the new text. This is the assertion that pins that behaviour down;
  // it is the reason the span needs no `key`, and it is how that was established. Phases are 3s
  // and 4s long, so a step is due well inside the timeout.
  const translatedText = (await statusLine.first().innerText()).trim()
  const steppedText = await waitUntil(
    async () => {
      const now = (await statusLine.first().innerText()).trim()
      return now === translatedText ? null : now
    },
    { timeout: 9000, interval: 250 },
  )

  check(
    'the status line keeps stepping after the page is translated, rather than freezing on the ' +
      'phase it was translated in',
    steppedText != null,
    `"${translatedText}" -> ${steppedText == null ? 'never changed' : `"${steppedText}"`}`,
  )

  // --- translate again, because the step above healed it ----------------------------------------
  //
  // This is not belt-and-braces, it is the difference between testing the fix and testing nothing.
  // The phase step that just passed *is* React assigning `textContent` on the span, which deletes
  // the `<font>` and puts a fresh React-owned text node in its place. So at this point the status
  // line is no longer translated, and releasing the grade now would exercise the `isCompleted`
  // swap — the one that crashed in production — against a perfectly ordinary DOM.
  //
  // It went unnoticed because the script still failed correctly against the reverted code: there
  // the phase write lands on a detached node, the `<font>` survives, and the crash comes anyway.
  // A detector that only works on the broken build is the shape of green this suite exists to
  // refuse, so the count is asserted a second time rather than assumed.
  const retranslated = await translate(statusLine)

  check(
    'the status line is translated again at the moment the grade lands, so the swap under test ' +
      'actually runs against a translated DOM',
    retranslated > 0,
    `${retranslated} replaced`,
  )

  // --- let the grade land on the translated page ------------------------------------------------

  submissions = graded
  releaseAwait()

  // "Done!" is the swap that used to throw. Waiting on the results rather than on it directly,
  // because the completed panel is held for only 700ms before the reveal replaces it.
  const results = page.getByText('Adds two numbers')
  await waitUntil(() => results.isVisible(), { timeout: 15000 })
  await shot('results')

  const crashed = await page.getByText('This page stopped working').isVisible()
  check('the page survives the grade landing on a translated DOM', !crashed, crashed ? 'crash screen is showing' : '')

  check(
    'and the test results are on screen, where the student can read them',
    await results.isVisible(),
  )

  const removeChildErrors = errors.filter((m) => m.includes('removeChild'))
  check(
    'no removeChild throw reached the page',
    removeChildErrors.length === 0,
    // Sorted longest-first, because React logs the same throw twice and one of the two is a bare
    // `%o` format string — printed first, it would be the whole of a failure's explanation.
    removeChildErrors.sort((a, b) => b.length - a.length).join(' | ').slice(0, 300),
  )

  await close()
})

/**
 * Translate everything under `locator` the way Chrome does, and return how many nodes it took.
 *
 * A function rather than an inline `evaluate` because it has to run twice — see the note at the
 * second call. Chrome replaces the text node outright, wrapping the translation in a `<font>` with
 * this exact inline style; reproduced rather than approximated, because what breaks React is
 * specifically that the node it created is no longer a child of the element it put it in. Setting
 * `nodeValue`, or wrapping without detaching, does not do that and would test nothing.
 *
 * The words are left as they are. What matters is the node identity, and keeping the text readable
 * means the locators and the screenshots still say what they mean.
 */
function translate(locator) {
  return locator.first().evaluate((el) => {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    const nodes = []
    for (let n = walker.nextNode(); n; n = walker.nextNode()) nodes.push(n)

    let count = 0
    for (const node of nodes) {
      if (!node.nodeValue.trim()) continue
      const font = document.createElement('font')
      font.setAttribute('style', 'vertical-align: inherit;')
      font.textContent = node.nodeValue
      node.replaceWith(font)
      count += 1
    }
    return count
  })
}

// --- fixture plumbing ---------------------------------------------------------------------------

/** Flipped to `graded` once the held-open await is released. */
let submissions = noSubmissions

/** Resolves the `submissions/latest/await` request, letting grading "finish" on cue. */
let releaseAwait = () => {}

function handlers() {
  submissions = noSubmissions
  const graderAnswered = new Promise((resolve) => {
    releaseAwait = resolve
  })

  return [
    ['/account/checkin', () => ({})],
    [/\/statistics(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/statistics\/common(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/messages(\?|$)/, () => ({ messages: [] })],

    // Above `submissions/all` and above the bare POST, both of whose needles also match this URL.
    // Ordered the other way round, the await would be answered instantly and there would be no
    // window in which to translate anything — the script would pass without ever testing the swap.
    [
      `/student/courses/${COURSE}/exercises/${CE}/submissions/latest/await`,
      async () => {
        await graderAnswered
        return {}
      },
    ],

    [`/student/courses/${COURSE}/exercises/${CE}/submissions/all`, () => submissions],
    [`/student/courses/${COURSE}/exercises/${CE}/draft`, ({ route }) => route.fulfill({ status: 204, body: '' })],
    [`/student/courses/${COURSE}/exercises/${CE}/activities`, () => ({ teacher_activities: [] })],
    [`/student/courses/${COURSE}/exercises/${CE}/inline-comments`, () => ({ inline_comments: [] })],

    // The submit itself. Anchored, so it does not swallow the two endpoints above it.
    [new RegExp(`/student/courses/${COURSE}/exercises/${CE}/submissions(\\?|$)`), () => ({})],

    [new RegExp(`/student/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise],
    [new RegExp(`/student/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises: [] })],
  ]
}
