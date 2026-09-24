/**
 * A read-only editor holds focus, so the keyboard works in it (EZ-1920).
 *
 * A teacher reading a student's solution clicked a line, saw it light up the way a focused line
 * does, pressed Cmd+A — and selected the whole page. The editor was `editable.of(false)`, which
 * leaves its content element unfocusable, so the click moved CodeMirror's selection and nothing
 * else, and no key ever reached it.
 *
 * What is asserted is what the browser holds — `document.activeElement` and the DOM selection —
 * rather than CodeMirror's state, because CodeMirror's state was right all along. And the other
 * half each time: focusable must not have become editable, and must not have become a focus trap.
 *
 *   cd web && npx playwright test editor-readonly-focus
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const EX = '9001'
const STUDENT = 's-mari'
const SUBMISSION = 'sub-77'

const SOLUTION = 'a = int(input())\nb = int(input())\nprint(a + b)\n'

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
  grader_type: 'TEACHER',
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
  ai_explanations_per_student: 0,
  exception_students: null,
  exception_groups: null,
}

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
  completed_count: 0,
  latest_submissions: [{
    student_id: STUDENT,
    given_name: 'Mari',
    family_name: 'Maasikas',
    groups: [],
    status: 'UNGRADED',
    submission: {
      id: SUBMISSION,
      submission_number: 1,
      time: '2026-08-01T10:00:00.000Z',
      grade: null,
      seen: true,
      flagged: false,
    },
  }],
}

/** The solution as one string, however the browser chose to break the lines it selected. */
const selectedText = (page) =>
  page.evaluate(() => window.getSelection().toString().replace(/\s+/g, ' ').trim())


/** Cursor elements that would actually paint. CodeMirror keeps them in the DOM and hides them. */
const caretsShown = (editor) =>
  editor.evaluate((el) =>
    [...el.querySelectorAll('.cm-cursor')].filter((c) => getComputedStyle(c).display !== 'none').length)

const outlineOf = (content) => content.evaluate((el) => getComputedStyle(el).outlineStyle)

test('editor-readonly-focus',async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'ro-focus-' })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/basic`, () => ({
      title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
      moodle_course_url: null,
    })],
    [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    [/\/submissions\/seen$/, () => ({})],
    [/\/submissions\/latest\/students(\?|$)/, () => latestStudents],
    [/\/submissions\/all\/students\//, () => ({
      submissions: [{
        id: SUBMISSION,
        submission_number: 1,
        created_at: '2026-08-01T10:00:00.000Z',
        status: 'UNGRADED',
        grade: null,
      }],
    })],
    [/\/students\/[^/]+\/activities$/, () => ({ teacher_activities: [], ai_feedback: [] })],
    [/\/students\/[^/]+\/inline-comments$/, () => ({ inline_comments: [] })],
    [/\/submissions\/[^/]+$/, () => ({
      id: SUBMISSION,
      solution: SOLUTION,
      submission_number: 1,
      created_at: '2026-08-01T10:00:00.000Z',
      grade: null,
      seen: true,
      flagged: false,
      autograde_status: 'NONE',
      auto_assessment: null,
    })],
    [new RegExp(`/teacher/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}?student=${STUDENT}`)
  await waitUntil(async () => (await page.getByText('print(a + b)').count()) > 0)

  // The student's solution is the first editor on the page; the feedback composer comes after it.
  const editor = page.locator('.cm-editor').first()
  const content = editor.locator('.cm-content')

  check(
    'the solution is still not editable',
    (await content.getAttribute('contenteditable')) === 'false',
  )

  // --- the report: click a line, press select-all ------------------------------------------------
  await editor.locator('.cm-line').nth(1).click()
  check(
    'clicking a line puts focus in the editor, not just a highlight on the line',
    await waitUntil(() => content.evaluate((el) => el === document.activeElement)),
  )
  // Focus is what makes CodeMirror draw its cursor, and a blinking caret in text nobody can type
  // in is a promise the editor does not keep.
  check('but no caret — there is nowhere to type', (await caretsShown(editor)) === 0)
  check('and no focus ring after a click', (await outlineOf(content)) === 'none')

  await page.keyboard.press('ControlOrMeta+a')
  const selected = await selectedText(page)
  check(
    'select-all then selects the whole solution',
    selected === SOLUTION.replace(/\s+/g, ' ').trim(),
  )
  check(
    'and nothing outside it — the page around the editor is not part of the selection',
    !selected.includes('Mari') && !selected.includes('Sum of two numbers'),
  )
  await shot('01-solution-selected')

  // --- focusable is not editable -----------------------------------------------------------------
  await page.keyboard.type('x')
  await page.keyboard.press('Backspace')
  check(
    'typing over the selection changes nothing',
    (await content.innerText()).replace(/\s+/g, ' ').trim() === SOLUTION.replace(/\s+/g, ' ').trim(),
  )

  // --- and not a trap ----------------------------------------------------------------------------
  // The editable editors bind Tab to indent (EZ-1904), with Escape-then-Tab as the way out. A
  // read-only one has nothing to indent, so Tab has to stay the browser's.
  await page.keyboard.press('Tab')
  check(
    'Tab leaves the editor rather than being swallowed by it',
    await waitUntil(async () => !(await content.evaluate((el) => el === document.activeElement))),
  )

  // --- reachable without a mouse -----------------------------------------------------------------
  await page.keyboard.press('Shift+Tab')
  check(
    'and Shift+Tab comes back to it, so the keyboard alone can reach the solution',
    await waitUntil(() => content.evaluate((el) => el === document.activeElement)),
  )
  // With the caret gone the ring is the only thing telling a keyboard user where they are, and
  // CodeMirror switches the content element's outline off, so it does not come for free.
  check('arriving by keyboard draws the focus ring', (await outlineOf(content)) === 'solid')
  check('still without a caret', (await caretsShown(editor)) === 0)
  await shot('02-keyboard-focus')

  await close()
})
