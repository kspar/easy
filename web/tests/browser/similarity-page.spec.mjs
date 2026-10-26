/**
 * The similarity analysis page (EZ-1699), ported from the WUI.
 *
 * The things worth pinning are the ones a screenshot cannot show: that the request carries the
 * *library* exercise id and only the submission ids the group filter left, that the chosen exercise
 * survives in the URL, and that each side of a pair links to the right student's grading view — that
 * last one is only possible because the page joins the similarity response (names, no ids) to the
 * summaries it already fetched.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '1'
const CE = '10'          // course exercise id
const EX = '77'          // library exercise id — what similarity is keyed on
const GROUP = 'g1'

const requests = []
const settingsWrites = []
// Which provider the stubbed check answers as. JPlag first, as the rest of the spec reads; edit distance
// at the end, for what changes when a provider says how alike two texts are but not where (EZ-1667).
let provider = 'jplag'

const rowsAll = [
  { student_id: 's1', given_name: 'Mari', family_name: 'Maasikas', status: 'COMPLETED', groups: [{ id: GROUP, name: 'Rühm A' }], submission: { id: '101', submission_number: 1, time: '2026-08-01T10:00:00.000Z', grade: null, seen: true, flagged: false } },
  { student_id: 's2', given_name: 'Jaan', family_name: 'Tamm', status: 'COMPLETED', groups: [{ id: GROUP, name: 'Rühm A' }], submission: { id: '102', submission_number: 1, time: '2026-08-01T11:00:00.000Z', grade: null, seen: true, flagged: false } },
  // No submission, so it must not end up in the request — the page compares solutions, and a student
  // who has not submitted has none.
  { student_id: 's3', given_name: 'Kati', family_name: 'Kask', status: 'UNSTARTED', groups: [], submission: null },
  { student_id: 's4', given_name: 'Peeter', family_name: 'Puu', status: 'COMPLETED', groups: [], submission: { id: '104', submission_number: 1, time: '2026-08-01T12:00:00.000Z', grade: null, seen: true, flagged: false } },
]

test('similarity-page', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'similarity-' })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],

    // Before '/similarity', which it also contains - the first needle that matches answers. Priced from
    // what was sent, as core does, so a group change is a new answer: n submissions, n(n-1)/2 pairs.
    ['/similarity/estimate', ({ body }) => {
      const n = body?.students?.length ?? 0
      return {
        submissions: n, pairs: (n * (n - 1)) / 2, provider, language: 'python', mode: 'full',
        provider_fallback: null, estimated_sec: 2, budget_sec: 120, within_budget: true, optimised: false, not_compared: 0,
      }
    }],

    [`/courses/${COURSE}/similarity-settings`, ({ method, body }) => {
      if (method === 'PUT') {
        settingsWrites.push(body)
        return {}
      }
      return {
        provider: 'jplag',
        mode: 'full',
        providers: [
          { id: 'edit-distance', languages: ['java', 'python', 'sql', 'text'] },
          { id: 'jplag', languages: ['java', 'python', 'text'] },
        ],
      }
    }],

    ['/similarity', ({ method, url, body }) => {
      if (method === 'POST') requests.push({ url, body })
      if (provider === 'edit-distance') return {
        submissions: [
          { id: '101', created_at: '2026-08-01T10:00:00.000Z', solution: '# loeb kaks arvu ja liidab\na = int(input())\nb = int(input())\nsumma = a + b\nprint(summa)\n', given_name: 'Mari', family_name: 'Maasikas', course_title: 'Programmeerimise alused', student_id: 's1' },
          { id: '102', created_at: '2026-08-01T11:00:00.000Z', solution: '# loeb kaks arvu ja liidab\nx = int(input())\ny = int(input())\ntulemus = x + y\nprint(tulemus)\n', given_name: 'Jaan', family_name: 'Tamm', course_title: 'Programmeerimise alused', student_id: 's2' },
        ],
        // Edit distance sends no matches, and says so with match_ranges: false.
        scores: [
          { sub_1: '101', sub_2: '102', similarity: 72, max_similarity: 81, matches: [], score_a: 72, score_b: 81,
            metrics: [{ id: 'levenshtein', value: 64, unit: 'percent' }, { id: 'dice', value: 81, unit: 'percent' }] },
        ],
        compared: 2,
        language: 'python',
        skipped: [],
        truncated: false,
        groups: [],
        provider: 'edit-distance',
        mode: 'full',
        // The course pinned JPlag; this stands for a check JPlag could not take.
        provider_fallback: 'PINNED_UNAVAILABLE',
        match_ranges: false,
        pairs_total: 1, pairs_compared: 1, complete: true,
      }
      return {
        submissions: [
          { id: '101', created_at: '2026-08-01T10:00:00.000Z', solution: '# loeb kaks arvu ja liidab\na = int(input())\nb = int(input())\nsumma = a + b\nprint(summa)\n', given_name: 'Mari', family_name: 'Maasikas', course_title: 'Programmeerimise alused', student_id: 's1' },
          { id: '102', created_at: '2026-08-01T11:00:00.000Z', solution: '# loeb kaks arvu ja liidab\n# teine lahendus\nx = int(input())\ny = int(input())\ntulemus = x + y\nprint(tulemus)\n', given_name: 'Jaan', family_name: 'Tamm', course_title: 'Programmeerimise alused', student_id: 's2' },
        ],
        scores: [
          { sub_1: '101', sub_2: '102', similarity: 91, max_similarity: 94, matches: [{ start_line_1: 2, end_line_1: 5, start_line_2: 3, end_line_2: 6, tokens: 12 }], score_a: 91, score_b: 94,
            metrics: [{ id: 'similarity', value: 91, unit: 'percent' }, { id: 'max_similarity', value: 94, unit: 'percent' }, { id: 'shared_tokens', value: 12, unit: 'count' }] },
          { sub_1: '101', sub_2: '104', similarity: 20, max_similarity: 22, matches: [], score_a: 20, score_b: 22 },
        ],
        compared: 3,
        language: 'python',
        skipped: [],
        truncated: false,
        groups: [],
        provider: 'jplag',
        mode: 'full',
        provider_fallback: null,
        match_ranges: true,
        pairs_total: 3, pairs_compared: 3, complete: true,
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
  check(
    'and how long core says it will take, before anybody waits for it',
    await waitUntil(() => page.getByText('3 submissions, 3 pairs to compare, about 2 s').isVisible()),
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
    JSON.stringify(requests[0]?.body?.students) === JSON.stringify([{ id: 's1' }, { id: 's2' }, { id: 's4' }]),
  )
  check('and scoping to this course', JSON.stringify(requests[0]?.body?.courses) === JSON.stringify([{ id: COURSE }]))

  check(
    'the pair is listed with both scores labelled',
    await waitUntil(async () =>
      (await page.getByText('Similarity 91%').count()) > 0 && (await page.getByText('Max 94%').count()) > 0),
  )
  check('and with what else the provider measured, by name', await page.getByText('12 shared tokens').isVisible())
  check(
    'the result says which provider compared it, and what that means for renamed code',
    await page.getByText(/^Compared by JPlag/).isVisible(),
  )

  // --- the diff, and the link to the student -----------------------------------------------------------
  // Absent, not merely hidden. MUI keeps AccordionDetails mounted inside a collapsed Collapse unless
  // told otherwise, and the page tells it otherwise (`unmountOnExit`) because at the 100-pair cap the
  // mounted editors locked the tab. Collapsed content is `visibility: hidden` either way, so asserting
  // it is not visible passed with the option deleted; counting is what pins it, and what makes the
  // next line mean something.
  const mergeView = page.locator('.cm-mergeView')
  check('the diff is not built until the pair is opened', (await mergeView.count()) === 0)

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
  // --- what the colour means (EZ-1875) -----------------------------------------------------------
  // The stub pair is the same algorithm with every name changed, which a line diff sees as almost
  // completely different. The point of the treatment is that the *shared* skeleton is what carries
  // the ink, so assert both halves: shared runs are marked, and the library's red/green is gone.
  // Asserting only the first would still pass with the default theme layered underneath.
  const marks = await page.evaluate(() => ({
    shared: document.querySelectorAll('.cm-mergeView .cm-commonLine').length,
    litUnderlines: [...document.querySelectorAll('.cm-mergeView .cm-changedText')].filter((el) => {
      const bg = getComputedStyle(el).background
      return bg.includes('gradient') || bg.includes('rgb(')
    }).length,
  }))
  check(`the code both students share is marked (${marks.shared} runs)`, marks.shared > 0)
  check(
    `and the red/green diff colouring is not (${marks.litUnderlines} lit)`,
    marks.litUnderlines === 0,
  )
  check(
    'the highlight is explained, since it means the opposite of a normal diff',
    await page.getByText('Highlighted code appears in both solutions.').isVisible(),
  )

  // Banded per matched line, and only where core said there was a match, on each side. The stub's
  // one match covers lines 2-5 on the left and 3-6 on the right — deliberately not the same, so a
  // page that built the right side from the left's numbers, swapped the two, or dropped the right
  // side's ranges cannot pass. Exact lines rather than "something is banded", which would pass just
  // as well against a version that painted the whole file.
  //
  // The *colour* is asserted, not just the class, because that distinction is what let a real bug
  // ship: `cm-commonLine` was on every matched line while the band was invisible underneath it.
  // The merge package's own `.cm-merge-a .cm-changedLine` rule at (0,3,0) outranked a bare
  // `.cm-commonLine` at (0,2,0), so every line the character diff called changed was painted the
  // library's colour or nothing at all — on a renamed copy, which is the case this page exists for,
  // that was all but the blank lines. A class-membership check passed throughout.
  //
  // The colour is compared with the legend's swatch, which is painted with the band colour itself,
  // rather than merely checked for being one non-transparent value. Every banded fixture line is also
  // one the character diff calls changed, so a band that lost the cascade shows the library's colours
  // — tan on the left, green on the right, which "one colour" across both sides already catches —
  // and a band that is not painted at all is transparent on both, which only the swatch comparison
  // catches. The swatch is right in either theme.
  const banded = await page.evaluate(() => {
    const side = (name) => {
      const lines = [...document.querySelectorAll(`.cm-mergeView .${name} .cm-line`)]
      const marked = lines.filter((l) => l.classList.contains('cm-commonLine'))
      return {
        numbers: lines.map((l, i) => (marked.includes(l) ? i + 1 : null)).filter(Boolean),
        colours: marked.map((l) => getComputedStyle(l).backgroundColor),
      }
    }
    const legend = [...document.querySelectorAll('div')]
      .find((d) => d.textContent === 'Highlighted code appears in both solutions.')
    const swatch = legend?.querySelector('[aria-hidden="true"]')
    const a = side('cm-merge-a')
    const b = side('cm-merge-b')
    return {
      a: a.numbers,
      b: b.numbers,
      colours: [...new Set([...a.colours, ...b.colours])],
      legend: swatch ? getComputedStyle(swatch).backgroundColor : null,
    }
  })
  check(`the left side is banded on exactly its matched lines (${banded.a.join(', ')})`, banded.a.join() === '2,3,4,5')
  check(`and the right side on exactly its own (${banded.b.join(', ')})`, banded.b.join() === '3,4,5,6')
  check(
    `and every banded line is painted the legend's colour (${banded.colours.join(' / ')} vs ${banded.legend})`,
    banded.legend !== null && banded.colours.length === 1 && banded.colours[0] === banded.legend,
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

  // --- select-all takes one side, not the page (EZ-1920) -------------------------------------------------
  // This editor is built from parts and has no keymap of its own, so it is the one place where the
  // binding in `readOnlyEditor` is the only thing standing between Cmd+A and the whole page.
  await page.locator('.cm-mergeView .cm-editor').nth(1).locator('.cm-line').nth(2).click()
  await page.keyboard.press('ControlOrMeta+a')
  const picked = await page.evaluate(() => window.getSelection().toString())
  check(
    "select-all in one side of the diff takes that student's solution",
    picked.includes('tulemus = x + y') && picked.includes('print(tulemus)'),
  )
  check(
    'and neither the other side nor the page around it',
    !picked.includes('summa') && !picked.includes('Maasikas'),
  )

  // --- the score filter ---------------------------------------------------------------------------------
  // The low-scoring pair references a submission the response does not describe, which is what the
  // real endpoint does when a pair falls outside the returned set — it must not crash the page.
  check(
    'a pair whose submissions are missing from the response is skipped rather than crashing',
    (await page.getByText('Similarity 20%').count()) === 0,
  )

  // --- the group filter narrows what gets sent ----------------------------------------------------------
  requests.length = 0
  await page.getByRole('combobox', { name: 'Group' }).click()
  await page.getByRole('option', { name: 'Rühm A' }).click()
  // `scope_one`'s text: '1 pairs' never renders, so this waited out its 8 s and asserted nothing.
  await waitUntil(() => page.getByText('2 submissions, 1 pair to compare').isVisible())
  await page.getByRole('button', { name: /Find similarities/i }).click()
  check(
    'picking a group sends only that group\'s students',
    await waitUntil(() =>
      requests.length === 1 &&
      JSON.stringify(requests[0].body.students) === JSON.stringify([{ id: 's1' }, { id: 's2' }])),
  )

  // --- the course's comparison method (EZ-1667) ---------------------------------------------------------
  await page.getByRole('button', { name: 'Comparison method' }).click()
  const dialog = page.getByRole('dialog')
  // Opened from an exercise, the dialog starts on that exercise's settings: the course's are a click away.
  await dialog.getByRole('button', { name: 'The whole course' }).click()
  check(
    'the dialog opens on what the course stored, a pinned JPlag',
    await waitUntil(() => dialog.getByRole('radio', { name: /^JPlag/ }).isChecked()),
  )
  await dialog.getByRole('radio', { name: /^Automatic/ }).check()
  await dialog.getByRole('button', { name: 'Save' }).click()
  // The mode is not offered yet, so it must go back as it came - 'full' - rather than as a default.
  check(
    'saving Automatic unpins, and leaves the stored mode as it was',
    await waitUntil(() => settingsWrites.length === 1 &&
      JSON.stringify(settingsWrites[0]) === JSON.stringify({ provider: null, mode: 'full' })),
  )

  // --- a provider that says how alike, not where -------------------------------------------------------
  provider = 'edit-distance'
  requests.length = 0
  await page.getByRole('button', { name: /Find similarities/i }).click()
  check(
    'an edit-distance pair shows its text similarity in the row, and Dice only for reference once opened',
    await waitUntil(async () =>
      (await page.getByText('Text similarity 64%').isVisible()) && (await page.getByText('Shared text 81%').count()) === 0),
  )
  check(
    'a pinned provider passed over is said rather than shown as the course\'s choice',
    await page.getByText(/^The method chosen for this course cannot compare/).isVisible(),
  )
  check(
    'and the JPlag reading of renamed code is not claimed for edit distance',
    (await page.getByText(/^Compared by edit distance/).isVisible()) && (await page.getByText(/^Compared by JPlag/).count()) === 0,
  )

  // With no matches to paint, the shared code is what the character diff leaves unchanged: the identical
  // first line is banded, and inside the renamed lines the skeleton is marked and the names are not.
  await page.getByText('Mari Maasikas — Jaan Tamm').click()
  check(
    'the shared code is found by the diff: the identical comment line banded on both sides',
    await waitUntil(() => page.evaluate(() =>
      ['cm-merge-a', 'cm-merge-b'].every((side) =>
        document.querySelector(`.cm-mergeView .${side} .cm-line`)?.classList.contains('cm-commonLine')))),
  )
  const spans = await page.evaluate(() =>
    [...document.querySelectorAll('.cm-mergeView .cm-merge-a .cm-commonSpan')].map((el) => el.textContent))
  check(
    `and the renamed lines marked on their shared skeleton, not their names (${spans.join(' | ')})`,
    spans.some((text) => text.includes('= int(input())')) && !spans.some((text) => /\b[ab]\b/.test(text)),
  )

  await close()
})
