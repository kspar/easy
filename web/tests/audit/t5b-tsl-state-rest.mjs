/**
 * Unit T5, the three leads the first driver did not reach.
 *
 *  A. Changing a test's type discards the whole body, with no confirmation.
 *  B. There is no undo: a deleted test is gone.
 *  C. `tslValid` is only written by `TslEditor`, so switching the container away from TSL unmounts the
 *     editor without resetting it, and Save may stay disabled on an exercise that is no longer TSL.
 *
 * The observation channel is the **debounced compile request**, not the JSON tab. `useTslSpec` POSTs
 * the whole spec to `/tsl/compile` 800ms after every change, so the sequence of request bodies *is* the
 * sequence of specs — no tab round-trip to go wrong, and it survives the card collapsing. The first
 * driver read the JSON tab and broke on exactly that.
 *
 *   HARNESS_PORT=5299 node tests/audit/t5b-tsl-state-rest.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil, SPEC_TAB, AUTO_ASSESS_TAB } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'

const TSL_SPEC = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'program_execution_test',
      id: 111,
      name: 'Minu käsitsi nimetatud test',
      standardInputData: ['2', '3'],
      inputFiles: [{ fileName: 'sisend.txt', fileContent: 'midagi' }],
      genericChecks: [
        {
          id: 900,
          checkType: 'ALL_OF_THESE',
          expectedValue: ['5'],
          elementsOrdered: false,
          dataCategory: 'CONTAINS_STRINGS',
          beforeMessage: '',
          passedMessage: 'Hea',
          failedMessage: 'Halb',
        },
      ],
      outputFileChecks: [],
      exceptionCheck: null,
    },
  ],
}

const exercise = () => ({
  dir_id: 'root', effective_access: 'PRAWM', created_at: '2026-08-23T10:00:00.000Z',
  is_public: false, is_anonymous_autoassess_enabled: false, owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z', last_modified_by_id: 'kspar',
  grader_type: 'AUTO', solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa', text_html: '<p>Liida.</p>', text_md: 'Liida.',
  anonymous_autoassess_template: '', grading_script: 'python generated_0.py',
  container_image: 'tiivad:tsl-compose', max_time_sec: 7, max_mem_mb: 30,
  assets: [
    { file_name: 'tsl.json', file_content: JSON.stringify(TSL_SPEC, null, 4) },
    { file_name: 'generated_0.py', file_content: '# generated\n' },
  ],
  executors: null, on_courses: [], on_courses_no_access: 0,
})

async function open(launch) {
  const specs = []
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      ['/tsl/compile', ({ body }) => {
        try {
          specs.push(JSON.parse(body?.tsl_spec ?? '{}'))
        } catch {
          specs.push({ unparseable: body?.tsl_spec?.slice(0, 60) })
        }
        return {
          scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
          meta: { timestamp: '2026-08-23T10:00:00.000Z', compiler_version: '1', backend_id: 'tiivad', backend_version: '?' },
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
  await page.getByRole('tab', { name: AUTO_ASSESS_TAB }).first().click()
  await page.waitForTimeout(1500)
  return { page, specs }
}

const saveDisabled = (page) =>
  page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /Salvesta/i.test(x.innerText))
    return b ? b.disabled : null
  })

const describe = (s) => {
  const t = s?.tests?.[0]
  if (!t) return s?.unparseable ? `UNPARSEABLE(${s.unparseable})` : 'no tests'
  return `${t.type} id=${t.id} name=${JSON.stringify(t.name)} keys=${Object.keys(t).length} stdin=${JSON.stringify(t.standardInputData ?? null)} checks=${(t.genericChecks ?? []).length}`
}

// ─── A. switching a test's type ────────────────────────────────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const { page, specs } = await open(launch)
  let dialog = null
  page.on('dialog', async (d) => { dialog = d.message(); await d.dismiss() })

  // Expand the card. Its title is the hand-set name, so it is findable.
  await page.getByText('Minu käsitsi nimetatud test').first().click()
  await page.waitForTimeout(800)
  const combosAfterExpand = await page.locator('main [role="combobox"]').count()
  await shoot(page, 't5b-A1-card-expanded')

  const before = specs.at(-1)
  // The test-type Select is the first combobox inside the expanded card region.
  const combos = page.locator('main [role="combobox"]')
  let picked = null
  for (let i = 0; i < combosAfterExpand; i++) {
    await combos.nth(i).click()
    await page.waitForTimeout(350)
    // Any type other than the one already selected will do; these are the current `tsl.defaultName`
    // labels, and EZ-1820 rewrote several of them ("Funktsiooni väljakutse test" is now
    // "Funktsiooni käivituse test", and "Väljakutse test" is a different type entirely).
    const opt = page.getByRole('option', { name: /Funktsiooni käivituse|Klassi|Koodi sisu|Definitsiooni/i }).first()
    if (await opt.count()) {
      picked = (await opt.innerText()).trim()
      await opt.click()
      break
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1600)
  const after = specs.at(-1)

  console.log(`\n[A] switching a test's type`)
  console.log(`    comboboxes visible after expanding: ${combosAfterExpand}`)
  console.log(`    switched to: ${JSON.stringify(picked)}`)
  console.log(`    confirmation shown: ${dialog ? JSON.stringify(dialog) : 'NO'}`)
  console.log(`    spec before: ${describe(before)}`)
  console.log(`    spec after:  ${describe(after)}`)
  await shoot(page, 't5b-A2-after-type-switch')
  await page.close()
})

// ─── B. deleting a test ────────────────────────────────────────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const { page, specs } = await open(launch)
  let dialog = null
  page.on('dialog', async (d) => { dialog = d.message(); await d.dismiss() })

  const before = specs.at(-1)
  // The kebab lives on the card header. Try every icon button until a Delete menu item appears.
  const icons = page.locator('main .MuiIconButton-root')
  let deleted = false
  for (let i = 0; i < (await icons.count()); i++) {
    await icons.nth(i).click()
    await page.waitForTimeout(350)
    const del = page.getByRole('menuitem', { name: /Kustuta|Delete/i }).first()
    if (await del.count()) {
      await del.click()
      deleted = true
      break
    }
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1600)
  const after = specs.at(-1)

  const affordances = await page.evaluate(() => {
    const labels = [...document.querySelectorAll('button, [role=menuitem]')]
      .filter((b) => b.offsetParent !== null)
      .map((b) => (b.innerText || b.getAttribute('aria-label') || '').trim())
      .filter(Boolean)
    return {
      undo: labels.some((l) => /tagasi|Undo|Taasta|Restore/i.test(l)),
      emptyState: /Teste veel pole|No tests/i.test(document.querySelector('main')?.innerText ?? ''),
      labels: labels.slice(0, 12),
    }
  })

  console.log(`\n[B] deleting a test`)
  console.log(`    delete clicked: ${deleted}`)
  console.log(`    confirmation shown: ${dialog ? JSON.stringify(dialog) : 'NO'}`)
  console.log(`    spec before: ${describe(before)}`)
  console.log(`    spec after:  ${describe(after)}`)
  console.log(`    undo affordance anywhere: ${affordances.undo}`)
  console.log(`    empty state shown: ${affordances.emptyState}`)
  await shoot(page, 't5b-B1-after-delete')
  await page.close()
})

// ─── C. the stale tslValid flag ────────────────────────────────────────────────────────────────────
await withBrowser(async ({ launch }) => {
  const { page } = await open(launch)
  const before = await saveDisabled(page)

  // Break the spec in the TSL tab.
  await page.getByRole('tab', { name: SPEC_TAB }).first().click()
  await page.waitForTimeout(600)
  await page.locator('.cm-content').first().click()
  await page.keyboard.press('End')
  await page.keyboard.type('{{{ not json')
  await page.waitForTimeout(1800)
  const whileBroken = await saveDisabled(page)

  // Now change the auto-assessment type away from TSL. The type Select is above the editor tabs.
  const combos = page.locator('main [role="combobox"]')
  let movedTo = null
  for (let i = 0; i < (await combos.count()); i++) {
    await combos.nth(i).click()
    await page.waitForTimeout(350)
    const opts = page.getByRole('option')
    const n = await opts.count()
    for (let j = 0; j < n; j++) {
      const label = (await opts.nth(j).innerText()).trim()
      if (label && !/TSL/i.test(label)) {
        movedTo = label
        await opts.nth(j).click()
        break
      }
    }
    if (movedTo) break
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1800)
  const afterMove = await saveDisabled(page)

  console.log(`\n[C] the stale tslValid flag`)
  console.log(`    Save disabled on arrival (clean spec): ${before}`)
  console.log(`    Save disabled with a broken spec: ${whileBroken}`)
  console.log(`    auto-assessment type changed to: ${JSON.stringify(movedTo)}`)
  console.log(`    Save STILL disabled after leaving TSL: ${afterMove}`)
  await shoot(page, 't5b-C1-after-leaving-tsl')
  await page.close()
})
