/**
 * Unit T5, lead C on its own, done properly.
 *
 * The first attempt looped over every combobox and took the first non-TSL option it found, which was
 * **"Tekstiredaktor" in the *submission type* select** — a different field entirely. So it never left
 * TSL, and "Save still disabled" was just the broken spec correctly disabling Save. The claim needs the
 * *auto-assessment type* select by name, and a control: the same sequence without breaking the spec
 * first, so a stuck button can be told from a correctly disabled one.
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'

const EX_ID = '4242'
const TSL_SPEC = {
  language: 'python3', validateFiles: true, requiredFiles: ['lahendus.py'], tslVersion: '1.0',
  tests: [{ type: 'program_execution_test', id: 111, name: null, standardInputData: [], inputFiles: [], genericChecks: [], outputFileChecks: [], exceptionCheck: null }],
}
const exercise = () => ({
  dir_id: 'root', effective_access: 'PRAWM', created_at: '2026-08-23T10:00:00.000Z',
  is_public: false, is_anonymous_autoassess_enabled: false, owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z', last_modified_by_id: 'kspar',
  grader_type: 'AUTO', solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa', text_html: '<p>Liida.</p>', text_md: 'Liida.',
  anonymous_autoassess_template: '', grading_script: 'python generated_0.py',
  container_image: 'tiivad:tsl-compose', max_time_sec: 7, max_mem_mb: 30,
  assets: [{ file_name: 'tsl.json', file_content: JSON.stringify(TSL_SPEC, null, 4) }],
  executors: null, on_courses: [], on_courses_no_access: 0,
})

const saveDisabled = (page) =>
  page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /Salvesta/i.test(x.innerText))
    return b ? b.disabled : null
  })

/** Change the auto-assessment type select, by its visible label, to the first non-TSL option. */
async function leaveTsl(page) {
  const select = page.getByLabel(/Automaatkontrolli tüüp/i).first()
  if (!(await select.count())) return { error: 'auto-assessment type select not found by label' }
  await select.click()
  await page.waitForTimeout(400)
  const opts = page.getByRole('option')
  const labels = []
  for (let i = 0; i < (await opts.count()); i++) labels.push((await opts.nth(i).innerText()).trim())
  const idx = labels.findIndex((l) => l && !/TSL/i.test(l))
  if (idx < 0) {
    await page.keyboard.press('Escape')
    return { error: `no non-TSL option; options were ${JSON.stringify(labels)}` }
  }
  await opts.nth(idx).click()
  return { chosen: labels[idx], options: labels }
}

for (const breakSpec of [true, false]) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        ['/tsl/compile', ({ body }) => {
          let ok = false
          try { ok = !!JSON.parse(body?.tsl_spec ?? '') } catch { ok = false }
          return ok
            ? { scripts: [{ name: 'generated_0.py', value: '# ok\n' }], meta: { timestamp: 'x', compiler_version: '1', backend_id: 'tiivad', backend_version: '?' }, feedback: null }
            : { scripts: null, feedback: "Expected start of the object '{', but had 'EOF' instead at path: $", meta: null }
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

    const onArrival = await saveDisabled(page)

    if (breakSpec) {
      await page.getByRole('tab', { name: /^TSL$/i }).first().click()
      await page.waitForTimeout(600)
      await page.locator('.cm-content').first().click()
      await page.keyboard.press('End')
      await page.keyboard.type('{{{ not json')
      await page.waitForTimeout(1800)
    }
    const beforeLeaving = await saveDisabled(page)

    const moved = await leaveTsl(page)
    await page.waitForTimeout(1800)
    const afterLeaving = await saveDisabled(page)

    const label = breakSpec ? 'spec BROKEN first' : 'spec left VALID (control)'
    console.log(`\n[${label}]`)
    console.log(`    Save disabled on arrival: ${onArrival}`)
    console.log(`    Save disabled before leaving TSL: ${beforeLeaving}`)
    console.log(`    left TSL via: ${JSON.stringify(moved)}`)
    console.log(`    Save disabled AFTER leaving TSL: ${afterLeaving}`)
    await shoot(page, `t5c-${breakSpec ? 'broken' : 'valid'}-after-leaving-tsl`)
    await page.close()
  })
}
