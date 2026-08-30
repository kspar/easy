/**
 * Verification driver for the TSL silent-failure chain fixes (EZ-1795):
 *
 *  X-015 — choosing TSL seeds a valid empty spec (from the real solution file name), so the first
 *          render is the empty state with Save enabled, not a kotlinx error with Save dead;
 *  X-016 — the Testimine tab follows the *edited* grader type, with a save-first alert until the
 *          exercise has actually been saved with auto-assessment;
 *  X-022 — an invalid spec no longer locks Save after the container stops being TSL;
 *  X-023 — a test that checks nothing gets a warning chip and a summary line, and still saves;
 *  X-027 — a blank required field gets an error chip and gates Save.
 *
 * Asserting, like the other x-*-verify drivers.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x-tsl-chain-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const baseExercise = () => ({
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
  // Deliberately not the default name, so the seeded requiredFiles is provably from the
  // exercise rather than from emptySpec's fallback.
  solution_file_name: 'minu_lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
})

const teacherExercise = () => ({
  ...baseExercise(),
  grader_type: 'TEACHER',
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: null,
})

const TSL_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['minu_lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'program_execution_test',
      id: 111,
      name: null,
      standardInputData: [],
      inputFiles: [],
      genericChecks: [{ id: 1, checkType: 'ALL_OF_THESE', expectedValue: ['5'], elementsOrdered: false, dataCategory: 'CONTAINS_STRINGS', beforeMessage: '', passedMessage: 'ok', failedMessage: 'fail' }],
      outputFileChecks: [],
      exceptionCheck: null,
    },
  ],
}

const tslExercise = () => ({
  ...baseExercise(),
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

async function open(launch, exercise, compileBodies) {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      ['/tsl/compile', ({ body }) => {
        compileBodies.push(body)
        return {
          scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
          meta: { timestamp: '2026-08-23T10:00:00.000Z', compiler_version: '4.0', backend_id: 'tiivad', backend_version: '0.0.33' },
          feedback: null,
        }
      }],
      [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => exercise],
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
  await page.getByRole('tab', { name: /Automaatkontroll/i }).first().click()
  await page.waitForTimeout(600)
  return page
}

const saveEnabled = (page) => page.getByRole('button', { name: /^Salvesta/i }).isEnabled()

await withBrowser(async ({ launch }) => {
  // ─── X-015 + X-016: first contact with TSL ──────────────────────────────────────────────────────
  {
    const compileBodies = []
    const page = await open(launch, teacherExercise(), compileBodies)

    await page.getByLabel(/Automaatkontrolli tüüp/i).click()
    await page.getByRole('option', { name: /^TSL$/i }).click()
    await page.waitForTimeout(1800) // past the 400ms parse + 800ms compile debounces

    check((await page.getByText('Teste veel pole.').count()) > 0, 'the first render is the empty state')
    check((await page.getByRole('alert').count()) === 0, 'no error alert greets the teacher')
    check(await saveEnabled(page), 'Save is enabled on a fresh TSL choice')
    const lastCompile = compileBodies[compileBodies.length - 1]
    check(
      typeof lastCompile?.tsl_spec === 'string' && lastCompile.tsl_spec.includes('"minu_lahendus.py"'),
      `the compiled spec is the seeded one, requiredFiles from the real solution file (${JSON.stringify(lastCompile?.tsl_spec?.slice(0, 60))}…)`,
    )
    check((await page.getByText(/Testid koostatakse allpool/).count()) > 0, 'the TSL choice now explains itself')

    // X-016: the Testimine tab exists before saving, and says to save first.
    const testingTab = page.getByRole('tab', { name: /Testimine/i })
    check((await testingTab.count()) > 0, 'the Testimine tab appears without a round-trip through Save')
    await testingTab.click()
    await page.waitForTimeout(400)
    check(
      (await page.getByText(/pole veel automaatkontrolliga salvestatud/).count()) > 0,
      'and explains that runs need a saved version first',
    )
    await page.getByRole('tab', { name: /Automaatkontroll/i }).click()
    await page.waitForTimeout(400)

    // ─── X-023: the first preset produces a test that checks nothing — now it says so ───────────
    await page.getByRole('button', { name: /Lisa test/i }).click()
    await page.getByRole('menuitem', { name: /Käivitab programmi/i }).click()
    await page.waitForTimeout(600)
    check((await page.getByText('Ei kontrolli midagi').count()) > 0, 'the checkless test carries a warning chip')
    check((await page.getByText(/ei kontrolli midagi ja läbitakse alati/).count()) > 0, 'and the summary line says so')
    check(await saveEnabled(page), 'a checkless test still saves — the teacher may be mid-build')

    // ─── X-027: a blank required field gates Save ────────────────────────────────────────────────
    await page.getByRole('button', { name: /Lisa test/i }).click()
    await page.getByRole('menuitem', { name: /Kutsub välja funktsiooni/i }).first().click()
    await page.waitForTimeout(800)
    check((await page.getByText('Kohustuslik väli täitmata').count()) > 0, 'the blank functionName carries an error chip')
    check(!(await saveEnabled(page)), 'and Save is gated on it')
    await page.getByLabel(/Funktsiooni nimi/i).first().fill('liida')
    await page.waitForTimeout(1800)
    check((await page.getByText('Kohustuslik väli täitmata').count()) === 0, 'filling the name clears the chip')
    check(await saveEnabled(page), 'and Save re-enables')
    await page.close()
  }

  // ─── X-027, without the editor: the gate is derived from the draft, not from TslEditor ─────────
  // An exercise whose saved spec has a blank required name, edited entirely from the Text tab:
  // the TSL editor never mounts, so a gate that trusted its report would wave this through.
  {
    const compileBodies = []
    const brokenSpec = {
      ...TSL_SPEC,
      tests: [{
        type: 'function_execution_test',
        id: 7,
        name: null,
        functionName: '',
        functionType: 'FUNCTION',
        createObject: null,
        arguments: [],
        standardInputData: [],
        inputFiles: [],
        genericChecks: [],
        returnValueCheck: null,
        paramValueChecks: [],
        outputFileChecks: [],
      }],
    }
    const exercise = {
      ...tslExercise(),
      assets: [
        { file_name: 'tsl.json', file_content: JSON.stringify(brokenSpec, null, 4) },
        { file_name: 'generated_0.py', file_content: '# generated\n' },
      ],
    }
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(page, [
      ...baseHandlers(),
      ['/tsl/compile', ({ body }) => { compileBodies.push(body); return { scripts: [], meta: null, feedback: null } }],
      [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => exercise],
      [/\/lib\/dirs\/[^/]+\/parents/, () => ({ parents: [] })],
      [/\/lib\/dirs\//, () => ({ dirs: [], exercises: [] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], dirs: [], count: 0 })],
    ], { log: false, contract: false })
    await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/kahe-arvu-summa`)
    await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
    await page.getByRole('button', { name: /^Muuda/i }).first().click()
    await page.getByRole('button', { name: /^Salvesta/i }).waitFor({ timeout: 10000 })
    // Stay on the Text tab; rename only.
    await page.getByLabel(/Pealkiri/i).first().fill('Kahe arvu summa v2')
    await page.waitForTimeout(400)
    check(
      !(await saveEnabled(page)),
      'a blank required field in the saved spec gates Save even when the TSL editor never mounts',
    )
    await page.close()
  }

  // ─── X-022: an invalid spec no longer locks Save after the container changes away ──────────────
  {
    const compileBodies = []
    const page = await open(launch, tslExercise(), compileBodies)

    await page.getByRole('tab', { name: /^Spec$/i }).first().click()
    await page.waitForTimeout(600)
    await page.locator('.cm-content').first().click()
    await page.keyboard.press('End')
    await page.keyboard.type('}{{')
    await page.waitForTimeout(1200)
    check(!(await saveEnabled(page)), 'broken JSON disables Save while the container is TSL')

    await page.getByLabel(/Automaatkontrolli tüüp/i).click()
    await page.getByRole('option', { name: /^–$/ }).click()
    await page.waitForTimeout(600)
    check(await saveEnabled(page), 'switching auto-assessment off re-enables Save — the stale flag no longer sticks')
    await page.close()
  }
})

console.log(failures === 0 ? '\nTSL chain verification: all checks passed' : `\nTSL chain verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
