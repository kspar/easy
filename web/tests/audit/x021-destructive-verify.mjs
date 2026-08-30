/**
 * Verification driver for the X-021 fix: the TSL builder's two destructive one-click edits get
 * the treatments the audit prescribed — deletion is undoable from a snackbar, a type switch asks
 * first, and only when the body actually carries something a switch would destroy.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x021-destructive-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil, SPEC_TAB, BUILDER_TAB, AUTO_ASSESS_TAB } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const TSL_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'program_execution_test',
      id: 111,
      name: 'Minu täidetud test',
      standardInputData: ['2', '3'],
      inputFiles: [],
      genericChecks: [
        {
          id: 1,
          checkType: 'ALL_OF_THESE',
          expectedValue: ['5'],
          elementsOrdered: false,
          dataCategory: 'CONTAINS_STRINGS',
          beforeMessage: '',
          passedMessage: 'ok',
          failedMessage: 'fail',
        },
      ],
      outputFileChecks: [],
      exceptionCheck: null,
    },
    { type: 'placeholder_test', id: 222, name: null },
  ],
}

const tslExercise = () => ({
  dir_id: 'root',
  effective_access: 'PRAWM',
  created_at: '2026-08-23T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z',
  last_modified_by_id: 'kspar',
  anonymous_autoassess_template: '',
  executors: null,
  on_courses: [],
  on_courses_no_access: 0,
  title: 'Kahe arvu summa',
  text_html: '<p>Liida kaks arvu.</p>',
  text_md: 'Liida kaks arvu.',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  grader_type: 'AUTO',
  grading_script: 'cd student-submission\npython generated_0.py',
  container_image: 'tiivad:tsl-compose',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [
    { file_name: 'tsl.json', file_content: JSON.stringify(TSL_SPEC, null, 4) },
    { file_name: 'generated_0.py', file_content: '# generated\n' },
  ],
})

async function openEditing(launch) {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      ['/tsl/compile', () => ({
        scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
        meta: { timestamp: '2026-08-23T10:00:00.000Z', compiler_version: '4.0', backend_id: 'tiivad', backend_version: '0.0.33' },
        feedback: null,
      })],
      [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => tslExercise()],
      [/\/lib\/dirs\/[^/]+\/parents/, () => ({ parents: [] })],
      [/\/lib\/dirs\//, () => ({ dirs: [], exercises: [] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], dirs: [], count: 0 })],
    ],
    { log: false, contract: false },
  )
  await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/kahe-arvu-summa`)
  await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
  await page.getByRole('button', { name: /^Muuda/i }).first().click()
  await page.getByRole('button', { name: /^Salvesta/i }).waitFor({ timeout: 10000 })
  await page.getByRole('tab', { name: AUTO_ASSESS_TAB }).first().click()
  await page.waitForTimeout(800)
  return page
}

/** Change the expanded card's test type via its first combobox. */
async function pickType(page, optionRe) {
  const combos = page.locator('main [role="combobox"]')
  const n = await combos.count()
  for (let i = 0; i < n; i++) {
    await combos.nth(i).click()
    await page.waitForTimeout(300)
    const opt = page.getByRole('option', { name: optionRe }).first()
    if (await opt.count()) {
      await opt.click()
      return true
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(150)
  }
  return false
}

await withBrowser(async ({ launch }) => {
  // ─── 1. deletion: undoable from the snackbar, not silent and final ──────────────────────────────
  {
    const page = await openEditing(launch)
    // The kebab on the test's own card — the page header carries one with the same label.
    const card = page.locator('div.MuiPaper-root', { hasText: 'Minu täidetud test' }).first()
    await card.getByRole('button', { name: /Rohkem valikuid/i }).click()
    await page.waitForTimeout(300)
    await page.getByRole('menuitem', { name: /Kustuta/i }).first().click()
    await page.waitForTimeout(400)

    check((await page.getByText('Minu täidetud test').count()) === 0, 'the test is gone after delete')
    check((await page.getByText('Test kustutatud').count()) > 0, 'the snackbar says so')
    const undo = page.getByRole('button', { name: /Võta tagasi/ })
    check((await undo.count()) > 0, 'and offers undo')
    await undo.click()
    await page.waitForTimeout(400)
    check((await page.getByText('Minu täidetud test').count()) > 0, 'undo brings the test back')
    await page.close()
  }

  // ─── 2. a type switch on a filled body asks first; cancel keeps everything ──────────────────────
  {
    const page = await openEditing(launch)
    // Expand the filled test's card (title click toggles it).
    await page.getByText('Minu täidetud test').first().click()
    await page.waitForTimeout(600)

    check(await pickType(page, /^Funktsiooni käivituse test$/i), 'found the type select')
    await page.waitForTimeout(400)
    check((await page.getByText(/tühjendab kõik täidetud väljad/).count()) > 0, 'the filled body gets a confirm')

    await page.getByRole('button', { name: /Tühista/i }).last().click()
    await page.waitForTimeout(400)
    const spec = await page.evaluate(() => document.body.innerText)
    check(!spec.includes('tühjendab kõik'), 'cancel closes the dialog')
    // The body survived: the stdin values are still in the spec tab.
    await page.getByRole('tab', { name: SPEC_TAB }).first().click()
    await page.waitForTimeout(400)
    const json = await page.locator('.cm-content').first().innerText()
    check(json.includes('"2"') && json.includes('program_execution_test'), 'cancel kept the body')

    // Back to tests; confirm path this time.
    await page.getByRole('tab', { name: BUILDER_TAB }).first().click()
    await page.waitForTimeout(400)
    check(await pickType(page, /^Funktsiooni käivituse test$/i), 'found the type select again')
    await page.waitForTimeout(400)
    await page.getByRole('button', { name: /Vaheta tüüpi/ }).click()
    await page.waitForTimeout(600)
    await page.getByRole('tab', { name: SPEC_TAB }).first().click()
    await page.waitForTimeout(400)
    const json2 = await page.locator('.cm-content').first().innerText()
    check(
      json2.includes('function_execution_test') && !json2.includes('"standardInputData": [\n                "2"'),
      'confirming performs the switch and clears the body',
    )
    check(json2.includes('Minu täidetud test'), 'the hand-set name survives the switch, as before')
    await page.close()
  }

  // ─── 3. a pristine body switches silently — the confirm is not a tax on every switch ────────────
  {
    const page = await openEditing(launch)
    // The placeholder test has no body; expand it.
    await page.getByText(/Uus test/i).first().click()
    await page.waitForTimeout(600)
    check(await pickType(page, /Koodi sisu test/i), 'found the placeholder card type select')
    await page.waitForTimeout(400)
    check((await page.getByText(/tühjendab kõik täidetud väljad/).count()) === 0, 'no confirm for an empty body')
    await page.close()
  }
})

console.log(failures === 0 ? '\nX-021 verification: all checks passed' : `\nX-021 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
