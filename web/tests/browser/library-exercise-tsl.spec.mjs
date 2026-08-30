/**
 * Exercises the TSL editor's two-way sync: an edit in the visual builder must reach the JSON
 * spec, an edit in the JSON spec must reach the visual builder, and neither may loop.
 *
 * The compile endpoint is stubbed and every request to it recorded, so the last thing the
 * compiler was asked to build is also the thing the assertions check.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const EXERCISE_ID = '4242'
const DIR_ID = '77'

const initialSpec = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'program_execution_test',
      id: 1001,
      name: 'Adds two numbers',
      standardInputData: ['2', '3'],
      inputFiles: [],
      genericChecks: [],
      outputFileChecks: [],
      exceptionCheck: null,
    },
  ],
}

const exercise = {
  dir_id: DIR_ID,
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
  text_html: '<p>Write a program that reads two numbers and prints their sum.</p>',
  text_md: 'Write a program that reads two numbers and prints their sum.',
  // Empty string, not null: core has sent this non-nullable since changeset 020826-1, which
  // gave "no template" a single spelling. The contract check against doc/core/api-shapes.json
  // caught this fixture still describing a response core cannot produce.
  anonymous_autoassess_template: '',
  grading_script: 'cd student-submission\npython generated_0.py',
  container_image: 'tiivad:tsl-compose',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [
    { file_name: 'tsl.json', file_content: JSON.stringify(initialSpec, null, 4) },
    { file_name: 'generated_0.py', file_content: '# stale, server regenerates on save' },
  ],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

test('library-exercise-tsl', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-ex-tsl-' })

  /** Every spec the UI handed the compiler, newest last. */
  const compiled = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/preview/markdown', () => ({ content: '<p>preview</p>' })],
    ['/teacher/courses', () => ({ courses: [] })],
    [`/lib/dirs/${DIR_ID}/parents`, () => ({ parents: [{ id: DIR_ID, name: 'Algoritmid' }] })],
    [
      '/tsl/compile',
      ({ body }) => {
        compiled.push(JSON.parse(body.tsl_spec))
        return {
          scripts: [{ name: 'generated_0.py', value: '# generated from the spec\nprint("ok")' }],
          feedback: null,
          meta: {
            timestamp: '2026-08-01T12:00:00.000Z',
            compiler_version: '1.0',
            backend_id: 'tiivad',
            backend_version: '2.0',
          },
        }
      },
    ],
    [new RegExp(`/exercises/${EXERCISE_ID}(\\?|$)`), () => exercise],
  ])

  await page.goto(`${BASE_URL}/library/exercise/${EXERCISE_ID}/sum-of-two-numbers`)
  await page.waitForSelector('text=Sum of two numbers')

  // --- enter edit mode and open the TSL builder -------------------------------------------------
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByRole('tab', { name: 'Tests' }).click()

  check('TSL builder replaces the file editor', await page.getByRole('tab', { name: 'TSL', exact: true }).isVisible())
  check(
    'existing test is listed by name',
    await page.getByText('Adds two numbers', { exact: true }).isVisible(),
  )
  await shot('01-tsl-tests-tab')

  // --- direction 1: visual edit -> JSON spec ----------------------------------------------------
  await page.getByText('Adds two numbers', { exact: true }).click()
  await page.getByRole('button', { name: /Output check/i }).click()

  await page.getByLabel('Expected values').fill('5')

  // Poll rather than sleep: the model->text hop is debounced, and how long that takes on a
  // loaded runner is not something a fixed wait can predict.
  await page.getByRole('tab', { name: 'Spec', exact: true }).click()
  const sawEdit = await waitUntil(async () =>
    (await page.locator('.cm-content').first().innerText()).includes('"5"'),
  )
  check('visual edit reached the JSON spec', sawEdit)
  const specText = await page.locator('.cm-content').first().innerText()
  check('visual edit kept the existing test name', specText.includes('Adds two numbers'))
  await shot('02-spec-after-visual-edit')

  // Asserted on the compiler payload rather than the editor's text: CodeMirror only renders the
  // lines in view, so innerText silently omits the tail of a long spec.
  /**
   * Wait for a compile that **contains the edit**, not merely for a compile to exist.
   *
   * `compiled.length > 0` is satisfied instantly by a compile that was already in flight when the
   * edit was made — the model→text→compile hop is debounced — and the assertion then reads the
   * spec from *before* the edit and fails. Measured at roughly 1 run in 3 on an idle machine, and
   * `retries: 0` means each of those is a red gate.
   *
   * `library-exercise-tsl-static.spec.mjs` hit exactly this and documented it in its `afterEdit`
   * helper: "a compile already in flight when the action starts will satisfy a count check and
   * hand back the state from before the edit". The lesson was written down next door and this
   * spec still had the bug.
   */
  const sawCompiledEdit = await waitUntil(
    () => compiled.length > 0 && JSON.stringify(compiled.at(-1)).includes('"5"'),
    { timeout: 15_000 },
  )
  check(
    'compiler was sent the edited spec',
    sawCompiledEdit,
    `${compiled.length} compile(s), last: ${JSON.stringify(compiled.at(-1) ?? null).slice(0, 120)}`,
  )
  // The payload the rest of this spec reasons about. Read after the wait above, so it is the
  // compile that contains the edit rather than whatever happened to be latest a moment earlier.
  const lastCompiled = compiled.at(-1)
  const roundTripped = lastCompiled.tests[0]
  check(
    'fields the UI never shows survive the round trip',
    'outputFileChecks' in roundTripped && 'exceptionCheck' in roundTripped,
    JSON.stringify(Object.keys(roundTripped)),
  )
  check('spec-level fields survive too', lastCompiled.language === 'python3' && lastCompiled.validateFiles === true)

  // --- direction 2: JSON spec -> visual builder -------------------------------------------------
  const edited = structuredClone(lastCompiled)
  edited.tests[0].name = 'Renamed from JSON'
  edited.tests.push({
    type: 'function_execution_test',
    id: 2002,
    name: 'Calls solve()',
    functionName: 'solve',
    functionType: 'FUNCTION',
    arguments: ['1', '2'],
    standardInputData: [],
    inputFiles: [],
    genericChecks: [],
    returnValueCheck: null,
    paramValueChecks: [],
    outputFileChecks: [],
  })

  await page.locator('.cm-content').first().click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.insertText(JSON.stringify(edited, null, 4))
  await page.getByRole('tab', { name: 'TSL', exact: true }).click()
  check(
    'JSON edit renamed the test in the builder',
    await waitUntil(() => page.getByText('Renamed from JSON', { exact: true }).isVisible()),
  )
  check(
    'JSON edit added the second test to the builder',
    await page.getByText('Calls solve()', { exact: true }).isVisible(),
  )
  await shot('03-tests-after-json-edit')

  // --- no feedback loop -------------------------------------------------------------------------
  // First let the compile the JSON edit triggered actually land — counting before it arrives
  // would score a perfectly normal compile as a loop.
  await waitUntil(
    async () => {
      const before = compiled.length
      await page.waitForTimeout(1000)
      return compiled.length === before
    },
    { timeout: 15_000, interval: 0 },
  )

  const countBefore = compiled.length
  // A real sleep on purpose: this measures the *absence* of activity, which is the one thing
  // polling can't shorten. If the two directions fed each other, compiles would keep arriving.
  await page.waitForTimeout(2000)
  check(
    'idle UI stops compiling (no sync loop)',
    compiled.length === countBefore,
    `compiles went ${countBefore} -> ${compiled.length} while idle`,
  )

  // --- generated scripts tab --------------------------------------------------------------------
  await page.getByRole('tab', { name: 'Generated scripts' }).click()
  check(
    'generated scripts come from the compiler',
    await waitUntil(async () =>
      (await page.locator('.cm-content').first().innerText()).includes('generated from the spec'),
    ),
  )
  await shot('04-generated-scripts')

  // --- a spec the compiler rejects blocks Save ---------------------------------------------------
  await page.getByRole('tab', { name: 'Spec', exact: true }).click()
  await page.locator('.cm-content').first().click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.insertText('{ this is not json')

  const saveBtn = page.getByRole('button', { name: /^Sav(e|ing)/ })
  check('broken JSON disables Save', await waitUntil(() => saveBtn.isDisabled()))
  await shot('05-parse-error')

  await close()
})
