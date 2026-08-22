/**
 * Maths in exercise text: that KaTeX is loaded and typesets what core marked up (EZ-1732).
 *
 * This is the half of the feature that cannot be unit-tested. `MarkdownMathTest` in core pins the
 * markup — that `$x^2$` becomes a `data-easy-tex` span and that `$5 and $6` does not — and the
 * *parsing* is entirely covered there. What is left is browser-only by nature: a dynamic import
 * resolving, a stylesheet arriving with its fonts, and KaTeX finding elements that React inserted
 * with `dangerouslySetInnerHTML`. Each of those fails silently and leaves the page showing `$x^2$`,
 * which is precisely the bug this feature exists to fix — so a passing suite with no browser check
 * here would be a suite that cannot see the regression.
 *
 * The fixture HTML below is copied verbatim from `MarkdownService.mdToHtml` output rather than
 * hand-written, so a change to the attribute names on either side shows up as a failure here and in
 * core's test together.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ID = '4343'
const DIR = '78'

/**
 * What core emits for:
 *
 *     Inline $x^2$ here.
 *
 *     $$
 *     \frac{a}{b}
 *     $$
 *
 *     Costs $5 and $6.
 */
const MATH_HTML =
  '<p>Inline <span class="easy-math" data-easy-math="inline" data-easy-tex="x^2">$x^2$</span> here.</p>\n' +
  '<div class="easy-math" data-easy-math="display" data-easy-tex="\\frac{a}{b}">$$\\frac{a}{b}$$</div>\n' +
  '<p>Costs $5 and $6.</p>'

/**
 * The preview stub answers with a *different* formula from `text_html`, and that is the point.
 * With the same string in both, the preview check passed without the preview working at all: the
 * html prop never changed, the effect never re-ran, and the two `.katex` nodes it counted were the
 * ones the read-only view had already rendered.
 */
const PREVIEW_HTML =
  '<p>Preview <span class="easy-math" data-easy-math="inline" data-easy-tex="\\sqrt{2}">$\\sqrt{2}$</span> only.</p>'

const EXERCISE = {
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
  title: 'Quadratic formula',
  text_html: MATH_HTML,
  text_md: 'Inline $x^2$ here.\n\n$$\n\\frac{a}{b}\n$$\n\nCosts $5 and $6.',
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

test('markdown-math', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'md-math-' })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/preview/markdown', () => ({ content: PREVIEW_HTML })],
    ['/teacher/courses', () => ({ courses: [] })],
    [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Algebra' }] })],
    [new RegExp(`/exercises/${ID}(\\?|$)`), () => EXERCISE],
  ])

  await page.goto(`${BASE_URL}/library/exercise/${ID}/quadratic`)
  await page.waitForSelector('text=Quadratic formula')

  // --- the formulae are typeset ------------------------------------------------------------------
  // Polled, not asserted immediately: KaTeX arrives by dynamic import, so there is a tick between
  // the HTML being in the DOM and the maths being rendered. Asserting on the next frame would be
  // green or red depending on the machine.
  const katexCount = () => page.locator('.katex').count()
  check('KaTeX renders the formulae', await waitUntil(async () => (await katexCount()) >= 2))

  const inline = page.locator('[data-easy-math="inline"]')
  const display = page.locator('[data-easy-math="display"]')

  check('inline maths is typeset', (await inline.locator('.katex').count()) === 1)
  check('displayed maths is typeset', (await display.locator('.katex-display').count()) === 1)

  // The delimiters are the fallback text. Once KaTeX has run they must be gone, or the reader sees
  // the formula *and* its source — which is how a half-working version of this would look.
  check('the inline delimiters are replaced', !(await inline.innerText()).includes('$'))
  check('the display delimiters are replaced', !(await display.innerText()).includes('$'))

  // MathML alongside the visual output, which is the only thing a screen reader can read. KaTeX
  // drops it if `output` is set to html, so this is worth pinning rather than assuming.
  check('the formula is exposed as MathML', (await page.locator('.katex math').count()) >= 2)

  // The TeX stays on the element after rendering, so a re-render can tell what is already done.
  check(
    'the TeX survives rendering',
    (await inline.getAttribute('data-easy-tex')) === 'x^2',
  )

  // --- and prose that merely contains dollars is left as prose -----------------------------------
  // Core decides this, and MarkdownMathTest proves it; checked here too because a client-side
  // delimiter scan is the obvious "improvement" someone will add later, and this is the check that
  // would catch it turning a price list into a formula.
  // Scoped to the rendered paragraph: the same text is also sitting in the Markdown source pane's
  // CodeMirror, and an unscoped getByText matches both.
  check(
    'currency is still currency',
    (
      await page.getByRole('paragraph').filter({ hasText: 'Costs $5' }).first().innerText()
    ).includes('$5 and $6'),
  )
  check('and was not typeset', (await page.locator('.katex').count()) === 2)
  await shot('01-rendered')

  // --- the toolbar can insert maths --------------------------------------------------------------
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).waitFor()

  const toolbar = page.getByRole('toolbar', { name: 'Formatting' })
  check('the toolbar offers a formula button', await toolbar.getByRole('button', { name: 'Formula', exact: true }).isVisible())

  const editor = page.locator('.cm-content').first()
  const docText = () => editor.innerText()

  async function clearEditor() {
    await editor.click()
    await page.keyboard.press('ControlOrMeta+a')
    await page.keyboard.press('Backspace')
  }

  await clearEditor()
  await toolbar.getByRole('button', { name: 'Formula', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Formula in the text' }).click()
  check('the inline item inserts delimited TeX', (await docText()).includes('$x^2$'))

  await clearEditor()
  await page.keyboard.type('Lead-in line')
  await toolbar.getByRole('button', { name: 'Formula', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Formula on its own line' }).click()
  const withBlock = await docText()
  check('the display item inserts a fence', withBlock.includes('$$'))
  check('and keeps the paragraph above it', withBlock.includes('Lead-in line'))

  // Acts on the selection, like every other button on this toolbar. Appending a placeholder block
  // instead would leave the teacher deleting the text they had just selected.
  await clearEditor()
  await page.keyboard.type('\\frac{a}{b}')
  await page.keyboard.press('ControlOrMeta+a')
  await toolbar.getByRole('button', { name: 'Formula', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Formula on its own line' }).click()
  const fenced = await docText()
  check('the display item fences a selection rather than ignoring it', fenced.includes('$$\n\\frac{a}{b}\n$$'))
  check('and does not leave a placeholder behind', !fenced.includes('x^2'))

  // Same trap as the heading and image menus: MUI's focus trap hands focus back to the button as
  // the menu closes, after the insert has already focused the editor.
  check(
    'the formula menu leaves the caret in the editor',
    await waitUntil(() =>
      page.evaluate(() => document.activeElement?.classList?.contains('cm-content') === true),
    ),
  )

  // --- the live preview typesets too -------------------------------------------------------------
  // The editing pane is where a teacher finds out whether their formula parses, so it matters more
  // than the read-only view: a preview that showed raw TeX would send them to save-and-reload to
  // check every formula.
  // Asserted on the preview's *own* formula, which appears nowhere in `text_html` — so this can
  // only pass if the preview response was fetched, inserted and typeset.
  const previewMath = page.locator('[data-easy-tex="\\\\sqrt{2}"]')
  check(
    'the preview pane typesets its own formula',
    await waitUntil(async () => (await previewMath.locator('.katex').count()) === 1),
  )
  check('and the preview delimiters are replaced', !(await previewMath.innerText()).includes('$'))
  await shot('02-preview')
  await close()

  // --- and when KaTeX cannot be loaded at all ----------------------------------------------------
  // The floor the whole design rests on: the element text is the original `$x^2$`, so a failed
  // import degrades to what students saw before this feature existed rather than to a blank space.
  // Worth a real check — every other part of this is written to fail quietly, so "it degrades
  // gracefully" is exactly the claim that could be false for a year without anyone noticing.
  const offline = await launch({ role: 'teacher,admin', shotPrefix: 'md-math-nokatex-' })

  await offline.page.route('**/*katex*', (route) => route.abort())
  await fakeApi(offline.page, [
    ['/account/checkin', () => ({})],
    ['/preview/markdown', () => ({ content: PREVIEW_HTML })],
    ['/teacher/courses', () => ({ courses: [] })],
    [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Algebra' }] })],
    [new RegExp(`/exercises/${ID}(\\?|$)`), () => EXERCISE],
  ])

  await offline.page.goto(`${BASE_URL}/library/exercise/${ID}/quadratic`)
  await offline.page.waitForSelector('text=Quadratic formula')

  const inlineOffline = offline.page.locator('[data-easy-math="inline"]')
  check('nothing is typeset when the chunk will not load', (await offline.page.locator('.katex').count()) === 0)
  check('and the TeX source is still readable', (await inlineOffline.innerText()).includes('$x^2$'))
  await offline.shot('03-katex-blocked')

  await offline.close()
})
