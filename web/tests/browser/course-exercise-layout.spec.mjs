/**
 * The exercise page as a frame rather than a scroll — EZ-1835.
 *
 * The later homework on the database courses *is* a database backup: a `pg_dump` of 1500 lines or
 * more. The editor had a minimum height and no maximum, so the document set the height of the
 * page, and the submit button underneath it ended up some forty screens down. Every submission
 * cost a scroll to the bottom of a file the student had just uploaded.
 *
 * What is asserted here is geometry, because that is what broke: where the button is, what
 * scrolls, and how the space is divided. A layout bug of this shape is invisible to a test that
 * only asks whether an element exists — the button was always there, just 29,000px away.
 *
 * The fixture's solution is deliberately long enough to reproduce it (1500 lines): at 200 lines
 * every assertion here passes with or without the fix.
 *
 *   cd web && npx playwright test course-exercise-layout
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const VIEWPORT = { width: 1280, height: 860 }

// Long on purpose. With a two-line statement the left pane is shorter than its own maximum, and
// "the pane ends at the bottom of the window" is true however wrongly that maximum was computed.
const statement =
  '<p>Loo indeksid ja trigerid ning esita andmebaasi varukoopia.</p>' +
  Array.from({ length: 40 }, (_, i) => `<p>Samm ${i + 1}: kontrolli, et varukoopia taastub.</p>`).join('')

const exercise = {
  effective_title: 'Kodutöö 6: indeksid ja trigerid',
  text_html: statement,
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'backup.sql',
  solution_file_type: 'TEXT_EDITOR',
}

/** A backup of the size the course actually produces. */
const dump = Array.from(
  { length: 1500 },
  (_, i) => `INSERT INTO public.klient VALUES (${i + 1}, '3800000${i}', 'Nimi ${i}');`,
).join('\n')

const graded = {
  submissions: [
    {
      id: '9001',
      number: 1,
      solution: dump,
      submission_time: '2026-08-30T10:19:00.000Z',
      autograde_status: 'COMPLETED',
      grade: { grade: 60, is_autograde: true, is_graded_directly: true },
      submission_status: 'COMPLETED',
      auto_assessment: {
        grade: 60,
        feedback: JSON.stringify({
          result_type: 'OK_V3',
          producer: 'silmused 1.7.11',
          pre_evaluate_error: null,
          points: 60,
          tests: Array.from({ length: 8 }, (_, i) => ({
            title: `Kontroll ${i + 1}`,
            status: i === 0 ? 'FAIL' : 'PASS',
            exception_message: null,
            user_inputs: [],
            created_files: [],
            actual_output: null,
            converted_submission: null,
            checks: [{ title: `Alamkontroll ${i + 1}`, status: i === 0 ? 'FAIL' : 'PASS', feedback: '' }],
          })),
        }),
      },
    },
  ],
}

/** The same exercise before anyone has submitted: nothing at all below the editor. */
const untouched = { submissions: [] }

let submissions = graded

function handlers() {
  return [
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
  ]
}

test('course-exercise-layout', async ({ launch, check }) => {
  // --- a long submission on a desktop viewport ----------------------------------------------
  submissions = graded
  const { page, shot, close } = await launch({
    role: 'student',
    viewport: VIEWPORT,
    shotPrefix: 'ce-layout-',
  })
  await fakeApi(page, handlers(), { log: false })
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)

  const submit = page.getByRole('button', { name: /Submit and test/i })
  await waitUntil(() => submit.isVisible())
  // The editor seeds asynchronously; wait for the document to actually be in it.
  await waitUntil(async () =>
    (await page.locator('.cm-scroller').evaluate((el) => el.scrollHeight)) > 2000,
  )

  const windowScrolls = await page.evaluate(
    () => document.documentElement.scrollHeight > window.innerHeight + 2,
  )
  check('the window does not scroll at all, whatever the file is', !windowScrolls)

  const box = await submit.boundingBox()
  check(
    'the submit button is inside the viewport rather than screens below it',
    box !== null && box.y >= 0 && box.y + box.height <= VIEWPORT.height,
    box ? `y=${Math.round(box.y)} h=${Math.round(box.height)} of ${VIEWPORT.height}` : 'no box',
  )

  const editorScrolls = await page.locator('.cm-scroller').evaluate(
    (el) => el.scrollHeight > el.clientHeight + 50,
  )
  check('the 1500-line dump scrolls inside the editor instead of stretching the page', editorScrolls)

  // The statement pane used to be `calc(100vh - 48px)` tall while starting well below 48px, so it
  // ran off the bottom of the window by the height of the title and the chips.
  // Found by walking up from the statement text itself rather than by guessing at a DOM shape:
  // the pane is a plain Box with no class of its own, and a structural selector would pass by
  // finding some other scroller.
  const statementBottom = await page.evaluate(() => {
    let el = [...document.querySelectorAll('p')].find((p) =>
      p.textContent.includes('Loo indeksid ja trigerid'),
    )
    while (el && getComputedStyle(el).overflowY !== 'auto') el = el.parentElement
    return el ? Math.round(el.getBoundingClientRect().bottom) : null
  })
  check(
    'and the statement pane ends at the bottom of the window, not past it',
    statementBottom !== null && statementBottom <= VIEWPORT.height + 1,
    `bottom at ${statementBottom} of ${VIEWPORT.height}`,
  )

  await shot('01-framed')

  // --- the divider ---------------------------------------------------------------------------
  const grip = page.getByRole('separator', { name: /Resize the editor/i })
  check('a divider appears once there is something below it', await grip.isVisible())

  // Grab the handle off-centre, move, and come back to exactly where the grab started. A splitter
  // that carries the grab offset lands back where it was; one that centres itself on the cursor —
  // as this one did — keeps the few pixels between the grab point and the middle of the handle,
  // which is felt as a jump the moment you take hold of it.
  const restBox = await grip.boundingBox()
  const grabY = restBox.y + 1
  await page.mouse.move(restBox.x + 80, grabY)
  await page.mouse.down()
  await page.mouse.move(restBox.x + 80, grabY + 40, { steps: 4 })
  await page.mouse.move(restBox.x + 80, grabY, { steps: 4 })
  await page.mouse.up()
  const returnedBox = await grip.boundingBox()
  check(
    'grabbing it off-centre and returning leaves the split where it was',
    Math.abs(returnedBox.y - restBox.y) <= 1.5,
    `moved ${(returnedBox.y - restBox.y).toFixed(1)}px`,
  )

  const gripBox = await grip.boundingBox()
  await page.mouse.move(gripBox.x + gripBox.width / 2, gripBox.y + gripBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(gripBox.x + gripBox.width / 2, gripBox.y - 150, { steps: 8 })
  await page.mouse.up()

  const stored = await page.evaluate(() => localStorage.getItem('splitPane.studentExercise.topPct'))
  check(
    'dragging it moves the split and the position is remembered',
    stored !== null && Math.abs(Number(stored) - 55) > 2,
    `stored ${stored}`,
  )

  const submitStillVisible = await submit.boundingBox()
  check(
    'and the submit button stays on screen after the drag',
    submitStillVisible.y + submitStillVisible.height <= VIEWPORT.height,
  )
  await shot('02-dragged')
  await close()

  // --- nothing submitted yet: the lower half must not reserve space it has no use for ---------
  submissions = untouched
  const fresh = await launch({ role: 'student', viewport: VIEWPORT, shotPrefix: 'ce-layout-fresh-' })
  await fakeApi(fresh.page, handlers(), { log: false })
  await fresh.page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await waitUntil(() => fresh.page.getByRole('button', { name: /Submit and test/i }).isVisible())

  const share = await fresh.page.locator('.cm-editor').evaluate(
    (el) => el.getBoundingClientRect().height / window.innerHeight,
  )
  check(
    'with no results, no feedback and no history, the editor gets the pane',
    share > 0.45,
    `editor is ${(share * 100).toFixed(0)}% of the window height`,
  )
  check(
    'and there is no divider, because there is nothing below to divide from',
    (await fresh.page.getByRole('separator', { name: /Resize the editor/i }).count()) === 0,
  )
  await fresh.shot('03-first-visit')
  await fresh.close()

  // --- a phone keeps scrolling, but the editor is capped so the button is reachable -----------
  submissions = graded
  const phone = await launch({
    role: 'student',
    viewport: { width: 420, height: 780 },
    shotPrefix: 'ce-layout-phone-',
  })
  await fakeApi(phone.page, handlers(), { log: false })
  await phone.page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await waitUntil(() => phone.page.getByRole('button', { name: /Submit and test/i }).isVisible())
  await waitUntil(async () =>
    (await phone.page.locator('.cm-scroller').evaluate((el) => el.scrollHeight)) > 2000,
  )

  const cappedHeight = await phone.page.locator('.cm-editor').evaluate(
    (el) => el.getBoundingClientRect().height / window.innerHeight,
  )
  check(
    'on a phone the editor is capped rather than 1500 lines tall',
    cappedHeight <= 0.7,
    `editor is ${(cappedHeight * 100).toFixed(0)}% of the window height`,
  )
  await phone.shot('04-phone')
  await phone.close()
})
