/**
 * The exercise text editor's markdown toolbar, and the guard around exercises that have no
 * markdown source.
 *
 * Backend faked throughout. The toolbar drives shared commands (`components/markdown/`) that are
 * unit-covered against raw CodeMirror state; what this checks is the wiring — that the buttons
 * exist, reach the editor, and are absent when they should be.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const ID = '4242'
const DIR = '77'

const BASE_EXERCISE = {
  dir_id: DIR,
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-07-30T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Sum of two numbers',
  text_html: '<p>Read two numbers and print their sum.</p>',
  text_md: 'Read two numbers and print their sum.',
  // Empty string, not null: core has sent this non-nullable since changeset 020826-1, which
  // gave "no template" a single spelling. The contract check against doc/core/api-shapes.json
  // caught this fixture still describing a response core cannot produce.
  anonymous_autoassess_template: '',
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: [],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

let exercise = { ...BASE_EXERCISE }

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-ex-md-' })
const check = checker()

page.on('dialog', (d) => d.accept())

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  ['/preview/markdown', ({ body }) => ({ content: `<p>PREVIEW:${body.content}</p>` })],
  ['/teacher/courses', () => ({ courses: [] })],
  [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Algoritmid' }] })],
  [new RegExp(`/exercises/${ID}(\\?|$)`), () => exercise],
])

const editor = page.locator('.cm-content').first()
const docText = () => editor.innerText()

async function enterEditMode() {
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).waitFor()
}

/** Select the whole document, so a formatting button acts on known content. */
async function selectAll() {
  await editor.click()
  await page.keyboard.press('ControlOrMeta+a')
}

await page.goto(`${BASE_URL}/library/exercise/${ID}/sum`)
await page.waitForSelector('text=Sum of two numbers')

// --- the toolbar is edit-mode only ----------------------------------------------------------
check(
  'no formatting toolbar before editing',
  !(await page.getByRole('toolbar', { name: 'Formatting' }).isVisible()),
)

await enterEditMode()
const toolbar = page.getByRole('toolbar', { name: 'Formatting' })
check('toolbar appears in edit mode', await toolbar.isVisible())

// Everything the feedback editor offers, which this one is required to match...
for (const name of ['Bold', 'Italic', 'Inline code', 'Bullet list', 'Numbered list']) {
  check(`toolbar has ${name}`, await toolbar.getByRole('button', { name, exact: true }).isVisible())
}
// ...plus the ones only this editor has.
for (const name of ['Heading', 'Strikethrough', 'Quote', 'Link', 'Image', 'Code block', 'Table', 'Divider']) {
  check(`toolbar adds ${name}`, await toolbar.getByRole('button', { name, exact: true }).isVisible())
}
await shot('01-toolbar')

// --- buttons reach the editor ---------------------------------------------------------------
await selectAll()
await toolbar.getByRole('button', { name: 'Bold', exact: true }).click()
check('bold wraps the selection', (await docText()).includes('**Read two numbers and print their sum.**'))

// Clicking the same button again takes it back off — the selection wrapping leaves behind is
// exactly the one unwrapping looks for.
await toolbar.getByRole('button', { name: 'Bold', exact: true }).click()
check('bold a second time unwraps it', (await docText()).trim() === 'Read two numbers and print their sum.')

await selectAll()
await page.keyboard.press('Backspace')

// A table is the one that has to land as its own block, so it is worth asserting on directly.
await editor.click()
await page.keyboard.type('Intro line')
await toolbar.getByRole('button', { name: 'Table', exact: true }).click()
const withTable = await docText()
check('table inserts a header row', withTable.includes('| Heading 1 | Heading 2 |'))
check('table inserts a delimiter row', withTable.includes('| --- | --- |'))
check('table keeps the paragraph above it', withTable.includes('Intro line'))

// Heading is a menu rather than a button, so its wiring differs from the rest.
await selectAll()
await page.keyboard.press('Backspace')
await editor.click()
await page.keyboard.type('Section title')
await toolbar.getByRole('button', { name: 'Heading', exact: true }).click()
await page.getByRole('menuitem', { name: 'Heading 2' }).click()
check('heading menu applies h2', (await docText()).includes('## Section title'))
await shot('02-formatted')

// --- keyboard shortcut ------------------------------------------------------------------------
await selectAll()
await page.keyboard.press('Backspace')
await editor.click()
await page.keyboard.type('shortcut')
await page.keyboard.press('ControlOrMeta+a')
await page.keyboard.press('ControlOrMeta+b')
check('Mod-b bolds without the toolbar', (await docText()).includes('**shortcut**'))

// --- every action leaves the caret in the editor ------------------------------------------------
// Two of these did not. The divider dispatched inline in the toolbar and never called
// `view.focus()`, and the heading menu had MUI's focus trap hand focus back to the toolbar button
// as it closed — after the action had already focused the editor. Both presented as the cursor
// simply disappearing, which is invisible to an assertion about document text.
const activeIsEditor = () =>
  page.evaluate(() => document.activeElement?.classList?.contains('cm-content') === true)

// Image is not in this list: it opens a menu now that uploading is offered alongside by-URL, so it
// belongs with Heading below rather than with the buttons that act immediately.
for (const name of ['Bold', 'Italic', 'Strikethrough', 'Inline code', 'Bullet list',
  'Numbered list', 'Quote', 'Link', 'Code block', 'Table', 'Divider']) {
  await selectAll()
  await page.keyboard.press('Backspace')
  await page.keyboard.type('caret test')
  await toolbar.getByRole('button', { name, exact: true }).click()
  check(`${name} keeps the caret in the editor`, await activeIsEditor())
}

await selectAll()
await page.keyboard.press('Backspace')
await page.keyboard.type('caret test')
await toolbar.getByRole('button', { name: 'Heading', exact: true }).click()
await page.getByRole('menuitem', { name: 'Heading 2' }).click()
// The menu re-focuses on its exit transition, so poll rather than assert on the next tick.
check('Heading menu keeps the caret in the editor', await waitUntil(activeIsEditor))

await selectAll()
await page.keyboard.press('Backspace')
await page.keyboard.type('caret test')
await toolbar.getByRole('button', { name: 'Image', exact: true }).click()
await page.getByRole('menuitem', { name: /URL/ }).click()
check('Image menu keeps the caret in the editor', await waitUntil(activeIsEditor))
check('and by-URL still inserts the old markup', (await docText()).includes('!['))

// --- the exercise with no markdown source -----------------------------------------------------
// text_html but no text_md is the shape ~1000 production exercises are still in. Saving derives
// text_html from text_md, so saving with the box empty deletes the text — and renaming was enough
// to trigger it, since nothing else required the text to be touched.
exercise = { ...BASE_EXERCISE, text_md: null }
await page.goto(`${BASE_URL}/library/exercise/${ID}/sum`)
await page.waitForSelector('text=Sum of two numbers')
await enterEditMode()

check(
  'legacy exercise warns that it has no markdown source',
  await page.getByText(/no source text to load/).isVisible(),
)
check(
  'and refuses to save while the box is empty',
  await page.getByRole('button', { name: 'Save', exact: true }).isDisabled(),
)
await shot('03-no-markdown-source')

await editor.click()
await page.keyboard.type('Recovered text.')
check(
  'typing something re-enables Save',
  await page.getByRole('button', { name: 'Save', exact: true }).isEnabled(),
)

await browser.close()
process.exit(check.summary() ? 0 : 1)
