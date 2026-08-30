/**
 * Unit T1 — the TSL builder's entry point and first run.
 *
 * The lead from the planning pass: `changeType()` to the TSL container seeds a grading script, an
 * asset list containing an empty `generated_0.py`, a time limit and a memory limit — but **no
 * `tsl.json`**. `useTslSpec` then compiles whatever is in that file, which is the empty string, so the
 * first thing a teacher sees on the deepest screen in the application would be a compiler rejection.
 *
 * What this driver can settle with a stub: whether an empty spec is really sent, whether Save is
 * gated on it, and what the screen offers a teacher who has just chosen TSL and made no test yet.
 * What it cannot settle: the exact text the real compiler returns for `""` — kotlinx's message is not
 * something to invent. That half is the :8080 relay, and this run is what justifies asking for it.
 *
 *   HARNESS_PORT=5299 node tests/audit/t1-tsl-first-run.mjs
 */
import { withBrowser, fakeApi, shoot, collectProblems, VIEWPORTS, BASE_URL, waitUntil, AUTO_ASSESS_TAB } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

/** A freshly created library exercise: `CreateExerciseDialog` always makes a TEACHER-graded one. */
const freshExercise = (over = {}) => ({
  dir_id: 'root',
  effective_access: 'PRAWM',
  created_at: '2026-08-23T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'TEACHER',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa',
  text_html: '<p>Kirjuta programm, mis liidab kaks arvu.</p>',
  text_md: 'Kirjuta programm, mis liidab kaks arvu.',
  anonymous_autoassess_template: '',
  grading_script: null,
  container_image: null,
  max_time_sec: null,
  max_mem_mb: null,
  assets: null,
  executors: null,
  on_courses: [],
  on_courses_no_access: 0,
  ...over,
})

const compileCalls = []

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  const problems = collectProblems(page)

  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [
        '/tsl/compile',
        ({ body }) => {
          compileCalls.push(body)
          // Answer the way CompileTSL.controller does when the compiler throws: scripts null, the
          // exception message passed through verbatim. The *text* here is this driver's invention and
          // is marked as such in the log; the shape is the contract.
          const spec = body?.tsl_spec ?? ''
          if (!spec.trim()) {
            return {
              scripts: null,
              feedback: '<stubbed: whatever kotlinx says about decoding an empty string>',
              meta: null,
            }
          }
          return {
            scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
            meta: {
              timestamp: '2026-08-23T10:00:00.000Z',
              compiler_version: '4.0',
              backend_id: 'tiivad',
              backend_version: '0.0.33',
            },
            feedback: null,
          }
        },
      ],
      [new RegExp(`/exercises/${EX_ID}(\\?|$)`), () => freshExercise()],
      [/\/lib\/dirs\/[^/]+\/parents/, () => ({ parents: [] })],
      [/\/lib\/dirs\//, () => ({ dirs: [], exercises: [] })],
      [/\/v2\//, () => ({ courses: [], exercises: [], dirs: [], executors: [], images: [], count: 0 })],
    ],
    { log: false, contract: false },
  )

  await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/kahe-arvu-summa`)
  await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(600)
  await shoot(page, 't1-01-library-exercise-readonly')

  // What does the read-only auto-assessment tab say before anything is configured?
  const tabs = await page.getByRole('tab').allInnerTexts()
  console.log(`tabs: ${JSON.stringify(tabs)}`)
  const autoTab = page.getByRole('tab', { name: AUTO_ASSESS_TAB }).first()
  if (await autoTab.count()) {
    await autoTab.click()
    await page.waitForTimeout(600)
    await shoot(page, 't1-02-autoassess-readonly-teacher-graded')
    const txt = (await page.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 400)
    console.log(`read-only auto-assessment tab text: ${JSON.stringify(txt)}`)
  } else {
    console.log('NO auto-assessment tab found')
  }

  // Enter edit mode.
  const editBtn = page.getByRole('button', { name: /^Muuda|^Edit/i }).first()
  console.log(`edit button present: ${(await editBtn.count()) > 0}`)
  if (await editBtn.count()) {
    await editBtn.click()
    await page.waitForTimeout(900)
    await shoot(page, 't1-03-editing-autoassess')
  }

  // Choose TSL from the auto-assessment type select.
  const selects = await page.locator('main [role="combobox"], main select').count()
  console.log(`comboboxes on the editing auto-assessment tab: ${selects}`)
  const typeSelect = page.locator('main [role="combobox"]')
  let chose = false
  for (let i = 0; i < (await typeSelect.count()); i++) {
    await typeSelect.nth(i).click()
    await page.waitForTimeout(300)
    const tsl = page.getByRole('option', { name: /TSL/i }).first()
    if (await tsl.count()) {
      const label = await tsl.innerText()
      await tsl.click()
      console.log(`chose auto-assessment type option: ${JSON.stringify(label)} (combobox #${i})`)
      chose = true
      break
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
  }
  if (!chose) console.log('COULD NOT FIND a TSL option in any combobox')

  await page.waitForTimeout(2500) // past the 400ms parse and 800ms compile debounce
  await shoot(page, 't1-04-just-chose-tsl')

  const state = await page.evaluate(() => {
    const main = document.querySelector('main')
    const txt = main?.innerText ?? ''
    const alerts = [...document.querySelectorAll('.MuiAlert-root')].map((a) => a.textContent?.trim().slice(0, 200))
    const buttons = [...document.querySelectorAll('button')]
      .filter((b) => b.offsetParent !== null)
      .map((b) => ({ label: (b.innerText || b.getAttribute('aria-label') || '').trim().slice(0, 40), disabled: b.disabled }))
      .filter((b) => b.label)
    return {
      alerts,
      saveButton: buttons.find((b) => /Salvesta|Save/i.test(b.label)) ?? null,
      addTestButton: buttons.find((b) => /Lisa test|Add test/i.test(b.label)) ?? null,
      mentionsNoTests: /Teste pole|No tests/i.test(txt),
      buttonCount: buttons.length,
    }
  })

  console.log(`\n--- T1 state right after choosing TSL ---`)
  console.log(`alerts on screen: ${JSON.stringify(state.alerts)}`)
  console.log(`Save button: ${JSON.stringify(state.saveButton)}`)
  console.log(`Add-test button: ${JSON.stringify(state.addTestButton)}`)
  console.log(`shows a "no tests yet" empty state: ${state.mentionsNoTests}`)
  console.log(`compile calls so far: ${compileCalls.length}`)
  console.log(`compile request bodies: ${JSON.stringify(compileCalls.slice(0, 4))}`)
  const errs = problems.filter((p) => !p.includes('unique "key"'))
  if (errs.length) console.log(`errors: ${errs.slice(0, 3).join(' | ').slice(0, 400)}`)

  await page.close()
})
