/**
 * The library exercise page's non-TSL behaviour: edit mode, the save payload, the
 * concurrent-edit guards, the action dialogs, and the raw-JSON fallback for TSL test types that
 * have no form.
 *
 * Backend is entirely faked here — the point is the page's own logic. `library-exercise-tsl-live`
 * covers the parts that need a real core.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

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
  anonymous_autoassess_template: 'a = 1\n',
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

test('library-exercise-ui', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-ex-ui-' })

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
    // The embed dialog's preview iframe loads the real embed page, which fetches this.
    ['anonymous/details', () => ({
      title: exercise.title,
      text_html: exercise.text_html,
      anonymous_autoassess_template: exercise.anonymous_autoassess_template,
      submit_allowed: true,
    })],
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
          // Merge only what was sent, mirroring the backend's `req.field?.let { ... }`. Assigning
          // every field unconditionally set the untouched one to undefined, which turned a
          // controlled switch uncontrolled and unmounted half the dialog.
          if (body.anonymous_autoassess_enabled !== undefined) {
            exercise = { ...exercise, is_anonymous_autoassess_enabled: body.anonymous_autoassess_enabled }
          }
          if (body.anonymous_autoassess_template !== undefined) {
            exercise = { ...exercise, anonymous_autoassess_template: body.anonymous_autoassess_template }
          }
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
  // Through the app's own dialog since the X-017 fix; window.confirm here would be a regression.
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByLabel('Exercise title').fill('Throwaway title')
  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  check(
    'the discard prompt was actually shown — as the app dialog, not window.confirm',
    (await waitUntil(() => page.getByRole('heading', { name: 'Unsaved changes' }).isVisible())) && confirmsSeen.length === 0,
    confirmsSeen.join(' | '),
  )
  await page.getByRole('button', { name: 'Keep editing' }).click()
  await waitUntil(async () => (await page.getByRole('heading', { name: 'Unsaved changes' }).count()) === 0)
  check('declining the discard prompt stays in edit mode', await page.getByLabel('Exercise title').isEnabled())

  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  await waitUntil(() => page.getByRole('heading', { name: 'Unsaved changes' }).isVisible())
  await page.getByRole('button', { name: 'Discard', exact: true }).click()
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
  // Polled, not asserted on the next tick: the snackbar appears as soon as the comparison fails,
  // but the newer title only lands once the refetch behind it resolves. Asserting immediately made
  // this pass or fail on how quickly the stubbed request came back.
  check(
    'and the page shows the newer version',
    await waitUntil(() => page.getByText('Changed by a colleague').first().isVisible()),
  )
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

  // The generated URL is wui's scheme, and it is asserted literally because embeds published in
  // PmWiki pages carry it. All three parts of this were wrong before: singular `exercise` in the
  // path, `showTitle=true`-style parameters the page never reads, and an @iframe-resizer CDN script
  // for a protocol the page does not speak.
  // Picked by content, not position: the dialog now has two CodeEditors — the starting-code editor
  // comes first in the DOM, so `.first()` silently read the wrong one and every negative assertion
  // ("does not contain iframe-resizer") passed against a document that could never contain it.
  const snippetText = () =>
    embedDialog.locator('.cm-content').filter({ hasText: '<iframe' }).first().innerText()
  let snippet = await snippetText()
  check('snippet uses the plural /embed/exercises/ path', snippet.includes(`/embed/exercises/${ID}/`))
  check('snippet points at our own resizer script', snippet.includes('/static/js/ez-embed-frame-resizer.js'))
  check('and not at the iframe-resizer CDN', !snippet.includes('iframe-resizer'))
  check('a default embed carries no query string at all', !snippet.includes(`${ID}/Sum%20of%20two%20numbers?`))

  // Flags are negative and valueless, so each switch *adds* one when turned off.
  await embedDialog.getByRole('switch', { name: 'Title' }).click()
  snippet = await waitUntil(async () => {
    const s = await snippetText()
    return s.includes('no-title') ? s : null
  })
  check('turning the title off adds no-title', Boolean(snippet))

  // Allow testing is the one positive flag — and the one that silently did nothing before.
  await embedDialog.getByRole('switch', { name: 'Allow submitting and testing' }).click()
  snippet = await waitUntil(async () => {
    const s = await snippetText()
    return s.includes('submit') ? s : null
  })
  check('allow testing adds the submit flag', Boolean(snippet))
  check(
    'and no showSubmit=true, which the page never read',
    !(await snippetText()).includes('showSubmit'),
  )

  // The template switch is meaningless without the editor, which only exists when testing is on.
  const templateSwitch = embedDialog.getByRole('switch', { name: 'Starting code' })
  check('the template switch is enabled once testing is on', await templateSwitch.isEnabled())

  // Value-carrying options, appended after the bare flags.
  // Title was switched off above, and an override for a hidden title is meaningless — the field is
  // disabled to say so, which is also why it has to go back on before this can be typed into.
  check(
    'the title override is disabled while the title is hidden',
    await embedDialog.getByLabel('Title override').isDisabled(),
  )
  await embedDialog.getByRole('switch', { name: 'Title' }).click()
  await embedDialog.getByLabel('Title override').fill('Warm-up')
  snippet = await waitUntil(async () => {
    const s = await snippetText()
    return s.includes('title-alias=Warm-up') ? s : null
  })
  check('a title override adds title-alias', Boolean(snippet))

  // From the library there is no course to preselect, so the toggle starts off and the dropdown
  // with it — the switch is how you opt into a link at all.
  const courseSelect = embedDialog.getByRole('combobox', { name: 'Course' })
  check('the course dropdown is disabled until the link toggle is on', await courseSelect.isDisabled())
  await embedDialog.getByRole('switch', { name: 'Link to course' }).click()
  check('the toggle alone changes nothing in the url', !(await snippetText()).includes('course='))
  await courseSelect.click()
  await page.getByRole('option', { name: 'Programmeerimise alused' }).click()
  snippet = await waitUntil(async () => {
    const s = await snippetText()
    return s.includes('course=9006') ? s : null
  })
  check('linking a course adds course=', Boolean(snippet))
  check('and the course exercise id', (await snippetText()).includes('exercise=55'))

  // Turning the link off clears the selection, so the url loses both parameters.
  await embedDialog.getByRole('switch', { name: 'Link to course' }).click()
  check(
    'turning the link off clears it from the url',
    await waitUntil(async () => !(await snippetText()).includes('course=')),
  )
  await embedDialog.getByRole('switch', { name: 'Link to course' }).click()

  // The starting code lives on the exercise and autosaves through PATCH — this dialog is the only
  // place in the app that can set it. No Save button: the editor debounces and writes by itself.
  const templateEditor = embedDialog.locator('.cm-content').filter({ hasNotText: '<iframe' }).first()
  // The toggle seeds from whether the exercise actually has starting code, so an exercise with a
  // template opens with the switch on and the editor showing.
  check('the starting code toggle reflects an exercise that has one',
    await embedDialog.getByRole('switch', { name: 'Starting code' }).isChecked())
  check('the starting code editor appears under its toggle', await templateEditor.isVisible())
  await templateEditor.click()
  await page.keyboard.type('print()')
  // Typed into an editor that already holds the exercise's template, so this appends rather than
  // replaces — the assertion is about the autosave firing, not about the exact text.
  check(
    'typing autosaves it',
    await waitUntil(() => patches.some((p) => p.anonymous_autoassess_template?.includes('print()'))),
  )

  // The whole point of making the column non-nullable: emptying the editor is a real "no template"
  // state. When it was nullable, absent meant "leave alone" and no value meant "remove".
  await templateEditor.click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.press('Backspace')
  check(
    'clearing autosaves an empty string, not null',
    await waitUntil(() => patches.some((p) => p.anonymous_autoassess_template === '')),
  )

  // The toggle is not a snippet flag: switching it off means the exercise has no starting code, so
  // it clears the stored value and takes the editor away. Nothing about it reaches the URL.
  await templateEditor.click()
  await page.keyboard.type('print()')
  await waitUntil(() => patches.some((p) => p.anonymous_autoassess_template === 'print()'))
  await embedDialog.getByRole('switch', { name: 'Starting code' }).click()
  check('switching the toggle off hides the editor', await waitUntil(async () => !(await templateEditor.isVisible())))
  check(
    'and clears the stored starting code',
    await waitUntil(() => patches.at(-1)?.anonymous_autoassess_template === ''),
  )
  check('and never puts no-template in the url', !(await snippetText()).includes('no-template'))
  await embedDialog.getByRole('switch', { name: 'Starting code' }).click()
  check('switching it back on opens an empty editor', await waitUntil(async () =>
    (await templateEditor.isVisible()) && !(await templateEditor.innerText()).includes('print()')))

  // The preview is a real iframe at the generated src, so it cannot drift from what gets pasted.
  const previewFrame = embedDialog.locator('iframe')
  check('a preview iframe is shown', (await previewFrame.count()) === 1)
  const previewSrc = await previewFrame.getAttribute('src')
  check('the preview points at the same URL as the snippet', (await snippetText()).includes(previewSrc))

  // And that it actually rendered — the app has to boot inside the frame, so asserting the element
  // exists proves nothing. Waiting here also keeps the screenshot from catching an empty box.
  const preview = embedDialog.frameLocator('iframe')
  check(
    'the preview renders the exercise',
    await waitUntil(() => preview.getByText('Read two numbers').isVisible().catch(() => false)),
  )
  check(
    'and honours no-title, which is switched off above',
    (await preview.getByRole('heading', { name: 'Sum of two numbers' }).count()) === 0,
  )
  // Snippet options are remembered — embedding a run of exercises into one page means opening this
  // dialog repeatedly and answering the same way each time. Exercise-specific fields are not
  // remembered, because carrying one exercise's title override to the next would be nonsense.
  check('the copy button is the emphasised action', 
    (await embedDialog.getByRole('button', { name: /Copy/ }).getAttribute('class'))?.includes('MuiButton-contained') === true)
  check('and closing is an X in the title bar, not a footer button',
    await embedDialog.getByRole('button', { name: 'Close' }).isVisible())

  const aliasBefore = await embedDialog.getByLabel('Title override').inputValue()
  await embedDialog.getByRole('button', { name: 'Close' }).click()
  await waitUntil(async () => (await page.getByRole('dialog').count()) === 0)
  await page.getByRole('button', { name: 'More options' }).click()
  await page.getByRole('menuitem', { name: 'Embedding' }).click()
  const reopened = page.getByRole('dialog')
  await reopened.waitFor()
  // Asserted on "allow testing", whose default is off and which this run turned on — checking a
  // value that matches the default would pass whether or not anything was remembered.
  check(
    'reopening keeps the snippet options',
    (await reopened.getByRole('switch', { name: 'Allow submitting and testing' }).isChecked()) === true,
  )
  // Reopening the *same* exercise keeps the override — you were part-way through a task. It cannot
  // follow you to another exercise, because the dialog is keyed by exercise id at the call site.
  check(
    'and keeps the title override for the same exercise',
    (await reopened.getByLabel('Title override').inputValue()) === aliasBefore && aliasBefore !== '',
  )
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

  // --- the settings are a summary until you edit -----------------------------------------------------
  // Five labelled inputs pushed the file editor most of a screen down for values that are set once.
  // Reading them still has to work, so the summary carries all five; editing still has to work, so
  // the inputs come back with Edit. No collapsible in between — the mode already says which is wanted.
  const settingsLine = () => page.locator('p').filter({ hasText: 'lahendus.py' }).first().innerText()
  check('the settings are one line while viewing', await waitUntil(async () => (await settingsLine()).includes('·')))
  for (const value of ['lahendus.py', 'Text editor', 'Python Grader', '7 s', '30 MB']) {
    check(`the summary carries ${value}`, (await settingsLine()).includes(value))
  }
  check(
    'and there is no labelled input to distract from the script',
    (await page.getByRole('textbox', { name: 'Max time (s)' }).count()) === 0,
  )

  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  check(
    'editing brings the real fields back',
    await waitUntil(() => page.getByRole('textbox', { name: 'Max time (s)' }).isVisible()),
  )
  check(
    'with the values, not the container template defaults',
    (await page.getByRole('textbox', { name: 'Max time (s)' }).inputValue()) === '7',
  )
  check(
    'and they are editable',
    !(await page.getByRole('textbox', { name: 'Solution file name' }).isDisabled()),
  )

  // The container note was hardcoded Estonian and rendered untranslated in the English UI. Asserting
  // the Estonian is *absent* is the half that matters: `fallbackLng` is `et`, so a key missing from
  // en.json silently reproduces the original bug, and a check for English text alone would still see
  // something plausible on screen.
  const helpText = await page.getByText(/Python Grader/).last().innerText()
  check('the container note is translated', helpText.includes('not recommended for new auto-assessments'))
  check('and does not fall back to Estonian', !helpText.includes('ei soovita'))
  check(
    'the summary steps aside rather than duplicating them',
    (await page.locator('p').filter({ hasText: 'lahendus.py · Text editor' }).count()) === 0,
  )
  await shot('08-autoassess-editing')

  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  check(
    'leaving edit mode returns to the summary',
    await waitUntil(async () => (await settingsLine()).includes('Python Grader')),
  )

  await close()
})
