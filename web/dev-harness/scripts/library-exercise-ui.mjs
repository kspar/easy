/**
 * The library exercise page's non-TSL behaviour: edit mode, the save payload, the
 * concurrent-edit guards, the action dialogs, and the raw-JSON fallback for TSL test types that
 * have no form.
 *
 * Backend is entirely faked here — the point is the page's own logic. `library-exercise-tsl-live`
 * covers the parts that need a real core.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const ID = '4242'
const DIR = '77'

/** Mutable so PUT/refetch behave the way query invalidation would in production. */
let exercise = {
  dir_id: DIR,
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-07-30T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Sum of two numbers',
  text_html: '<p>Read two numbers and print their sum.</p>',
  text_md: 'Read two numbers and print their sum.',
  anonymous_autoassess_template: null,
  grading_script: 'cd student-submission\npython -m grader.easy',
  container_image: 'pygrader',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [{ file_name: 'tester.py', file_content: 'from grader import *' }],
  executors: [],
  on_courses: [
    {
      id: '9006',
      title: 'Programmeerimise alused',
      alias: null,
      course_exercise_id: '55',
      course_exercise_title_alias: null,
    },
  ],
  on_courses_no_access: 2,
}

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-ex-ui-' })
const check = checker()

const puts = []
const patches = []

/** window.confirm answers, consumed in order. */
let confirmAnswers = []
const confirmsSeen = []
page.on('dialog', async (d) => {
  confirmsSeen.push(d.message())
  const answer = confirmAnswers.length ? confirmAnswers.shift() : true
  await (answer ? d.accept() : d.dismiss())
})

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  ['/preview/markdown', ({ body }) => ({ content: `<p>PREVIEW:${body.content}</p>` })],
  ['/teacher/courses', () => ({ courses: [{ id: '9006', title: 'Programmeerimise alused', alias: null }] })],
  [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Algoritmid' }] })],
  [`/lib/dirs/${DIR}/access`, () => ({
    direct_any: null,
    direct_accounts: [],
    direct_groups: [],
    inherited_any: null,
    inherited_accounts: [],
    inherited_groups: [],
  })],
  [
    new RegExp(`/exercises/${ID}(\\?|$)`),
    ({ method, body }) => {
      if (method === 'PUT') {
        puts.push(body)
        exercise = { ...exercise, title: body.title, text_md: body.text_md }
        return {}
      }
      if (method === 'PATCH') {
        patches.push(body)
        exercise = { ...exercise, is_anonymous_autoassess_enabled: body.anonymous_autoassess_enabled }
        return {}
      }
      return exercise
    },
  ],
])

await page.goto(`${BASE_URL}/library/exercise/${ID}/sum`)
await page.waitForSelector('text=Sum of two numbers')

// --- read-only surface --------------------------------------------------------------------------
check('breadcrumb shows the parent dir', await page.getByText('Algoritmid').isVisible())
// .first(): the markdown source is on the page too, inside the editor.
check(
  'saved HTML renders in the preview',
  await page.getByRole('paragraph').filter({ hasText: 'Read two numbers' }).first().isVisible(),
)
check(
  'courses the exercise is used on are listed',
  await page.getByText('Programmeerimise alused').first().isVisible(),
)
check('courses with no access are counted', await page.getByText(/2 courses you have no access to/).isVisible())
check('testing tab is offered for an auto-graded exercise', await page.getByRole('tab', { name: 'Testing' }).isVisible())
await shot('01-view')

// --- edit mode ------------------------------------------------------------------------------------
await page.getByRole('button', { name: 'Edit', exact: true }).click()
// Entering edit mode re-fetches first to detect a concurrent change, so Save appearing is the
// signal that edit mode is actually on — not the click returning.
await page.getByRole('button', { name: 'Save', exact: true }).waitFor()
const titleField = page.getByLabel('Exercise title')
check('title becomes editable', await titleField.isEnabled())

await titleField.fill('Sum of two numbers v2')
const md = page.locator('.cm-content').first()
await md.click()
await page.keyboard.press('ControlOrMeta+a')
await page.keyboard.insertText('Now with **markdown**.')

check(
  'live preview replaces the saved HTML while editing',
  await waitUntil(() => page.getByText('PREVIEW:Now with **markdown**.').isVisible()),
)
check('preview heading follows the edited title', await page.getByRole('heading', { name: 'Sum of two numbers v2' }).isVisible())
await shot('02-editing')

// --- empty title blocks save ------------------------------------------------------------------
const saveBtn = page.getByRole('button', { name: 'Save', exact: true })
await titleField.fill('')
check('empty title disables Save', await waitUntil(() => saveBtn.isDisabled()))
await titleField.fill('Sum of two numbers v2')
check('restoring the title re-enables Save', await waitUntil(() => saveBtn.isEnabled()))

// --- save payload ---------------------------------------------------------------------------------
await page.getByRole('button', { name: 'Save', exact: true }).click()
await waitUntil(() => puts.length > 0)

check('save sent exactly one PUT', puts.length === 1, `puts=${puts.length}`)
if (puts.length) {
  const b = puts[0]
  check('save sends the new title', b.title === 'Sum of two numbers v2', b.title)
  check('save sends text_md', b.text_md === 'Now with **markdown**.', JSON.stringify(b.text_md))
  check(
    'save omits the legacy adoc/html fields the backend rejects',
    !('text_adoc' in b) && !('text_html' in b),
    JSON.stringify(Object.keys(b)),
  )
  check('save keeps non-TSL assets intact', (b.assets ?? []).map((a) => a.file_name).join() === 'tester.py')
}
check(
  'page leaves edit mode after saving',
  await waitUntil(() => page.getByRole('button', { name: 'Edit', exact: true }).isVisible()),
)

// --- cancel with unsaved changes asks first ------------------------------------------------------
await page.getByRole('button', { name: 'Edit', exact: true }).click()
await page.getByLabel('Exercise title').fill('Throwaway title')
confirmAnswers = [false] // decline the discard
await page.getByRole('button', { name: 'Cancel', exact: true }).click()
await waitUntil(() => confirmsSeen.length > 0)
check('declining the discard prompt stays in edit mode', await page.getByLabel('Exercise title').isEnabled())
check('the discard prompt was actually shown', confirmsSeen.some((m) => /unsaved/i.test(m)), confirmsSeen.join(' | '))

confirmAnswers = [true] // now accept
await page.getByRole('button', { name: 'Cancel', exact: true }).click()
check(
  'accepting the discard reverts the title',
  await waitUntil(async () => (await page.getByRole('heading', { name: 'Sum of two numbers v2' }).count()) === 1),
)

// --- someone else edited it: entering edit mode reloads instead --------------------------------
exercise = { ...exercise, title: 'Changed by a colleague' }
await page.getByRole('button', { name: 'Edit', exact: true }).click()
check(
  'a concurrent change blocks entering edit mode',
  await waitUntil(() => page.getByText(/Someone else has changed this exercise/).isVisible()),
)
check('and the page shows the newer version', await page.getByText('Changed by a colleague').first().isVisible())
await shot('03-concurrent-change')

// --- action dialogs --------------------------------------------------------------------------------
await page.getByRole('button', { name: 'More options' }).click()
await page.getByRole('menuitem', { name: 'Add to course' }).click()
check('add-to-course dialog opens', await page.getByRole('dialog').isVisible())
await shot('04-add-to-course')
await page.keyboard.press('Escape')
await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)

await page.getByRole('button', { name: 'More options' }).click()
await page.getByRole('menuitem', { name: 'Embedding' }).click()
const embedDialog = page.getByRole('dialog')
check('embed dialog opens', await embedDialog.isVisible())
check('embedding starts disabled for this exercise', await embedDialog.getByText('Disabled').isVisible())
const embedSwitch = embedDialog.getByRole('switch', { name: 'Allow embedding' })
await embedSwitch.click()
await waitUntil(() => patches.length > 0)
check('enabling embedding PATCHes the exercise', patches.some((p) => p.anonymous_autoassess_enabled === true))
// The switch is driven purely by server state — no optimistic update — so this also proves the
// mutation's cache invalidation actually refetches.
check(
  'the toggle reflects the saved state after the round trip',
  await waitUntil(() => embedSwitch.isChecked()),
)
check('the embed snippet appears once enabled', await embedDialog.getByText(/<iframe/).isVisible())
await shot('05-embed')
await page.keyboard.press('Escape')
await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)

await page.getByRole('button', { name: 'More options' }).click()
await page.getByRole('menuitem', { name: 'Share' }).click()
check('share dialog opens', await page.getByRole('dialog').isVisible())
await shot('06-share')
await page.keyboard.press('Escape')
await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)

// --- non-TSL container keeps the file editor ------------------------------------------------------
await page.getByRole('tab', { name: 'Auto-assessment' }).click()
check('non-TSL exercise shows the eval script tab', await page.getByRole('tab', { name: 'evaluate.sh' }).isVisible())
check('and its asset files', await page.getByRole('tab', { name: 'tester.py' }).isVisible())
check('no TSL builder for a non-TSL container', (await page.getByRole('tab', { name: 'Tests' }).count()) === 0)
await shot('07-non-tsl-autoassess')

await browser.close()
process.exit(check.summary() ? 0 : 1)
