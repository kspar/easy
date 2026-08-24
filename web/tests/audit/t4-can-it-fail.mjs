/**
 * Unit T4 — can a teacher tell whether their test set works before a student meets it?
 *
 * The sharpest version of that question is the opposite one: can they tell whether a test can **fail**?
 * A test set that passes everyone is the worst outcome available — it is invisible, it looks like a
 * well-taught cohort, and the exercise is silently worthless.
 *
 * This walks the actual authoring path — the "Lisa test" preset menu, which is the primary discovery
 * surface — records what a freshly added test contains, and then sends that exact spec through the real
 * compiler to see what reaches the generated Python. UI, contract and output in one chain.
 *
 * Also captures the preset menu's labels, which T1 still owed: they are the first TSL vocabulary a
 * teacher ever reads, and they are not the same words the test-type Select uses for the same things.
 *
 *   HARNESS_PORT=5299 node tests/audit/t4-can-it-fail.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'
const CORE = process.env.AUDIT_CORE ?? 'http://localhost:8080'
const CORE_HEADERS = {
  'Content-Type': 'application/json',
  oidc_claim_preferred_username: 'kspar',
  oidc_claim_email: 'kspar@ut.ee',
  oidc_claim_given_name: 'Test',
  oidc_claim_family_name: 'Teacher',
  oidc_claim_easy_role: 'teacher',
}

/** An exercise already on TSL with an empty test list — the state right after X-015's fix would land. */
const EMPTY_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [],
}

const exercise = () => ({
  dir_id: 'root', effective_access: 'PRAWM', created_at: '2026-08-23T10:00:00.000Z',
  is_public: false, is_anonymous_autoassess_enabled: false, owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z', last_modified_by_id: 'kspar',
  grader_type: 'AUTO', solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa', text_html: '<p>Liida.</p>', text_md: 'Liida.',
  anonymous_autoassess_template: '', grading_script: 'python generated_0.py',
  container_image: 'tiivad:tsl-compose', max_time_sec: 7, max_mem_mb: 30,
  assets: [{ file_name: 'tsl.json', file_content: JSON.stringify(EMPTY_SPEC, null, 4) }],
  executors: null, on_courses: [], on_courses_no_access: 0,
})

const report = {}

await withBrowser(async ({ launch }) => {
  const specs = []
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      ['/tsl/compile', ({ body }) => {
        try { specs.push(JSON.parse(body?.tsl_spec ?? '{}')) } catch { /* ignore */ }
        return {
          scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
          meta: { timestamp: 'x', compiler_version: '1', backend_id: 'tiivad', backend_version: '?' },
          feedback: null,
        }
      }],
      [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => exercise()],
      [/\/lib\/dirs\/[^/]+\/parents/, () => ({ parents: [] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], dirs: [], count: 0 })],
    ],
    { log: false, contract: false },
  )

  await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/x`)
  await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
  await page.getByRole('button', { name: /^Muuda/i }).first().click()
  await page.waitForTimeout(700)
  await page.getByRole('tab', { name: /Automaatkontroll/i }).first().click()
  await page.waitForTimeout(1500)

  // ── the preset menu: the first TSL vocabulary a teacher reads ──────────────────────────────────
  await page.getByRole('button', { name: /Lisa test/i }).first().click()
  await page.waitForTimeout(600)
  const menu = await page.evaluate(() => {
    const root = document.querySelector('.MuiMenu-list, [role=menu]')
    if (!root) return null
    return [...root.children].map((el) => ({
      role: el.getAttribute('role') ?? el.tagName.toLowerCase(),
      text: (el.textContent ?? '').trim(),
    }))
  })
  report.presetMenu = menu
  console.log(`\n[preset menu] ${menu ? menu.length : 0} entries`)
  for (const e of menu ?? []) console.log(`   ${e.role === 'menuitem' ? '  •' : '§ '} ${e.text}`)
  await shoot(page, 't4-01-preset-menu')

  // Pick the first "run the program" style preset.
  const item = page.getByRole('menuitem').filter({ hasText: /Käivita|programm/i }).first()
  const chosen = (await item.count()) ? (await item.innerText()).trim() : null
  if (chosen) await item.click()
  else await page.keyboard.press('Escape')
  await page.waitForTimeout(1800)
  report.chosenPreset = chosen
  console.log(`\n[chose preset] ${JSON.stringify(chosen)}`)

  const spec = specs.at(-1)
  const t = spec?.tests?.[0]
  report.freshTest = t ?? null
  console.log(`[fresh test] type=${t?.type} checks=${(t?.genericChecks ?? []).length} ` +
    `outputFileChecks=${(t?.outputFileChecks ?? []).length} exceptionCheck=${JSON.stringify(t?.exceptionCheck ?? null)}`)

  // ── does anything on screen say this test checks nothing? ──────────────────────────────────────
  const warnings = await page.evaluate(() => {
    const main = document.querySelector('main')
    const txt = main?.innerText ?? ''
    return {
      alerts: [...document.querySelectorAll('.MuiAlert-root')].map((a) => a.textContent?.trim().slice(0, 160)),
      // The app has one such caption, on class-instance checks; is there an equivalent here?
      mentionsChecksNothing: /kontrolli midagi|ei kontrolli|passes for everyone|midagi ei kontrolli/i.test(txt),
      warningColoured: [...main.querySelectorAll('*')].some((el) => {
        const c = getComputedStyle(el).color
        return c === 'rgb(249, 168, 37)' // theme warning.main #f9a825
      }),
      bodyText: txt.replace(/\s+/g, ' ').slice(0, 500),
    }
  })
  report.warnings = warnings
  console.log(`[warnings] alerts=${JSON.stringify(warnings.alerts)}`)
  console.log(`[warnings] any "checks nothing" wording: ${warnings.mentionsChecksNothing}`)
  console.log(`[warnings] anything painted warning.main: ${warnings.warningColoured}`)
  await shoot(page, 't4-02-fresh-test-no-checks')

  await page.close()
})

// ── the same spec, through the real compiler ────────────────────────────────────────────────────────
if (report.freshTest) {
  const spec = { ...EMPTY_SPEC, tests: [report.freshTest] }
  const res = await fetch(`${CORE}/v2/tsl/compile`, {
    method: 'POST',
    headers: CORE_HEADERS,
    body: JSON.stringify({ tsl_spec: JSON.stringify(spec), format: 'JSON' }),
  })
  const out = await res.json()
  const py = out.scripts?.[0]?.value ?? ''
  report.compiled = { ok: !!out.scripts && !out.feedback, feedback: out.feedback, python: py }
  console.log(`\n[real compiler] accepted: ${!!out.scripts && !out.feedback}`)
  console.log(`[real compiler] feedback: ${out.feedback ?? 'none'}`)
  console.log(`[real compiler] generated Python:\n${py}`)
  // Does the generated script contain any check at all?
  const checkArrays = py.match(/(standard_output_checks|output_file_checks)=\[[^\]]*\]/g) ?? []
  console.log(`[real compiler] check arrays in the script: ${JSON.stringify(checkArrays)}`)
  report.checkArrays = checkArrays
}

const path = join(REPORTS, 't4-can-it-fail.json')
writeFileSync(path, JSON.stringify(report, null, 2))
console.log(`\nreport written to ${path}`)
