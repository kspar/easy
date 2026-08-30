/**
 * Unit T5 — TSL state, persistence and escape routes.
 *
 * Four leads from the planning pass, each a candidate critical, all settleable with a stub:
 *
 *  1. `Cancel` and `beforeunload` guard unsaved work, but a **React Router** navigation — a breadcrumb,
 *     a sidebar item, the kebab's course link — reportedly has no guard at all. If true this is X-001
 *     again, for a teacher, on a spec that took longer to write than a student's solution.
 *  2. Changing a test's type discards the whole body with no confirmation.
 *  3. There is no undo at the model level: a deleted test or check is gone.
 *  4. `tslValid` is only ever written by `TslEditor`, so switching the container *away* from TSL
 *     unmounts the editor without resetting the flag, and Save can stay disabled on an exercise that
 *     is no longer a TSL exercise at all.
 *
 *   HARNESS_PORT=5299 node tests/audit/t5-tsl-state-escape.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

/** An exercise already configured for TSL, with one real test in it. */
const TSL_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'program_execution_test',
      id: 111,
      name: null,
      standardInputData: [],
      inputFiles: [],
      genericChecks: [],
      outputFileChecks: [],
      exceptionCheck: null,
    },
  ],
}

const tslExercise = (over = {}) => ({
  dir_id: 'root',
  effective_access: 'PRAWM',
  created_at: '2026-08-23T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa',
  text_html: '<p>Liida kaks arvu.</p>',
  text_md: 'Liida kaks arvu.',
  anonymous_autoassess_template: '',
  grading_script: 'cd student-submission\npython generated_0.py',
  container_image: 'tiivad:tsl-compose',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [
    { file_name: 'tsl.json', file_content: JSON.stringify(TSL_SPEC, null, 4) },
    { file_name: 'generated_0.py', file_content: '# generated\n' },
  ],
  executors: null,
  on_courses: [],
  on_courses_no_access: 0,
  ...over,
})

const handlers = () => [
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
]

/** Open the exercise, enter edit mode, and land on the Automaatkontroll tab. */
async function openEditing(launch) {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(page, handlers(), { log: false, contract: false })
  await page.goto(`${BASE_URL}/library/exercise/${EX_ID}/kahe-arvu-summa`)
  await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
  await page.getByRole('button', { name: /^Muuda/i }).first().click()
  await page.waitForTimeout(700)
  await page.getByRole('tab', { name: /Automaatkontroll/i }).first().click()
  await page.waitForTimeout(1200)
  return page
}

// ─── 1. does a router navigation warn about unsaved work? ──────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const page = await openEditing(launch)

  // Make a real edit: rename the test via its inline title field if present, else type in the JSON tab.
  await page.getByRole('tab', { name: /^Spec$/i }).first().click()
  await page.waitForTimeout(600)
  const editor = page.locator('.cm-content').first()
  await editor.click()
  await page.keyboard.press('End')
  await page.keyboard.type('   ') // a whitespace edit is still an edit as far as dirty tracking goes
  await page.waitForTimeout(1200)

  let dialogSeen = null
  page.on('dialog', async (d) => {
    dialogSeen = d.message()
    await d.dismiss()
  })

  const before = page.url()
  // Leave by the breadcrumb — the most natural way out of this page.
  await page.getByRole('link', { name: /Ülesandekogu/i }).first().click()
  await page.waitForTimeout(1200)
  const after = page.url()

  const warned = (await page.getByText(/salvestamata|Discard|Kas soovid/i).count()) > 0
  console.log(`\n[1] breadcrumb navigation away from an edited TSL spec`)
  console.log(`    url changed: ${before !== after} (${after.replace(BASE_URL, '')})`)
  console.log(`    native confirm shown: ${dialogSeen ? JSON.stringify(dialogSeen) : 'NO'}`)
  console.log(`    in-page warning shown: ${warned}`)
  await shoot(page, 't5-01-after-breadcrumb-navigation')
  await page.close()
})

// ─── 2. does Cancel warn? (the control — it is documented to) ──────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const page = await openEditing(launch)
  await page.getByRole('tab', { name: /^Spec$/i }).first().click()
  await page.waitForTimeout(600)
  await page.locator('.cm-content').first().click()
  await page.keyboard.press('End')
  await page.keyboard.type('   ')
  await page.waitForTimeout(1200)

  let dialogSeen = null
  page.on('dialog', async (d) => {
    dialogSeen = d.message()
    await d.dismiss()
  })
  await page.getByRole('button', { name: /^Tühista/i }).first().click()
  await page.waitForTimeout(800)
  console.log(`\n[2] Cancel with an edited TSL spec (control — should warn)`)
  console.log(`    native confirm shown: ${dialogSeen ? JSON.stringify(dialogSeen) : 'NO'}`)
  await page.close()
})

// ─── 3. changing a test's type: is the body discarded silently? ────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const page = await openEditing(launch)
  await page.waitForTimeout(400)

  // Expand the one test, then read the spec before and after switching its type.
  const readSpec = async () => {
    await page.getByRole('tab', { name: /^Spec$/i }).first().click()
    await page.waitForTimeout(500)
    const t = await page.locator('.cm-content').first().innerText()
    await page.getByRole('tab', { name: /^TSL$/i }).first().click()
    await page.waitForTimeout(500)
    return t
  }

  await page.getByRole('tab', { name: /^TSL$/i }).first().click()
  await page.waitForTimeout(400)
  // Open the card
  const chevron = page.locator('main .MuiCollapse-root').first()
  const card = page.locator('main').getByText(/programm|Test/i).first()
  if (await card.count()) {
    await card.click()
    await page.waitForTimeout(500)
  }
  await shoot(page, 't5-03-test-card-expanded')

  let dialogSeen = null
  page.on('dialog', async (d) => {
    dialogSeen = d.message()
    await d.dismiss()
  })

  const specBefore = await readSpec()
  // Find the test-type combobox inside the card and pick something else.
  const combos = page.locator('main [role="combobox"]')
  let switched = null
  for (let i = 0; i < (await combos.count()); i++) {
    await combos.nth(i).click()
    await page.waitForTimeout(300)
    const opt = page.getByRole('option', { name: /Funktsiooni|funktsioon|Klassi|Sisaldab/i }).first()
    if (await opt.count()) {
      switched = await opt.innerText()
      await opt.click()
      break
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1200)
  const specAfter = switched ? await readSpec() : null

  console.log(`\n[3] switching a test's type`)
  console.log(`    switched to: ${JSON.stringify(switched)}`)
  console.log(`    confirm shown: ${dialogSeen ? JSON.stringify(dialogSeen) : 'NO'}`)
  if (specAfter) {
    const keysBefore = (specBefore.match(/"\w+":/g) ?? []).length
    const keysAfter = (specAfter.match(/"\w+":/g) ?? []).length
    console.log(`    spec keys before ${keysBefore} → after ${keysAfter}`)
    console.log(`    body fields survived: ${specAfter.includes('standardInputData')}`)
  }
  await shoot(page, 't5-04-after-type-switch')
  await page.close()
})

// ─── 4. undo: is there any way back after deleting a test? ─────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const page = await openEditing(launch)
  await page.getByRole('tab', { name: /^TSL$/i }).first().click()
  await page.waitForTimeout(500)

  let dialogSeen = null
  page.on('dialog', async (d) => {
    dialogSeen = d.message()
    await d.dismiss()
  })

  // The kebab on the test card carries Duplicate / Move / Delete.
  const kebabs = page.locator('main button[aria-label], main .MuiIconButton-root')
  let deleted = false
  for (let i = 0; i < (await kebabs.count()); i++) {
    await kebabs.nth(i).click()
    await page.waitForTimeout(300)
    const del = page.getByRole('menuitem', { name: /Kustuta|Delete/i }).first()
    if (await del.count()) {
      await del.click()
      deleted = true
      break
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(150)
  }
  await page.waitForTimeout(900)

  const after = await page.evaluate(() => {
    const txt = document.querySelector('main')?.innerText ?? ''
    const buttons = [...document.querySelectorAll('button')]
      .filter((b) => b.offsetParent !== null)
      .map((b) => (b.innerText || b.getAttribute('aria-label') || '').trim())
      .filter(Boolean)
    return {
      showsEmptyState: /Teste veel pole|No tests/i.test(txt),
      hasUndoAffordance: buttons.some((l) => /Võta tagasi|Undo|Taasta|Restore/i.test(l)),
      buttons: buttons.slice(0, 14),
    }
  })
  console.log(`\n[4] deleting a test`)
  console.log(`    delete found and clicked: ${deleted}`)
  console.log(`    confirm shown: ${dialogSeen ? JSON.stringify(dialogSeen) : 'NO'}`)
  console.log(`    empty state now shown: ${after.showsEmptyState}`)
  console.log(`    any undo/restore affordance: ${after.hasUndoAffordance}`)
  console.log(`    visible buttons: ${JSON.stringify(after.buttons)}`)
  await shoot(page, 't5-05-after-delete')
  await page.close()
})

// ─── 5. the stale tslValid flag ────────────────────────────────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const page = await openEditing(launch)
  // Break the spec so tslValid goes false.
  await page.getByRole('tab', { name: /^Spec$/i }).first().click()
  await page.waitForTimeout(500)
  await page.locator('.cm-content').first().click()
  await page.keyboard.press('End')
  await page.keyboard.type('{{{ not json')
  await page.waitForTimeout(1500)
  const saveWhileBroken = await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /Salvesta/i.test(x.innerText))
    return b ? b.disabled : null
  })

  // Now switch the container away from TSL entirely.
  const combos = page.locator('main [role="combobox"]')
  let switchedAway = null
  for (let i = 0; i < (await combos.count()); i++) {
    await combos.nth(i).click()
    await page.waitForTimeout(300)
    const opt = page.getByRole('option').filter({ hasNotText: /TSL/i }).first()
    if (await opt.count()) {
      const label = await opt.innerText()
      if (!/TSL/i.test(label)) {
        switchedAway = label
        await opt.click()
        break
      }
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1500)
  const saveAfterSwitch = await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /Salvesta/i.test(x.innerText))
    return b ? b.disabled : null
  })

  console.log(`\n[5] tslValid after leaving TSL`)
  console.log(`    Save disabled while the spec was broken: ${saveWhileBroken}`)
  console.log(`    switched container to: ${JSON.stringify(switchedAway)}`)
  console.log(`    Save still disabled after leaving TSL: ${saveAfterSwitch}`)
  await shoot(page, 't5-06-after-leaving-tsl')
  await page.close()
})
