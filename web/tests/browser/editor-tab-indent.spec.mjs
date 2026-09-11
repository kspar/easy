/**
 * Tab in the solution editor — EZ-1904.
 *
 * CodeMirror 6 leaves Tab unbound, so pressing it in the student's editor moved focus to the
 * submit button, and Enter after `def f():` indented by the library's default two spaces. What is
 * asserted here is the keyboard half that the unit test cannot reach: that the key gets to the
 * command, that the indent unit reaches the auto-indenter, and — the reason CodeMirror leaves Tab
 * alone in the first place — that Escape followed by Tab still hands focus to the browser.
 *
 *   cd web && npx playwright test editor-tab-indent
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '119'
const CE = '4147'

const exercise = {
  effective_title: 'Kahe arvu summa',
  text_html: '<p>Loe kaks täisarvu ja väljasta nende summa.</p>',
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
}

const handlers = [
  ['/account/checkin', () => ({})],
  [/\/statistics(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 0, total_users: 1 })],
  [/\/statistics\/common(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 0, total_users: 1 })],
  [/\/messages(\?|$)/, () => ({ messages: [] })],
  [`/student/courses/${COURSE}/exercises/${CE}/submissions/all`, () => ({ submissions: [] })],
  [`/student/courses/${COURSE}/exercises/${CE}/draft`, ({ route }) => route.fulfill({ status: 204, body: '' })],
  [`/student/courses/${COURSE}/exercises/${CE}/activities`, () => ({ teacher_activities: [] })],
  [`/student/courses/${COURSE}/exercises/${CE}/inline-comments`, () => ({ inline_comments: [] })],
  [new RegExp(`/student/courses/${COURSE}/exercises/${CE}(\\?|$)`), () => exercise],
  [new RegExp(`/student/courses/${COURSE}/exercises(\\?|$)`), () => ({ exercises: [] })],
]

test('editor-tab-indent', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'student', shotPrefix: 'tab-indent-' })
  await fakeApi(page, handlers, { log: false })
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises/${CE}`)

  const editor = page.locator('.cm-content').first()
  await waitUntil(() => editor.isVisible())
  // Read off the rendered lines via textContent rather than innerText, because trailing spaces
  // are exactly what is under test and innerText is a layout-dependent rendering. The placeholder
  // is a widget inside the first line while the document is empty, and is not document text.
  const doc = () =>
    editor.evaluate((el) =>
      [...el.querySelectorAll('.cm-line')]
        .map((line) =>
          [...line.childNodes]
            .filter((n) => !(n instanceof Element && n.classList.contains('cm-placeholder')))
            .map((n) => n.textContent)
            .join(''),
        )
        .join('\n'),
    )
  const focused = () => page.evaluate(() => document.activeElement?.className ?? '')

  await editor.click()
  await page.keyboard.press('Tab')
  check('Tab on an empty line inserts four spaces, and the cursor stays in the editor',
    await doc() === '    ' && (await focused()).includes('cm-content'), await doc())

  await page.keyboard.press('Shift+Tab')
  check('Shift-Tab takes them back out', await doc() === '', JSON.stringify(await doc()))

  await page.keyboard.type('def f():')
  await page.keyboard.press('Enter')
  check('Enter after a block opener auto-indents by four, not the library default two',
    await doc() === 'def f():\n    ', JSON.stringify(await doc()))

  await page.keyboard.type('x = 1')
  await page.keyboard.press('Home')
  await page.keyboard.press('Shift+End')
  await page.keyboard.press('Tab')
  check('Tab with a selection indents the line rather than replacing the selection',
    await doc() === 'def f():\n        x = 1', JSON.stringify(await doc()))

  await page.keyboard.press('End')
  await page.keyboard.press('Tab')
  check('Tab mid-line pads to the next column stop, not by a fixed four',
    await doc() === 'def f():\n        x = 1   ', JSON.stringify(await doc()))
  await shot('01-indented')

  // The escape hatch CodeMirror keeps for keyboard users: Escape then Tab leaves the editor.
  await page.keyboard.press('Escape')
  await page.keyboard.press('Tab')
  check('Escape then Tab moves focus out of the editor instead of indenting',
    !(await focused()).includes('cm-content'), await focused())
  check('and leaves the document as it was', await doc() === 'def f():\n        x = 1   ')

  await close()
})
