/**
 * Verification driver for the X-018 fix: TSL compile/parse rejections reach the teacher as one
 * sentence in the app's voice — naming the key where the message carries one — with the raw
 * kotlinx diagnostic behind a details disclosure instead of in the open.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x018-error-voice-verify.mjs
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const KOTLINX_UNKNOWN_KEY = [
  "Unexpected JSON token at offset 106: Encountered an unknown key 'somethingTheUiInvented' at path: $",
  "Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to ignore unknown keys.",
  'JSON input: {"language":"python3","validateFiles":true}',
].join('\n')

const TSL_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [],
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

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  let rejectCompiles = false
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      ['/tsl/compile', () =>
        rejectCompiles
          ? { scripts: null, meta: null, feedback: KOTLINX_UNKNOWN_KEY }
          : {
              scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
              meta: { timestamp: '2026-08-23T10:00:00.000Z', compiler_version: '4.0', backend_id: 'tiivad', backend_version: '0.0.33' },
              feedback: null,
            },
      ],
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
  await page.getByRole('tab', { name: /Automaatkontroll/i }).first().click()
  await page.waitForTimeout(600)

  // ─── 1. a compile rejection: one sentence naming the key, raw text behind the disclosure ────────
  rejectCompiles = true
  await page.getByRole('tab', { name: /^TSL$/i }).first().click()
  await page.waitForTimeout(400)
  await page.locator('.cm-content').first().click()
  await page.keyboard.press('End')
  // Any edit re-triggers the compile, which now rejects.
  await page.keyboard.type(' ')
  await waitUntil(async () => (await page.getByText(/tundmatu väli/).count()) > 0, { timeout: 6000 }).catch(() => {})
  check((await page.getByText(/tundmatu väli/).count()) > 0, 'the summary sentence names the problem in Estonian')
  check((await page.getByText(/somethingTheUiInvented/).count()) > 0, 'and carries the offending key by name')
  check(
    !(await page.getByText(/ignoreUnknownKeys = true/).first().isVisible().catch(() => false)),
    "kotlinx's advice to the compiler author is not in the open",
  )
  await page.getByText('Tehnilised üksikasjad').first().click()
  await page.waitForTimeout(400)
  check(
    await page.getByText(/ignoreUnknownKeys = true/).first().isVisible(),
    'the raw diagnostic is available behind the disclosure',
  )

  // ─── 1b. a transport failure is not blamed on the spec ──────────────────────────────────────────
  await page.route('**/tsl/compile', (route) =>
    route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }),
  )
  await page.locator('.cm-content').first().click()
  await page.keyboard.type(' ')
  await waitUntil(async () => (await page.getByText(/Kompilaatorit ei õnnestunud/).count()) > 0, { timeout: 6000 }).catch(() => {})
  check(
    (await page.getByText(/Kompilaatorit ei õnnestunud/).count()) > 0,
    'a 500 says the compiler is unreachable',
  )
  check(
    (await page.getByText(/Kompilaator ei võta spetsifikatsiooni vastu/).count()) === 0,
    'and does not claim the spec was rejected',
  )
  await page.unroute('**/tsl/compile')

  // ─── 2. a parse failure: the sentence, not the raw browser message ───────────────────────────────
  await page.locator('.cm-content').first().click()
  await page.keyboard.type('}{{')
  await waitUntil(async () => (await page.getByText(/JSON-i süntaksiviga/).count()) > 0, { timeout: 6000 }).catch(() => {})
  check((await page.getByText(/JSON-i süntaksiviga/).count()) > 0, 'broken JSON gets the teacher-voiced sentence')
  check(
    !(await page.getByText(/Unexpected token|Expected ','/).first().isVisible().catch(() => false)),
    'the raw parser message is not in the open',
  )
  await page.close()
})

console.log(failures === 0 ? '\nX-018 verification: all checks passed' : `\nX-018 verification: ${failures} FAILURES`)
process.exit(failures === 0 ? 0 : 1)
