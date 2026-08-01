/**
 * The TSL editor against the **real** compiler on localhost:8080.
 *
 * Everything else is still faked, but `/v2/tsl/compile` is allowed through the Vite proxy, so
 * this answers the question a stub cannot: does the JSON the visual builder produces actually
 * decode on the Kotlin side? kotlinx.serialization runs with `ignoreUnknownKeys = false`, so an
 * invented or misspelled key is a hard error rather than something that quietly survives.
 *
 * Requires a core running with `easy.core.auth-enabled: false`. The keycloak stub's token isn't
 * a real JWT, so vite's bearer->oidc_claim translation can't fire; the headers are injected here.
 */
import { writeFileSync } from 'node:fs'
import { launch, fakeApi, checker, BASE_URL } from '../harness.mjs'

const EXERCISE_ID = '4242'
const DIR_ID = '77'

const AUTH_HEADERS = {
  oidc_claim_preferred_username: 'kspar',
  oidc_claim_email: 'kspar@test.ee',
  oidc_claim_easy_role: 'teacher,admin',
}

const emptySpec = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [],
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
  text_html: '<p>Read two numbers and print their sum.</p>',
  text_md: 'Read two numbers and print their sum.',
  anonymous_autoassess_template: null,
  grading_script: 'cd student-submission\npython generated_0.py',
  container_image: 'tiivad:tsl-compose',
  max_time_sec: 7,
  max_mem_mb: 30,
  // As a real GET returns it: the compiler's output is stored alongside the spec. Saving must
  // send back only the spec, or UpdateExercise appends a second copy of each generated file.
  assets: [
    { file_name: 'tsl.json', file_content: JSON.stringify(emptySpec, null, 4) },
    { file_name: 'generated_0.py', file_content: '# from a previous compile' },
    { file_name: 'meta.txt', file_content: 'Compiled at: earlier' },
  ],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-ex-tsl-live-' })
const check = checker()

const puts = []
let compileCalls = 0
let lastCompile = null

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  ['/preview/markdown', () => ({ content: '<p>preview</p>' })],
  ['/teacher/courses', () => ({ courses: [] })],
  [`/lib/dirs/${DIR_ID}/parents`, () => ({ parents: [{ id: DIR_ID, name: 'Algoritmid' }] })],
  [
    '/tsl/compile',
    async ({ route, body }) => {
      compileCalls++
      // Relayed from Node rather than route.continue(): Playwright drops the underscore-named
      // oidc_claim_* headers an auth-disabled core needs. Same compiler, same request body.
      const resp = await fetch('http://localhost:8080/v2/tsl/compile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...AUTH_HEADERS },
        body: JSON.stringify(body),
      })
      const text = await resp.text()
      lastCompile = { status: resp.status, body: text }
      await route.fulfill({ status: resp.status, contentType: 'application/json', body: text })
    },
  ],
  [
    new RegExp(`/exercises/${EXERCISE_ID}(\\?|$)`),
    ({ route, method, body }) => {
      if (method === 'PUT') {
        puts.push(body)
        return {}
      }
      void route
      return exercise
    },
  ],
])

/** Wait until the editor has settled: compile finished and no error banner is showing. */
async function settle(ms = 2500) {
  await page.waitForTimeout(ms)
}

async function compilerError() {
  const alerts = await page.locator('.MuiAlert-message').allInnerTexts()
  // The "no visual editor for X" notice is an info alert, not a compiler failure.
  return alerts.find((a) => !a.includes('no visual editor')) ?? null
}

await page.goto(`${BASE_URL}/library/exercise/${EXERCISE_ID}/sum`)
await page.waitForSelector('text=Sum of two numbers')
await page.getByRole('button', { name: 'Edit', exact: true }).click()
await page.getByRole('tab', { name: 'Auto-assessment' }).click()
await settle()

check('empty spec compiles on the real backend', (await compilerError()) === null, await compilerError())

// --- build a program execution test through the UI ---------------------------------------------
await page.getByRole('button', { name: 'Add test' }).click()
await page.getByLabel('Test type').click()
await page.getByRole('option', { name: 'Program execution test' }).click()
await settle()
check(
  'program_execution_test built in the UI compiles',
  (await compilerError()) === null,
  await compilerError(),
)

await page.getByRole('button', { name: 'User input' }).click()
await page.getByLabel('User inputs').fill('2\n3')
await page.getByRole('button', { name: 'Output check' }).click()
await page.getByLabel('Expected values').fill('5')
await settle()
check(
  'stdin + output check compiles',
  (await compilerError()) === null,
  await compilerError(),
)
await shot('01-program-execution')

await page.getByRole('tab', { name: 'Generated scripts' }).click()
await settle(500)
const generated = await page.locator('.cm-content').first().innerText()
check('real compiler emitted a tiivad script', generated.includes('tiivad'), generated.slice(0, 80))
check('the stdin values reached the generated script', generated.includes('2') && generated.includes('3'))
await shot('02-generated-from-real-compiler')

// --- add a function execution test --------------------------------------------------------------
await page.getByRole('tab', { name: 'Tests' }).click()
await page.getByRole('button', { name: 'Add test' }).click()
const secondCard = page.locator('[aria-expanded="true"]').last()
void secondCard
// The new test is the last "New test" row; open its type dropdown.
await page.getByLabel('Test type').last().click()
await page.getByRole('option', { name: 'Function call test' }).click()
await settle()

await page.getByLabel('Function name').fill('liida')
await page.getByLabel('Function arguments').fill('2\n3')
await page.getByRole('button', { name: 'Return value check' }).click()
await page.getByLabel('Return value').fill('5')
await settle()
check(
  'function_execution_test built in the UI compiles',
  (await compilerError()) === null,
  await compilerError(),
)
await shot('03-function-call')

// --- what the compiler was asked to build is what Save will send --------------------------------
await page.getByRole('tab', { name: 'TSL', exact: true }).click()
await settle(300)
await shot('04-spec')

// --- Save sends only the spec asset -------------------------------------------------------------
await page.getByRole('button', { name: 'Save', exact: true }).click()
await page.waitForTimeout(1200)

check('save happened', puts.length === 1, `puts=${puts.length}`)
if (puts.length) {
  const body = puts[0]
  // Dumped so the exact request can be replayed against a real core (see PUT_BODY_PATH).
  writeFileSync(process.env.PUT_BODY_PATH ?? '/tmp/tsl-put-body.json', JSON.stringify(body, null, 2))
  const names = (body.assets ?? []).map((a) => a.file_name)
  check(
    'save sends only tsl.json (server regenerates the rest)',
    names.length === 1 && names[0] === 'tsl.json',
    JSON.stringify(names),
  )
  check('save keeps grader_type AUTO', body.grader_type === 'AUTO')
  check('save keeps the container image', body.container_image === 'tiivad:tsl-compose')
  const saved = JSON.parse(body.assets[0].file_content)
  check('saved spec has both tests', saved.tests.length === 2, `tests=${saved.tests?.length}`)
  check(
    'saved spec is the one the compiler accepted',
    saved.tests[0].type === 'program_execution_test' &&
      saved.tests[1].type === 'function_execution_test',
  )
  // The decisive check: hand the saved spec straight to the real compiler.
  const resp = await fetch('http://localhost:8080/v2/tsl/compile', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...AUTH_HEADERS },
    body: JSON.stringify({ tsl_spec: body.assets[0].file_content, format: 'JSON' }),
  })
  const json = await resp.json()
  check('saved spec compiles standalone', resp.status === 200 && json.feedback === null, json.feedback ?? '')
}

check('the UI actually talked to the real compiler', compileCalls > 0, `calls=${compileCalls}`)

await browser.close()
process.exit(check.summary() ? 0 : 1)
