/**
 * What the app does with a large monitor — EZ-1527 and its children, EZ-1915, -1916, -1918, -1919.
 *
 * Until these, the shell capped every page at 1200px: on a 2560×1440 monitor the teacher read a
 * student's code in a 639px editor that clipped it by 445px, beside a thousand empty pixels, and
 * the grade table showed 7 of a course's 24 exercises. The audit that measured it is
 * `tests/audit/s5b-large-monitor-dense.mjs`; this is what keeps it fixed.
 *
 * All geometry, like `course-exercise-layout`, and for the same reason: every element asserted on
 * here existed before. What was wrong was where it was and how wide.
 *
 * Every other spec runs at 1100px, below the `lg` breakpoint, where none of this applies — which
 * is how the cap went unnoticed by the suite for as long as it did.
 *
 *   cd web && npx playwright test large-monitor-layout
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const MONITOR = { width: 2560, height: 1440 }

// Sixty, so the roster is taller than even this window and the table has something to scroll.
const students = Array.from({ length: 60 }, (_, i) => ({
  student_id: `s${i + 1}`,
  given_name: `Eesnimi${i + 1}`,
  family_name: `Perenimi${i + 1}`,
  groups: [],
}))

// One title that fits a narrow column, one that has to wrap, one that cannot fit in two lines.
// Then enough short ones that the table is wider than its toolbar — below that it is the page's
// minimum width stretching the columns, not the titles, and the column check would be measuring that.
const TITLES = [
  'Tsükkel', 'Rekursioon: Hanoi tornid', 'Kahemõõtmelised järjendid ja nende läbimine tsükliga',
  'Failid', 'Sõned', 'Hulgad', 'Klassid', 'Erindid',
]

const listExercise = (idx, title, rows) => ({
  course_exercise_id: String(4147 + idx),
  exercise_id: `ex-${idx}`,
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
  completed_count: rows.length,
  latest_submissions: rows.map((s) => ({
    ...s,
    status: 'COMPLETED',
    submission: {
      id: `${s.student_id}-sub-${idx}`,
      submission_number: 1,
      time: '2026-09-01T10:00:00.000Z',
      grade: { grade: 100, is_autograde: true, is_graded_directly: true },
      seen: true,
      flagged: false,
    },
  })),
})

const exerciseDetails = {
  exercise_id: '9001',
  title: 'Failist lugemine',
  title_alias: null,
  text_html: `<p>${'Kirjuta programm, mis loeb failist õpilaste nimed ja hinded ning väljastab keskmise. '.repeat(6)}</p>`,
  text_md: null,
  instructions_html: null,
  instructions_md: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
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

// The kind of line the audit's fixture clipped: 100 characters, an ordinary f-string.
const solution =
  'rida = "x"\n' +
  'print(f"Vigane rida: {rida}  # rida {reanumber}, hinnet ei saanud täisarvuks teisendada, jätan")\n'

let roster = students

const handlers = () => [
  ['/account/checkin', () => ({})],
  [`/courses/${COURSE}/basic`, () => ({
    title: 'Programmeerimine', alias: null, archived: false, color: '#1976d2', course_code: null,
    moodle_course_url: null,
  })],
  [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
  [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
  [/\/submissions\/latest\/students(\?|$)/, () => ({ ...listExercise(0, exerciseDetails.title, roster), course_exercise_id: CE })],
  [/\/submissions\/all\/students\//, () => ({
    submissions: [{
      id: 'sub-77', submission_number: 1, created_at: '2026-09-01T10:00:00.000Z', status: 'COMPLETED',
      grade: { grade: 100, is_autograde: false, is_graded_directly: true },
    }],
  })],
  [/\/students\/[^/]+\/activities$/, () => ({ teacher_activities: [] })],
  [/\/students\/[^/]+\/inline-comments$/, () => ({ inline_comments: [] })],
  [/\/submissions\/[^/]+$/, () => ({
    id: 'sub-77', solution, submission_number: 1, created_at: '2026-09-01T10:00:00.000Z',
    grade: { grade: 100, is_autograde: false, is_graded_directly: true }, seen: true, flagged: false,
    autograde_status: 'NONE', auto_assessment: null,
  })],
  [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exerciseDetails],
  [new RegExp(`/teacher/courses/${COURSE}/exercises(\\?|$)`), () => ({
    exercises: TITLES.map((t, i) => listExercise(i, t, roster)),
  })],
  [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
]

const box = (page, selector) =>
  page.evaluate((sel) => {
    const el = document.querySelector(sel)
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { left: Math.round(r.left), right: Math.round(r.right), top: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height) }
  }, selector)

test('large-monitor-layout', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', viewport: MONITOR, shotPrefix: 'wide-' })
  await fakeApi(page, handlers(), { log: false })

  // --- a wide route: the grade table ---------------------------------------------------------------
  await page.goto(`${BASE_URL}/courses/${COURSE}/grades`)
  await page.getByText('Eesnimi1 Perenimi1', { exact: true }).waitFor()

  const gradesMain = await box(page, 'main')
  check(
    'a wide route gets the window, not a 1200px column',
    gradesMain.width > 2200,
    `main is ${gradesMain.width}px of ${MONITOR.width}`,
  )

  // The header is what sets a column's width, so it is the header that is measured.
  const headers = await page.evaluate(() =>
    [...document.querySelectorAll('thead th')].slice(2).map((th) => {
      const a = th.querySelector('a')
      return {
        col: Math.round(th.getBoundingClientRect().width),
        lines: Math.round(a.getBoundingClientRect().height / parseFloat(getComputedStyle(a).lineHeight)),
        clamped: a.scrollHeight > a.clientHeight + 1,
      }
    }),
  )
  check(
    'an exercise column is about as wide as the number under it',
    headers.every((h) => h.col <= 100),
    JSON.stringify(headers),
  )
  check(
    'a title too long for one line wraps onto a second instead of being cut',
    headers[1].lines === 2 && !headers[1].clamped,
    JSON.stringify(headers[1]),
  )
  check(
    'and one too long for two stops there',
    headers[2].lines === 2 && headers[2].clamped,
    JSON.stringify(headers[2]),
  )

  // --- the header holds while the roster scrolls (EZ-1919) ----------------------------------------------
  const container = await box(page, '.MuiTableContainer-root')
  // The wheel goes to whatever is under the pointer, and the table is only as wide as its columns.
  await page.mouse.move(container.left + 100, MONITOR.height / 2)
  await page.mouse.wheel(0, 1200)
  await page.waitForTimeout(300)
  const afterScroll = await page.evaluate(() => {
    const th = document.querySelector('thead th:nth-child(3)')
    const corner = document.querySelector('thead th:first-child')
    return {
      windowY: Math.round(window.scrollY),
      tableY: Math.round(document.querySelector('.MuiTableContainer-root').scrollTop),
      thTop: Math.round(th.getBoundingClientRect().top),
      cornerBg: getComputedStyle(corner).backgroundColor,
    }
  })
  check(
    'the table scrolls inside itself and the window stays put',
    afterScroll.tableY > 0 && afterScroll.windowY === 0,
    JSON.stringify(afterScroll),
  )
  check(
    'so the exercise titles are still above the numbers, 1200px down the roster',
    Math.abs(afterScroll.thTop - container.top) <= 2,
    `header at ${afterScroll.thTop}, table starts at ${container.top}`,
  )
  // The sorted column's tint is translucent. As the cell's background it let the rows scrolling
  // underneath show through the sticky corner — a student's name printed across "NAME".
  check(
    'the sticky corner is opaque, so rows do not show through it',
    /^rgb\(/.test(afterScroll.cornerBg),
    afterScroll.cornerBg,
  )
  await shot('01-grades-scrolled')

  // A limit, not a height: two students must not get a table stretched to the bottom of the window.
  roster = students.slice(0, 2)
  await page.reload()
  await page.getByText('Eesnimi1 Perenimi1', { exact: true }).waitFor()
  const short = await box(page, '.MuiTableContainer-root')
  check('a short roster gets a short table', short.height < 250, `${short.height}px`)
  roster = students

  // --- a capped route sits beside the sidebar --------------------------------------------------------
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises`)
  await page.getByText('Rekursioon: Hanoi tornid').first().waitFor()
  const cappedMain = await box(page, 'main')
  check('a list page keeps its 1200px', cappedMain.width === 1200, `${cappedMain.width}px`)
  check(
    'and shares the wide pages\' left edge rather than floating in the middle',
    cappedMain.left === gradesMain.left,
    `capped at ${cappedMain.left}, wide at ${gradesMain.left}`,
  )

  // --- the exercise page's split (EZ-1916) -------------------------------------------------------------
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}?student=s1`)
  await waitUntil(async () => (await page.getByText('Vigane rida').count()) > 0)
  await page.waitForTimeout(500)

  const statement = await page.evaluate(() => {
    const p = [...document.querySelectorAll('main p')].find((el) => el.textContent.includes('Kirjuta programm'))
    return Math.round(p.getBoundingClientRect().width)
  })
  check(
    'the task text stops at a readable width however wide the window is',
    statement <= 720,
    `${statement}px`,
  )
  const editor = await page.evaluate(() => {
    const s = document.querySelector('.cm-scroller')
    return { width: s.clientWidth, clipped: s.scrollWidth - s.clientWidth }
  })
  check(
    'and the code gets the width — a 100-character line is not clipped',
    editor.width > 950 && editor.clipped <= 0,
    JSON.stringify(editor),
  )

  // --- the grade form beside the code (EZ-1917) ----------------------------------------------------
  const gradingRow = (p) =>
    p.evaluate(() => {
      const code = document.querySelector('.cm-editor').getBoundingClientRect()
      const grade = document.querySelector('input[inputmode="numeric"]').getBoundingClientRect()
      return {
        beside: grade.left >= code.right,
        gradeBottom: Math.round(grade.bottom),
        codeWidth: Math.round(code.width),
        windowH: window.innerHeight,
      }
    })
  const row = await gradingRow(page)
  check(
    'with the room for it, the grade form sits beside the code rather than under it',
    row.beside && row.gradeBottom < row.windowH,
    JSON.stringify(row),
  )
  await shot('02-grading')

  // 1920×1080, the monitor on most desks. With the task text open there is not room for three
  // columns without clipping the code again, so the form stays underneath…
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.waitForTimeout(400)
  const fhdOpen = await gradingRow(page)
  check(
    'at 1920 with the task text open, the code keeps the width and the form stays below',
    !fhdOpen.beside && fhdOpen.codeWidth > 850,
    JSON.stringify(fhdOpen),
  )
  // …and collapsing it — how a teacher works through a roster — is what brings the form alongside.
  await page.getByRole('button', { name: 'Collapse left pane' }).click()
  await page.waitForTimeout(400)
  const fhdCollapsed = await gradingRow(page)
  check(
    'at 1920 with the task text collapsed, the form is beside the code and on screen',
    fhdCollapsed.beside && fhdCollapsed.gradeBottom < 1080 && fhdCollapsed.codeWidth > 950,
    JSON.stringify(fhdCollapsed),
  )
  await shot('03-grading-1920-collapsed')
  await page.getByRole('button', { name: 'Split view' }).click()
  await page.setViewportSize(MONITOR)
  await page.waitForTimeout(400)

  // Dragging stores pixels. A percentage means a different thing on every monitor.
  const gutter = await page.evaluate(() => {
    const p = [...document.querySelectorAll('main p')].find((el) => el.textContent.includes('Kirjuta programm'))
    const pane = p.closest('div[class]').parentElement
    return Math.round(pane.getBoundingClientRect().right) + 17
  })
  await page.mouse.move(gutter, 700)
  await page.mouse.down()
  await page.mouse.move(gutter - 200, 700, { steps: 5 })
  await page.mouse.up()
  const stored = await page.evaluate(() => ({
    px: localStorage.getItem('splitPane.teacherExercise.leftPx'),
    pct: localStorage.getItem('splitPane.teacherExercise.leftPct'),
  }))
  check(
    'a dragged divider is remembered in pixels',
    Number(stored.px) > 400 && Number(stored.px) < 600 && stored.pct === null,
    JSON.stringify(stored),
  )
  await close()

  // --- a divider dragged before the page went wide ------------------------------------------------------
  // Stored as 30% of a pane the shell capped at 1152px, so 346px. Read as 30% of today's pane it
  // would come back as 675px on this monitor — not what anyone chose.
  const returning = await launch({ role: 'teacher,admin', viewport: MONITOR, shotPrefix: 'wide-returning-' })
  await fakeApi(returning.page, handlers(), { log: false })
  await returning.page.addInitScript(() => {
    if (!sessionStorage.getItem('seeded')) {
      localStorage.setItem('splitPane.teacherExercise.leftPct', '30')
      sessionStorage.setItem('seeded', '1')
    }
  })
  await returning.page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}?student=s1`)
  await waitUntil(async () => (await returning.page.getByText('Vigane rida').count()) > 0)
  const migrated = await returning.page.evaluate(() => ({
    px: localStorage.getItem('splitPane.teacherExercise.leftPx'),
    pct: localStorage.getItem('splitPane.teacherExercise.leftPct'),
  }))
  check(
    'an old percentage becomes the pixels it used to mean, once',
    migrated.px === '346' && migrated.pct === null,
    JSON.stringify(migrated),
  )
  await returning.close()
})
