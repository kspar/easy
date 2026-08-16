/**
 * The grade table's cells link to the submission behind them (EZ-1706), as the WUI's did.
 *
 * First browser coverage for this page, and it is the one `doc/testing.md` calls out as highest
 * consequence: grades are the output of the whole application, and until now nothing checked the
 * table that shows them.
 *
 * What is worth pinning is that each cell goes to *that* student and *that* exercise. A table of
 * links that all work but point one column over is the failure mode here, and it is invisible in a
 * screenshot — the numbers look right either way.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '1'
const CE1 = '10'
const CE2 = '20'

// id and name only: that is all core's GroupResp carries. An invented `student_count` here is
// exactly the fixture drift the contract check exists to catch, and it caught it.
const GROUPS = [
  { id: 'g1', name: 'Rühm A' },
  { id: 'g2', name: 'Rühm B' },
]

// Two students, two exercises, and deliberately mismatched grades so a cell that links to the wrong
// column or the wrong row is distinguishable rather than plausible.
//
// The names are chosen to sort *differently* by family name than by given name — Maasikas before
// Tamm, but Jaan before Mari — so an assertion about order cannot pass by accident whichever field
// the comparator happens to read.
const students = [
  { student_id: 's1', given_name: 'Mari', family_name: 'Maasikas', groups: [] },
  { student_id: 's2', given_name: 'Jaan', family_name: 'Tamm', groups: [] },
]

const sub = (n, grade, isAutograde) => ({
  id: `sub-${n}`,
  submission_number: n,
  time: '2026-08-01T10:00:00.000Z',
  grade: grade === null ? null : { grade, is_autograde: isAutograde, is_graded_directly: true },
  seen: true,
})

const exercise = (ceId, title, idx, rows) => ({
  course_exercise_id: ceId,
  exercise_id: `ex-${ceId}`,
  library_title: title,
  title_alias: null,
  effective_title: title,
  grade_threshold: 100,
  student_visible: true,
  student_visible_from: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'AUTO',
  ordering_idx: idx,
  unstarted_count: 0,
  ungraded_count: 0,
  started_count: 0,
  completed_count: 0,
  latest_submissions: rows,
})

const exercises = [
  exercise(CE1, 'Sum of two numbers', 0, [
    { ...students[0], status: 'COMPLETED', submission: sub(2, 100, true) },
    // Teacher-graded, so the cell shows the face icon.
    { ...students[1], status: 'COMPLETED', submission: sub(1, 60, false) },
  ]),
  exercise(CE2, 'Fibonacci', 1, [
    { ...students[0], status: 'UNGRADED', submission: sub(1, null, null) },
    // Never submitted — the cell shows "-" and, per the WUI, still links.
    { ...students[1], status: 'UNSTARTED', submission: null },
  ]),
]

/**
 * A third student who exists only on the *second* exercise, which is EZ-1767's first direction.
 *
 * Not folded into `exercises` above, because the link assertions there are written for a two-row
 * table. This is served from a separate route on a second visit.
 */
const withLateJoiner = [
  exercises[0],
  {
    ...exercises[1],
    latest_submissions: [
      ...exercises[1].latest_submissions,
      {
        student_id: 's3',
        given_name: 'Kati',
        family_name: 'Kask',
        groups: [],
        status: 'COMPLETED',
        submission: sub(1, 75, true),
      },
    ],
  },
]

test('grade-table', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'grades-' })

  // Mutable so the group filter can be observed changing what the server is asked for, the way it
  // would in production — a filter that changes the rows but not the request is a filter applied
  // in the wrong place.
  let exercisesToServe = exercises
  const exerciseRequests = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/groups`, () => ({ groups: GROUPS })],
    // Anchored, not a substring. `/teacher/courses/1/exercises/10?student=s2` — which the last
    // section navigates to — otherwise matches this handler and gets answered with a *list* where
    // core sends a single exercise. See the query-string gotcha in doc/web/browser-testing.md.
    [new RegExp(`/teacher/courses/${COURSE}/exercises(\\?|$)`), ({ url }) => {
      exerciseRequests.push(new URL(url).searchParams.get('group'))
      return { exercises: exercisesToServe }
    }],
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    // Anchored for the same reason, and it is the subtler one: `/teacher/courses` is a prefix of
    // every teacher URL on the course, so as a substring it quietly answered the *single exercise*
    // request with a course list. The page did not care — nothing on screen depends on it — but the
    // contract check saw a response missing all 30 of that endpoint's fields and said so.
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/grades`)
  await page.getByText('Mari Maasikas').first().waitFor()

  // The cells are named for who and what they belong to, which is what makes them findable here and
  // usable by anyone reading the table through a screen reader.
  const cell = (student, exerciseTitle, grade) =>
    page.getByRole('link', { name: `${student} — ${exerciseTitle} — ${grade}` })

  check(
    'a graded cell links to that student and that exercise',
    await waitUntil(async () =>
      (await cell('Mari Maasikas', 'Sum of two numbers', '100').getAttribute('href')) ===
      `/courses/${COURSE}/exercises/${CE1}?student=s1`),
  )
  check(
    'a different student in the same column links to the other student',
    await waitUntil(async () =>
      (await cell('Jaan Tamm', 'Sum of two numbers', '60').getAttribute('href')) ===
      `/courses/${COURSE}/exercises/${CE1}?student=s2`),
  )
  check(
    'and the second column links to the second exercise',
    await waitUntil(async () =>
      (await cell('Mari Maasikas', 'Fibonacci', 'no grade').getAttribute('href')) ===
      `/courses/${COURSE}/exercises/${CE2}?student=s1`),
  )
  check(
    'an unstarted cell is a link too, rather than a dead end',
    await waitUntil(async () =>
      (await cell('Jaan Tamm', 'Fibonacci', 'no grade').getAttribute('href')) ===
      `/courses/${COURSE}/exercises/${CE2}?student=s2`),
  )

  // Cell contents, unchanged from what the page rendered before the links went in.
  check('a teacher-graded cell is marked as such', (await page.locator('svg[data-testid="FaceOutlinedIcon"]').count()) === 1)
  await shot('01-table')

  // --- sorting ------------------------------------------------------------------------------------------
  // Read the name column top to bottom. The rendered order is the only thing that matters here; the
  // comparator's own properties (total order, tie-breaking, NaN) are pinned in
  // tests/unit/grade-table.test.mjs, which can do it exhaustively and in milliseconds.
  // `:not(:first-child)` skips the Σ summary row, which is inside tbody rather than the header and
  // would otherwise be read as a student called "Σ (2)".
  const nameColumn = async () =>
    (await page.locator('tbody tr:not(:first-child) td:first-child').allInnerTexts())
      .map((s) => s.trim())

  check(
    'rows start sorted by family name',
    JSON.stringify(await nameColumn()) === JSON.stringify(['Mari Maasikas', 'Jaan Tamm']),
    JSON.stringify(await nameColumn()),
  )

  const settle = () => page.waitForTimeout(50)
  const sortByName = async () => {
    await page.getByRole('button', { name: 'Name', exact: true }).click()
    await settle()
  }

  /**
   * Exercise headers are sorted by their arrow, not their text.
   *
   * The title inside the header is itself a link to the exercise, and it stops propagation — so
   * clicking the words navigates away instead of sorting. That is deliberate (a teacher wants to
   * reach the exercise from its column) and it is exactly the kind of thing that makes a test look
   * broken: the first version of this clicked the title, left the page, and reported an empty table.
   */
  const sortByExercise = async (title) => {
    await page
      .locator('thead th')
      .filter({ hasText: title })
      .locator('[class*=MuiTableSortLabel-icon]')
      .click()
    await settle()
  }

  await sortByName()
  check(
    'clicking Name reverses it',
    JSON.stringify(await nameColumn()) === JSON.stringify(['Jaan Tamm', 'Mari Maasikas']),
    JSON.stringify(await nameColumn()),
  )
  await sortByName()
  check('and clicking again restores it', (await nameColumn())[0] === 'Mari Maasikas')

  // Sorting on an exercise column. Descending first, which is what this column defaults to — a
  // teacher opening a grade column wants the top of the class, not the bottom.
  await sortByExercise('Sum of two numbers')
  check(
    'an exercise column sorts by that grade, highest first',
    JSON.stringify(await nameColumn()) === JSON.stringify(['Mari Maasikas', 'Jaan Tamm']),
    JSON.stringify(await nameColumn()),
  )
  check('and the sorted column is the one that got marked', (await page.locator('td.sorted-col').count()) > 0)
  await sortByExercise('Sum of two numbers')
  check('and reverses to lowest first', (await nameColumn())[0] === 'Jaan Tamm')

  // The column with a null grade in it. Ungraded has to go to one end and stay a row — the failure
  // to look for is a student vanishing because the comparator returned NaN for them.
  await sortByExercise('Fibonacci')
  check(
    'sorting on a column containing no grades keeps every row',
    (await nameColumn()).length === 2,
    JSON.stringify(await nameColumn()),
  )

  // Σ, the completion count.
  await page.getByRole('button', { name: /^Σ/ }).click()
  await settle()
  check('the Σ column sorts by how much each student has finished', (await nameColumn()).length === 2)

  await sortByName()

  // --- the submission-count toggle -----------------------------------------------------------------------
  const bodyText = async () => (await page.locator('tbody').innerText()).replace(/\s+/g, ' ')
  // `#` rather than `(2)`: the Σ summary row is literally "Σ (2)", so a check for "(2)" passes
  // before the toggle is touched and would have been green whatever the button did.
  check('submission counts are hidden by default', !(await bodyText()).includes('#'), await bodyText())
  await page.getByRole('button', { name: /submission count/i }).click()
  check(
    'toggling shows the submission number alongside the grade',
    await waitUntil(async () => (await bodyText()).includes('#2')),
    await bodyText(),
  )
  await shot('02-submission-counts')
  await page.getByRole('button', { name: /submission count/i }).click()

  // --- the group filter ---------------------------------------------------------------------------------
  // Two halves, and both matter: the request has to carry the group, and the table has to redraw
  // from the answer. A filter that only does the first is a filter that silently shows everyone.
  const before = exerciseRequests.length
  exercisesToServe = [
    { ...exercises[0], latest_submissions: [exercises[0].latest_submissions[0]] },
    { ...exercises[1], latest_submissions: [exercises[1].latest_submissions[0]] },
  ]
  const groupChip = () =>
    page.locator('[class*=MuiChip-root]').filter({ hasText: /Groups|Rühm/ })
  await groupChip().click()
  await page.getByRole('menuitem', { name: GROUPS[0].name }).click()

  check(
    'choosing a group refetches with that group in the query',
    await waitUntil(() => exerciseRequests.length > before && exerciseRequests.at(-1) === 'g1'),
    JSON.stringify(exerciseRequests),
  )
  check(
    'and the table redraws from the filtered answer',
    await waitUntil(async () => (await nameColumn()).length === 1),
    JSON.stringify(await nameColumn()),
  )
  await shot('03-group-filtered')

  // Back to everyone, so the rest of the spec sees the full table.
  exercisesToServe = exercises
  await groupChip().click()
  await page.getByRole('menuitem', { name: 'All groups' }).click()
  // Asserted on the table rather than on a request, because clearing the filter deliberately makes
  // **no** request: react-query still holds the unfiltered list and serves it. Asserting a refetch
  // here would be asserting that the cache does not work.
  check(
    'clearing the filter brings everyone back',
    await waitUntil(async () => (await nameColumn()).length === 2),
    JSON.stringify(await nameColumn()),
  )
  check(
    'and the chip goes back to its default label',
    await waitUntil(async () => (await groupChip().innerText()).trim() === 'Groups'),
    await groupChip().innerText(),
  )

  // --- CSV export ---------------------------------------------------------------------------------------
  // The file a teacher actually pastes into a gradebook, parsed rather than merely triggered.
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: /export/i }).click()
  const file = await download
  const csv = (await (await import('node:fs/promises')).readFile(await file.path(), 'utf8'))

  check('the export is offered as a .csv named for the course', /^grades-1-\d+\.csv$/.test(file.suggestedFilename()), file.suggestedFilename())
  const lines = csv.split('\n')
  check('it has a header row and one row per student', lines.length === 3, JSON.stringify(lines))
  check(
    'the header names every exercise column',
    lines[0] === '"Name";"Sum of two numbers";"Fibonacci"',
    lines[0],
  )
  check(
    'a graded cell exports its number and an ungraded one exports empty',
    lines.slice(1).includes('"Mari Maasikas";"100";""'),
    JSON.stringify(lines.slice(1)),
  )
  check(
    'and every field is quoted, so a name with a separator in it could not split a row',
    lines.every((l) => l.split(';').every((f) => f.startsWith('"') && f.endsWith('"'))),
    JSON.stringify(lines),
  )

  // --- following one -----------------------------------------------------------------------------------
  await cell('Jaan Tamm', 'Sum of two numbers', '60').click()
  check(
    'clicking a cell opens that student in that exercise',
    await waitUntil(() =>
      page.url().endsWith(`/courses/${COURSE}/exercises/${CE1}?student=s2`)),
  )

  // --- EZ-1767: a roster that differs between exercises --------------------------------------------------
  // Kati exists only on the second exercise. Before the fix the table's roster was the *first*
  // exercise's submission list, so she was silently absent — and a student present in the first but
  // missing from a later one took the whole page down with a TypeError. Both directions are pinned
  // exhaustively in the unit tests; this is the end-to-end half, because "the page renders at all"
  // is not something a pure function can answer.
  exercisesToServe = withLateJoiner
  await page.goto(`${BASE_URL}/courses/${COURSE}/grades`)
  await page.getByText('Mari Maasikas').first().waitFor()

  check(
    'a student who appears only in a later exercise is still in the table',
    await waitUntil(async () => (await nameColumn()).includes('Kati Kask')),
    JSON.stringify(await nameColumn()),
  )
  check(
    'and her empty cell in the first exercise renders rather than crashing the page',
    await waitUntil(async () =>
      (await cell('Kati Kask', 'Sum of two numbers', 'no grade').count()) === 1),
  )
  check(
    'while her real grade in the second exercise is shown',
    await waitUntil(async () =>
      (await cell('Kati Kask', 'Fibonacci', '75').count()) === 1),
  )
  check('the other students are unaffected', (await nameColumn()).length === 3, JSON.stringify(await nameColumn()))
  await shot('04-uneven-roster')

  await close()
})
