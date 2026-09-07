/**
 * Dragging a solution file onto a code editor.
 *
 * The bug this exists for: it opened the file in a new tab. CodeMirror registers its handlers on
 * the `contenteditable` and nothing anywhere calls `preventDefault` on `dragover`, so the editor
 * was a drop target only in as far as a contenteditable is one by default — and Chromium refuses a
 * file drag there, because a plain-text editable accepts a drag only when it carries plain text.
 * No drop event was fired, the browser did what it does with a file dropped on a page, and the
 * student's half-written solution went with the page.
 *
 * So the check that matters most here is not "the text arrived" but **`dragover` was accepted**:
 * that is the single fact the browser consults before deciding whether this is a drop zone or a
 * navigation, and it is the one that was false. The rest follows from it.
 *
 *   cd web && npx playwright test editor-file-drop
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'
const EX = '9001'

const DROPPED = 'print("dropped from the file manager")'

const exercise = {
  effective_title: 'Kodutöö 6',
  text_html: '<p>Read two integers and print their sum.</p>',
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
}

// The same exercise past its deadline. Nothing makes the editor read-only when this is false — only
// the upload menu item and the submit button go away — so there is still a solution in it to lose.
const closedExercise = { ...exercise, is_open: false }

const submissions = {
  submissions: [
    {
      id: '9001',
      number: 1,
      solution: 'print("what I had before")',
      submission_time: '2026-08-30T10:19:00.000Z',
      autograde_status: 'COMPLETED',
      grade: { grade: 60, is_autograde: true, is_graded_directly: true },
      submission_status: 'COMPLETED',
      auto_assessment: null,
    },
  ],
}

const teacherExercise = {
  exercise_id: EX,
  title: 'Sum of two numbers',
  title_alias: null,
  text_html: '<p>Read two integers and print their sum.</p>',
  text_md: 'Read two integers and print their sum.',
  instructions_html: null,
  instructions_md: null,
  soft_deadline: null,
  hard_deadline: null,
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  threshold: 100,
  last_modified: '2026-07-30T12:00:00.000Z',
  student_visible: true,
  student_visible_from: null,
  assessments_student_visible: true,
  grading_script: 'python grade.py',
  container_image: 'pygrader',
  max_time_sec: 12,
  max_mem_mb: 44,
  assets: [],
  executors: [{ id: '1', name: 'mock-executor' }],
  has_lib_access: true,
  exception_students: null,
  exception_groups: null,
}

/**
 * Dispatches a drag event carrying a file, and reports whether anything claimed it.
 *
 * A synthetic event cannot make Chromium navigate, so this cannot observe the new tab directly —
 * what it observes is the decision the browser would make from `defaultPrevented`, which is the
 * same bit and is testable.
 */
const dragFile = (page, selector, type) =>
  page.evaluate(([sel, evType]) => {
    const dt = new DataTransfer()
    dt.items.add(new File(['x'], 'lahendus.py', { type: 'text/x-python' }))
    const event = new DragEvent(evType, { dataTransfer: dt, bubbles: true, cancelable: true })
    document.querySelector(sel).dispatchEvent(event)
    return event.defaultPrevented
  }, [selector, type])

/** The same, for a drag carrying only text — moving a selection inside the document. */
const dragText = (page, selector) =>
  page.evaluate((sel) => {
    const dt = new DataTransfer()
    dt.setData('text/plain', 'a moved selection')
    const event = new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true })
    document.querySelector(sel).dispatchEvent(event)
    return event.defaultPrevented
  }, selector)

/** `bytes` is a plain array so it survives the hop into the page. */
const dropFile = (page, selector, name, bytes) =>
  page.evaluate(([sel, fileName, content]) => {
    const dt = new DataTransfer()
    dt.items.add(new File([new Uint8Array(content)], fileName))
    document.querySelector(sel)
      .dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }))
  }, [selector, name, bytes])

const utf8 = (s) => Array.from(new TextEncoder().encode(s))

test('editor-file-drop', async ({ launch, check }) => {
  // --- the student's solution editor ------------------------------------------------------------

  const { page, shot, close } = await launch({ role: 'student', shotPrefix: 'file-drop-' })
  await fakeApi(page, [
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
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await waitUntil(() => page.locator('.cm-content').first().isVisible())
  const docText = () => page.locator('.cm-content').first().innerText()

  check(
    'the editor accepts a file drag — the bit the browser reads before choosing to navigate',
    await dragFile(page, '.cm-content', 'dragover'),
  )
  check('and says so on screen', await waitUntil(() =>
    page.locator('.cm-editor.easy-cm-fileDragOver').first().isVisible()))
  await shot('01-drag-over')

  // The line numbers are not the contenteditable, so a drop landing a few pixels to the left of
  // the first character was never handled under any browser, prevented dragover or not.
  check('the gutter accepts it too', await dragFile(page, '.cm-gutters', 'dragover'))

  // Dragging a selection from one place in the document to another is a move CodeMirror implements
  // itself. Claiming every dragover would silently break it.
  check('a text drag is left alone', !(await dragText(page, '.cm-content')))

  await dropFile(page, '.cm-content', 'lahendus.py', utf8(DROPPED))
  check(
    'a dropped file becomes the solution',
    await waitUntil(async () => (await docText()).includes('dropped from the file manager')),
  )
  check('replacing what was there, as the upload menu item does',
    !(await docText()).includes('what I had before'))
  await shot('02-dropped')

  // --- a file that is not a solution ------------------------------------------------------------

  // Decoded strictly on purpose: leniently, a PDF dragged in by mistake arrives as a screenful of
  // replacement characters and gets submitted as one.
  await dropFile(page, '.cm-content', 'handout.pdf', [0xff, 0xfe, 0xff, 0xfe])
  check('a file that is not text is refused', await waitUntil(() =>
    page.getByText('The chosen file is not a text file').isVisible()))
  check('and the solution is left standing', (await docText()).includes('dropped from the file manager'))

  await page.evaluate(() => {
    const dt = new DataTransfer()
    // Built in the page rather than shipped through evaluate: 300 KB of array literal is slow, and
    // the only thing that matters about it is the length.
    dt.items.add(new File([new Uint8Array(400_000)], 'dump.sql'))
    document.querySelector('.cm-content')
      .dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }))
  })
  check('and one that is too large is refused', await waitUntil(() =>
    page.getByText('The chosen file is too large').isVisible()))
  await close()

  // --- the teacher's testing editor -------------------------------------------------------------

  // The same box to a teacher checking their own exercise, and it had the same bug.
  const teacher = await launch({ role: 'teacher,admin', shotPrefix: 'file-drop-t-' })
  await fakeApi(teacher.page, [
    ['/account/checkin', () => ({})],
    ['/participants', () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
    ['/groups', () => ({ groups: [] })],
    [`/teacher/courses/${COURSE}/exercises/${CE}/submissions/latest/students`, () => ({ latest_submissions: [] })],
    ['/submissions/latest', () => ({ latest_submissions: [] })],
    [`/student/courses/${COURSE}/exercises`, () => ({ exercises: [] })],
    [`/exercises/${EX}/testing/autoassess/submissions`, () => ({ count: 0, submissions: [] })],
    [/\/submissions\/latest\/students(\?|$)/, () => ({ latest_submissions: [] })],
    ['/submissions/', () => ({ submissions: [], count: 0 })],
    [`/teacher/courses/${COURSE}/exercises/${CE}`, () => teacherExercise],
    ['/teacher/courses', () => ({ courses: [] })],
  ], { log: false })

  await teacher.page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await teacher.page.getByText('Sum of two numbers').first().waitFor()
  await teacher.page.getByRole('tab', { name: 'Try it' }).click()
  await waitUntil(() => teacher.page.locator('.cm-content').first().isVisible())

  check('the testing editor accepts a file drag', await dragFile(teacher.page, '.cm-content', 'dragover'))
  await dropFile(teacher.page, '.cm-content', 'lahendus.py', utf8(DROPPED))
  check(
    'and a dropped file becomes the solution to test',
    await waitUntil(async () =>
      (await teacher.page.locator('.cm-content').first().innerText()).includes('dropped from the file manager')),
  )
  await teacher.shot('03-teacher-dropped')

  // The rebuild cases are what quietly cost work here: the view is destroyed and recreated on a
  // theme change, and a dropped file has to survive that like typed text does.
  await teacher.page.getByRole('button', { name: 'Account menu' }).click()
  await teacher.page.getByRole('menuitem', { name: /Dark mode|Light mode/ }).click()
  await teacher.page.keyboard.press('Escape')
  check(
    'and survives the editor being rebuilt',
    await waitUntil(async () =>
      (await teacher.page.locator('.cm-content').first().innerText()).includes('dropped from the file manager')),
  )
  await teacher.close()

  // --- a closed exercise ------------------------------------------------------------------------

  // The handler used to be registered only while the exercise was open, reasoning that a closed one
  // should ignore a drop the way a read-only editor does. It is not read-only, and an unclaimed
  // dragover is not "ignore": it is the browser deciding this is a navigation, opening the file and
  // taking the page — and the student's solution — with it. Precisely the bug the rest of this file
  // is about, still reachable on any exercise past its deadline. So a closed exercise claims the
  // drag as well, and declines the file afterwards, where declining costs nothing.
  const closed = await launch({ role: 'student', shotPrefix: 'file-drop-closed-' })
  await fakeApi(closed.page, [
    ['/account/checkin', () => ({})],
    [/\/statistics(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/statistics\/common(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 1, total_users: 1 })],
    [/\/messages(\?|$)/, () => ({ messages: [] })],
    [`/student/courses/${COURSE}/exercises/${CE}/submissions/all`, () => submissions],
    [`/student/courses/${COURSE}/exercises/${CE}/draft`, ({ route }) => route.fulfill({ status: 204, body: '' })],
    [`/student/courses/${COURSE}/exercises/${CE}/activities`, () => ({ teacher_activities: [] })],
    [`/student/courses/${COURSE}/exercises/${CE}/inline-comments`, () => ({ inline_comments: [] })],
    [new RegExp(`/student/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => closedExercise],
    [new RegExp(`/student/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises: [] })],
  ], { log: false })

  await closed.page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)
  await waitUntil(() => closed.page.locator('.cm-content').first().isVisible())

  check(
    'a closed exercise accepts the drag too, which is what keeps the browser from navigating',
    await dragFile(closed.page, '.cm-content', 'dragover'),
  )
  await dropFile(closed.page, '.cm-content', 'lahendus.py', utf8(DROPPED))
  // Scoped to the snackbar: a closed exercise already carries the same sentence in a chip near the
  // title, so an unscoped match would pass on the chip alone and prove nothing about the drop.
  check('and says why it will not take the file', await waitUntil(() =>
    closed.page.getByRole('alert')
      .getByText('This exercise is closed. No new submissions are accepted.').isVisible()))
  check(
    'leaving the existing solution exactly as it was',
    (await closed.page.locator('.cm-content').first().innerText()).includes('what I had before'),
  )
  await closed.shot('01-closed-refused')
  await closed.close()
})
