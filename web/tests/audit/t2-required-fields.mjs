/**
 * Unit T2 — the test forms: is required-versus-optional legible, and is it enforced?
 *
 * T1 noticed that the red outlines on required fields do not gate Save; only a parse error, a compiler
 * rejection, or the three `isAutoAssessValid` fields do. If that holds, a teacher can save a test whose
 * function name is blank — and the interesting question is what happens next. Three links in one chain:
 *
 *   does the form mark it required? → does Save allow it? → does the compiler accept it?
 *
 * If the answer is yes/yes/yes then the failure surfaces at grading time, which is where X-026 showed
 * the student gets a raw container error and a zero. That would make three findings one story.
 *
 * Also checks the one place the builder *does* warn — a class-instance check with both of its
 * checkboxes off, which earns an orange caption — because a fix for the rest should copy it rather
 * than invent something.
 *
 *   HARNESS_PORT=5299 node tests/audit/t2-required-fields.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil, AUTO_ASSESS_TAB } from './audit.mjs'
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

const spec = (tests) => ({
  language: 'python3', validateFiles: true, requiredFiles: ['lahendus.py'], tslVersion: '1.0', tests,
})

/** A function-execution test with the one field the form marks required left blank. */
const BLANK_FUNCTION_NAME = spec([
  {
    type: 'function_execution_test', id: 111, name: 'Test ilma funktsiooni nimeta',
    functionName: '', functionType: 'FUNCTION', createObject: null,
    arguments: [], standardInputData: [], inputFiles: [],
    genericChecks: [], returnValueCheck: null, paramValueChecks: [], outputFileChecks: [],
  },
])

/** A class-instance test whose check compares nothing — the case the app *does* warn about. */
const CHECKS_NOTHING = spec([
  {
    type: 'class_instance_test', id: 222, name: 'Klassi test mis ei kontrolli midagi',
    className: 'Arv', createObject: 'Arv(5)',
    classInstanceChecks: [
      {
        fieldsFinal: [{ fieldName: 'vaartus', fieldContent: '5' }],
        checkName: false, checkValue: false, nothingElse: false,
        beforeMessage: '', passedMessage: 'ok', failedMessage: 'vale',
      },
    ],
    genericChecks: [], outputFileChecks: [],
  },
])

const exercise = (tslJson) => () => ({
  dir_id: 'root', effective_access: 'PRAWM', created_at: '2026-08-23T10:00:00.000Z',
  is_public: false, is_anonymous_autoassess_enabled: false, owner_id: 'kspar',
  last_modified: '2026-08-23T10:00:00.000Z', last_modified_by_id: 'kspar',
  grader_type: 'AUTO', solution_file_name: 'lahendus.py', solution_file_type: 'TEXT_EDITOR',
  title: 'Kahe arvu summa', text_html: '<p>Liida.</p>', text_md: 'Liida.',
  anonymous_autoassess_template: '', grading_script: 'python generated_0.py',
  container_image: 'tiivad:tsl-compose', max_time_sec: 7, max_mem_mb: 30,
  assets: [{ file_name: 'tsl.json', file_content: JSON.stringify(tslJson, null, 4) }],
  executors: null, on_courses: [], on_courses_no_access: 0,
})

const report = []

for (const c of [
  { name: 'required functionName left blank', tsl: BLANK_FUNCTION_NAME, cardTitle: 'Test ilma funktsiooni nimeta' },
  { name: 'class-instance check that compares nothing', tsl: CHECKS_NOTHING, cardTitle: 'Klassi test mis ei kontrolli midagi' },
]) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        ['/tsl/compile', () => ({
          scripts: [{ name: 'generated_0.py', value: '# compiled\n' }],
          meta: { timestamp: 'x', compiler_version: '1', backend_id: 'tiivad', backend_version: '?' },
          feedback: null,
        })],
        [new RegExp(`/exercises/${EX_ID}(\\?|$)`), exercise(c.tsl)],
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
    await page.getByText(c.cardTitle).first().click()
    await page.waitForTimeout(1200)

    const seen = await page.evaluate(() => {
      const main = document.querySelector('main')
      const err = [...main.querySelectorAll('.Mui-error')]
      const save = [...document.querySelectorAll('button')].find((b) => /Salvesta/i.test(b.innerText))
      // Anything painted in warning.main (#f9a825) — the app's one "this checks nothing" caption.
      const warned = [...main.querySelectorAll('*')].filter((el) => {
        const c = getComputedStyle(el).color
        return c === 'rgb(249, 168, 37)'
      })
      return {
        fieldsMarkedError: err.length,
        errorFieldLabels: err
          .map((e) => (e.closest('.MuiFormControl-root')?.querySelector('label')?.textContent ?? '').trim())
          .filter(Boolean)
          .slice(0, 5),
        requiredAsterisks: main.querySelectorAll('.MuiFormLabel-asterisk').length,
        saveDisabled: save ? save.disabled : null,
        warningColouredElements: warned.length,
        warningText: warned.map((w) => (w.textContent ?? '').trim().slice(0, 120)).slice(0, 3),
      }
    })

    // Now ask the real compiler what it thinks of the same spec.
    const res = await fetch(`${CORE}/v2/tsl/compile`, {
      method: 'POST', headers: CORE_HEADERS,
      body: JSON.stringify({ tsl_spec: JSON.stringify(c.tsl), format: 'JSON' }),
    })
    const out = await res.json()
    const compilerAccepts = !!out.scripts && !out.feedback

    report.push({ case: c.name, ...seen, compilerAccepts, compilerFeedback: out.feedback ?? null })
    console.log(`\n[${c.name}]`)
    console.log(`   fields marked in error: ${seen.fieldsMarkedError} ${JSON.stringify(seen.errorFieldLabels)}`)
    console.log(`   required asterisks rendered: ${seen.requiredAsterisks}`)
    console.log(`   Save disabled: ${seen.saveDisabled}`)
    console.log(`   elements painted warning.main: ${seen.warningColouredElements} ${JSON.stringify(seen.warningText)}`)
    console.log(`   real compiler accepts it: ${compilerAccepts}${out.feedback ? ` (${out.feedback.split('\n')[0].slice(0, 90)})` : ''}`)
    await shoot(page, `t2-${c.name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`)
    await page.close()
  })
}

const path = join(REPORTS, 't2-required-fields.json')
writeFileSync(path, JSON.stringify(report, null, 2))
console.log(`\nreport written to ${path}`)
