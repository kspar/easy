/**
 * The similarity analysis page (EZ-1699), ported from the WUI.
 *
 * The things worth pinning are the ones a screenshot cannot show: that the request carries the
 * *library* exercise id and only the submission ids the group filter left, that the chosen exercise
 * survives in the URL, and that each side of a pair links to the right student's grading view — that
 * last one is only possible because the page joins the similarity response (names, no ids) to the
 * summaries it already fetched.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const COURSE = '1'
const CE = '10'          // course exercise id
const EX = '77'          // library exercise id — what similarity is keyed on
const GROUP = 'g1'

const requests = []

const rowsAll = [
  { student_id: 's1', given_name: 'Mari', family_name: 'Maasikas', status: 'COMPLETED', groups: [{ id: GROUP, name: 'Rühm A' }], submission: { id: '101', submission_number: 1, time: '2026-08-01T10:00:00.000Z', grade: null, seen: true } },
  { student_id: 's2', given_name: 'Jaan', family_name: 'Tamm', status: 'COMPLETED', groups: [{ id: GROUP, name: 'Rühm A' }], submission: { id: '102', submission_number: 1, time: '2026-08-01T11:00:00.000Z', grade: null, seen: true } },
  // No submission, so it must not end up in the request — the page compares solutions, and a student
  // who has not submitted has none.
  { student_id: 's3', given_name: 'Kati', family_name: 'Kask', status: 'UNSTARTED', groups: [], submission: null },
  { student_id: 's4', given_name: 'Peeter', family_name: 'Puu', status: 'COMPLETED', groups: [], submission: { id: '104', submission_number: 1, time: '2026-08-01T12:00:00.000Z', grade: null, seen: true } },
]

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'similarity-' })
const check = checker()

await fakeApi(page, [
  ['/account/checkin', () => ({})],

  ['/similarity', ({ method, url, body }) => {
    if (method === 'POST') requests.push({ url, body })
    return {
      submissions: [
        { id: '101', created_at: '2026-08-01T10:00:00.000Z', solution: '# loeb kaks arvu ja liidab\na = int(input())\nb = int(input())\nsumma = a + b\nprint(summa)\n', given_name: 'Mari', family_name: 'Maasikas', course_title: 'Programmeerimise alused' },
        { id: '102', created_at: '2026-08-01T11:00:00.000Z', solution: '# loeb kaks arvu ja liidab\nx = int(input())\ny = int(input())\ntulemus = x + y\nprint(tulemus)\n', given_name: 'Jaan', family_name: 'Tamm', course_title: 'Programmeerimise alused' },
      ],
      scores: [
        { sub_1: '101', sub_2: '102', score_a: 91, score_b: 78 },
        { sub_1: '101', sub_2: '104', score_a: 20, score_b: 15 },
      ],
    }
  }],

  [`/teacher/courses/${COURSE}/exercises/${CE}/submissions/latest/students`, ({ url }) => ({
    // The group filter is applied by core in production; here it is applied by the stub, so that
    // what the page sends can be checked against what it was given.
    latest_submissions: url.includes(`group=${GROUP}`)
      ? rowsAll.filter((r) => r.groups.some((g) => g.id === GROUP))
      : rowsAll,
  })],

  [`/courses/${COURSE}/groups`, () => ({ groups: [{ id: GROUP, name: 'Rühm A', student_count: 2 }] })],

  [`/teacher/courses/${COURSE}/exercises`, () => ({
    exercises: [{
      course_exercise_id: CE, exercise_id: EX, library_title: 'Sum of two numbers',
      title_alias: null, effective_title: 'Sum of two numbers', grade_threshold: 100,
      student_visible: true, student_visible_from: null, soft_deadline: null, hard_deadline: null,
      grader_type: 'AUTO', ordering_idx: 0, unstarted_count: 1, ungraded_count: 0,
      started_count: 0, completed_count: 3, latest_submissions: [],
    }],
  })],

  // Layout-level queries. Not what this script tests, but an unexplained console error is where a
  // real one goes to hide.
  [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
  ['/teacher/courses', () => ({ courses: [] })],
], { log: false })

await page.goto(`${BASE_URL}/courses/${COURSE}/similarity`)
await page.getByRole('heading').first().waitFor()

// Nothing to compare until an exercise is picked.
const findButton = page.getByRole('button', { name: /Find similarities/i })
check('the button is disabled before an exercise is chosen', await findButton.isDisabled())

await page.getByRole('combobox', { name: 'Exercise' }).click()
await page.getByRole('option', { name: 'Sum of two numbers' }).click()

check(
  'choosing an exercise puts it in the URL, so the page can be linked and reloaded',
  await waitUntil(() => page.url().includes(`exercise=${EX}`)),
)
check(
  'the scope is shown before running: 3 submissions of 4 students, 3 pairs',
  await waitUntil(() => page.getByText('3 submissions, 3 pairs to compare').isVisible()),
)

await shot('01-ready')
await findButton.click()

check('the comparison is requested once', await waitUntil(() => requests.length === 1))
check(
  'against the library exercise id, not the course exercise id',
  requests[0]?.url.includes(`/exercises/${EX}/similarity`),
)
check(
  'sending only the students who actually submitted',
  JSON.stringify(requests[0]?.body?.submissions) === JSON.stringify([{ id: '101' }, { id: '102' }, { id: '104' }]),
)
check('and scoping to this course', JSON.stringify(requests[0]?.body?.courses) === JSON.stringify([{ id: COURSE }]))

check(
  'the pair is listed with both scores labelled',
  await waitUntil(async () =>
    (await page.getByText('Dice 91%').count()) > 0 && (await page.getByText('Levenshtein 78%').count()) > 0),
)

// --- the diff, and the link to the student -----------------------------------------------------------
// Visibility, not presence. MUI keeps AccordionDetails mounted inside a collapsed Collapse, so the
// merge view is in the DOM before anything is clicked — counting elements here passed without a click
// at all, which is a check that cannot fail. Asserting it starts hidden is what makes the next line
// mean something.
const mergeView = page.locator('.cm-mergeView')
check('the diff is not rendered visibly until the pair is opened', !(await mergeView.isVisible()))

await page.getByText('Mari Maasikas — Jaan Tamm').click()
check(
  'expanding a pair shows both solutions side by side',
  await waitUntil(async () =>
    (await mergeView.isVisible()) && (await page.locator('.cm-mergeView .cm-editor').count()) === 2),
)
// Height, not just visibility. `isVisible()` turns true the instant MUI's Collapse starts animating,
// while the body is still ~0px tall — which is why the first screenshot of this looked collapsed. It
// also catches the CodeMirror failure where an editor built inside a hidden container measures itself
// as empty and stays that way.
check(
  'and the diff has actual height, rather than rendering into a 0px box',
  await waitUntil(async () => ((await mergeView.boundingBox())?.height ?? 0) > 60),
)
const link = page.getByRole('link', { name: /Mari Maasikas/ })
check(
  'each side links to that student in the grading view',
  await waitUntil(async () =>
    (await link.getAttribute('href')) === `/courses/${COURSE}/exercises/${CE}?student=s1`),
)
// The screenshot is for humans, and it kept catching MUI's expand animation mid-flight — an image
// showing a collapsed row under a passing "it expands" check is worse than no image. Settle, then
// re-assert, so the picture and the claim cannot disagree.
await page.waitForTimeout(500)
check(
  'the pair is still open once the animation settles',
  ((await mergeView.boundingBox())?.height ?? 0) > 60,
)
await shot('02-pair-expanded')

// --- the score filter ---------------------------------------------------------------------------------
// The low-scoring pair references a submission the response does not describe, which is what the
// real endpoint does when a pair falls outside the returned set — it must not crash the page.
check(
  'a pair whose submissions are missing from the response is skipped rather than crashing',
  (await page.getByText('Dice 20%').count()) === 0,
)

// --- the group filter narrows what gets sent ----------------------------------------------------------
requests.length = 0
await page.getByRole('combobox', { name: 'Group' }).click()
await page.getByRole('option', { name: 'Rühm A' }).click()
await waitUntil(() => page.getByText('2 submissions, 1 pairs to compare').isVisible())
await page.getByRole('button', { name: /Find similarities/i }).click()
check(
  'picking a group sends only that group\'s submissions',
  await waitUntil(() =>
    requests.length === 1 &&
    JSON.stringify(requests[0].body.submissions) === JSON.stringify([{ id: '101' }, { id: '102' }])),
)

await browser.close()
process.exit(check.summary() ? 0 : 1)
