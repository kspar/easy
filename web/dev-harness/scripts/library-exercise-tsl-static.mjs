/**
 * The collapsed static tests (EZ-1607) — `contains_test` and `calls_test` so far, with
 * `definition_test` and `function_is_test` to follow into the same file.
 *
 * The assertions that matter most are about what reaches the *spec*, not what the form looks
 * like, because several are silent failures:
 *
 *  - `genericCheck` must not carry `id` / `elementsOrdered` / `outputCategory`. Those belong to
 *    `GenericCheck`, not `GenericCheckLong`, and kotlinx decodes with `ignoreUnknownKeys = false`
 *    — so a stray key is a hard compile error on save, with nothing in the UI to hint at it.
 *  - switching scope must clear the name that no longer applies, or a spec keeps a `functionName`
 *    the compiler will hand to an analyzer that shouldn't get one.
 *
 * Both are invisible on screen and only observable in the payload the compiler was sent, which is
 * why this records every compile rather than reading the editor's own text.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const EXERCISE_ID = '4243'
const DIR_ID = '77'

const initialSpec = {
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests: [
    {
      type: 'contains_test',
      id: 3001,
      name: null,
      scope: 'FUNCTION',
      functionName: 'loe_andmed',
      className: null,
      containsWhat: 'KEYWORD_NO_ARG',
      containsWhatArg: null,
      genericCheck: {
        checkType: 'ALL_OF_THESE',
        expectedValue: ['for'],
        beforeMessage: '',
        passedMessage: 'Leidsid tsükli',
        failedMessage: 'Tsüklit ei leidnud',
      },
      // Never shown by the form; must survive every edit below.
      pointsWeight: 3.0,
      visibleToUser: false,
    },
  ],
}

const exercise = {
  dir_id: DIR_ID,
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-08-06T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Reads a file',
  text_html: '<p>Read the file.</p>',
  text_md: 'Read the file.',
  anonymous_autoassess_template: null,
  grading_script: 'cd student-submission\npython generated_0.py',
  container_image: 'tiivad:tsl-compose',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [{ file_name: 'tsl.json', file_content: JSON.stringify(initialSpec, null, 4) }],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-ex-tsl-static-' })
const check = checker()

const compiled = []

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  ['/preview/markdown', () => ({ content: '<p>preview</p>' })],
  ['/teacher/courses', () => ({ courses: [] })],
  [`/lib/dirs/${DIR_ID}/parents`, () => ({ parents: [{ id: DIR_ID, name: 'Algoritmid' }] })],
  [
    '/tsl/compile',
    ({ body }) => {
      compiled.push(JSON.parse(body.tsl_spec))
      return {
        scripts: [{ name: 'generated_0.py', value: '# ok' }],
        feedback: null,
        meta: {
          timestamp: '2026-08-06T12:00:00.000Z',
          compiler_version: '1.0',
          backend_id: 'tiivad',
          backend_version: '2.0',
        },
      }
    },
  ],
  [new RegExp(`/exercises/${EXERCISE_ID}(\\?|$)`), () => exercise],
])

/**
 * The test of the given type as the compiler last saw it, once a compile newer than `after` has
 * landed. Waiting on the compile is the point: the model→text→compile hop is debounced, so
 * reading `compiled` straight after an edit reads the state before it.
 */
async function latestTest(after = -1, type = 'contains_test') {
  await waitUntil(() => compiled.length > after + 1, { timeout: 15_000 })
  return compiled[compiled.length - 1].tests.find((t) => t.type === type)
}

const selectOption = async (comboName, optionName) => {
  await page.getByRole('combobox', { name: comboName }).click()
  await page.getByRole('option', { name: optionName, exact: true }).click()
}

await page.goto(`${BASE_URL}/library/exercise/${EXERCISE_ID}/reads-a-file`)
await page.waitForSelector('text=Reads a file')
await page.getByRole('button', { name: 'Edit', exact: true }).click()
await page.getByRole('tab', { name: 'Auto-assessment' }).click()

// --- the name is built from the fields, not the type ------------------------------------------
// A type-level label would read "Code contains…" for every one of these, which is exactly what
// the collapse would have cost the list view if the name hadn't been made instance-aware.
const fnTitle = page.getByText('The function contains a keyword', { exact: true })
check('unnamed test is titled from its scope and target', await waitUntil(() => fnTitle.isVisible()))
await shot('01-tests-tab')

await fnTitle.click()

// --- the scope's name field ---------------------------------------------------------------------
check(
  'FUNCTION scope shows the function name, filled from the spec',
  (await page.getByLabel('Function name').inputValue()) === 'loe_andmed',
)
check('and does not show a class name', (await page.getByLabel('Class name').count()) === 0)

// --- switching scope drops the name that no longer applies --------------------------------------
let seen = compiled.length - 1
await selectOption('Scope', 'Whole program')
check(
  'PROGRAM scope hides the function name field',
  await waitUntil(async () => (await page.getByLabel('Function name').count()) === 0),
)

let test = await latestTest(seen)
check(
  'and clears functionName in the spec rather than leaving it stale',
  test.functionName === null,
  `functionName = ${JSON.stringify(test.functionName)}`,
)
check('the title follows the scope', await waitUntil(() =>
  page.getByText('The program contains a keyword', { exact: true }).isVisible()))

// --- CLASS scope asks for a class name ----------------------------------------------------------
await selectOption('Scope', 'A class')
check(
  'CLASS scope asks for a class name instead',
  await waitUntil(() => page.getByLabel('Class name').isVisible()),
)
await page.getByLabel('Class name').fill('Raamatukogu')
await selectOption('Scope', 'Whole program')
seen = compiled.length - 1
test = await latestTest(seen - 1)
check('leaving CLASS clears className too', test.className === null)

// --- "imported module" is a mode, not a free-text argument ---------------------------------------
seen = compiled.length - 1
await selectOption('Looks for', 'Imported module')
test = await latestTest(seen)
check(
  'the UI supplies containsWhatArg=import, the only value tiivad accepts',
  test.containsWhat === 'KEYWORD_WITH_PRECEDING_ARG' && test.containsWhatArg === 'import',
  `containsWhat=${test.containsWhat} arg=${JSON.stringify(test.containsWhatArg)}`,
)
check(
  'and there is no argument field to get it wrong in',
  (await page.getByLabel(/argument/i).count()) === 0,
)
check(
  'the values field relabels for the mode',
  await waitUntil(() => page.getByLabel('Module names').isVisible()),
)
await shot('02-imports-module')

// --- the quantifier governs which fields mean anything --------------------------------------------
await page.getByLabel('Module names').fill('csv')
check('nothingElse offered for ALL_OF_THESE', await page.getByLabel('…and nothing else').isVisible())

await selectOption('Condition', 'at least one, any at all')
check(
  'ANY hides the expected values, which tiivad ignores',
  await waitUntil(async () => (await page.getByLabel('Module names').count()) === 0),
)
check(
  'ANY also hides nothingElse, which tiivad ignores for it',
  (await page.getByLabel('…and nothing else').count()) === 0,
)

// Hidden, not cleared: flipping the quantifier to look at something and back must not eat input.
await selectOption('Condition', 'all of these are present')
check(
  'switching back restores the values rather than eating them',
  await waitUntil(async () => (await page.getByLabel('Module names').inputValue()) === 'csv'),
)
await shot('03-quantifier')

// --- the shape of what gets saved ------------------------------------------------------------------
seen = compiled.length - 1
await page.getByLabel('Module names').fill('csv\nmath')
test = await latestTest(seen)

check('expected values reach the spec', JSON.stringify(test.genericCheck.expectedValue) === '["csv","math"]')

// The whole reason GenericCheckLong is a separate component. Any of these keys fails the save.
const illegal = ['id', 'elementsOrdered', 'outputCategory'].filter((k) => k in test.genericCheck)
check(
  'genericCheck carries no GenericCheck-only keys, which would fail to decode',
  illegal.length === 0,
  `offending keys: ${illegal.join(', ') || 'none'} | actual: ${Object.keys(test.genericCheck).join(',')}`,
)
check(
  'fields no form shows survive every edit above',
  test.pointsWeight === 3.0 && test.visibleToUser === false,
  `pointsWeight=${test.pointsWeight} visibleToUser=${test.visibleToUser}`,
)

// --- creating one from scratch ----------------------------------------------------------------------
const typeSelects = page.getByRole('combobox', { name: 'Test type' })
await page.getByRole('button', { name: 'Add test' }).click()
check(
  'a new test card is added, expanded',
  await waitUntil(async () => (await typeSelects.count()) === 2),
)

// The new card arrives already open, so "New test" matches both its title and its type select —
// address the select positionally instead. It defaults to placeholder; switching it to
// contains_test must produce every field the Kotlin class declares, or the first save fails on a
// missing non-nullable.
await typeSelects.last().click()
await page.getByRole('option', { name: 'Code contains…', exact: true }).click()
seen = compiled.length - 1
await waitUntil(() => compiled.length > seen + 1, { timeout: 15_000 })
const created = compiled[compiled.length - 1].tests.find((t) => t.id !== 3001 && t.type === 'contains_test')
check(
  'a freshly created contains_test declares every field Kotlin requires',
  created !== undefined &&
    'scope' in created &&
    'containsWhat' in created &&
    'containsWhatArg' in created &&
    'functionName' in created &&
    'className' in created &&
    'genericCheck' in created,
  created ? Object.keys(created).join(',') : 'not found',
)
// Its check is non-nullable, so it ships with whatever messages creation gave it — there is no
// "add check" button to supply translated defaults later, and blank ones reach students.
check(
  'and its mandatory check comes with real feedback rather than empty strings',
  !!created?.genericCheck?.passedMessage?.trim() && !!created?.genericCheck?.failedMessage?.trim(),
  `passed=${JSON.stringify(created?.genericCheck?.passedMessage)} failed=${JSON.stringify(created?.genericCheck?.failedMessage)}`,
)
await shot('04-created')

// --- calls_test, which reuses both shared sections -------------------------------------------------
// Switching the same card's type rather than adding a third: it also proves the type change rebuilds
// the body cleanly instead of leaving contains_test's fields behind.
await typeSelects.last().click()
await page.getByRole('option', { name: 'Code calls…', exact: true }).click()

check(
  'calls_test reuses the scope section',
  await waitUntil(() => page.getByRole('combobox', { name: 'Scope' }).last().isVisible()),
)
check(
  'and asks for the callee separately from the caller',
  await page.getByRole('combobox', { name: 'Calls' }).isVisible(),
)

// scope is the caller, targetType the callee, and they are independent — so a class method calling
// a plain function has to be expressible. That combination was four separate types before.
await page.getByRole('combobox', { name: 'Scope' }).last().click()
await page.getByRole('option', { name: 'A class', exact: true }).click()
await page.getByLabel('Class name').fill('Raamatukogu')
await page.getByRole('combobox', { name: 'Calls' }).click()
await page.getByRole('option', { name: 'A function', exact: true }).click()

check(
  'the values field is named after the callee, not the caller',
  await waitUntil(() => page.getByLabel('Function names').isVisible()),
)
check(
  'and the title reads caller-then-callee',
  await waitUntil(() => page.getByText('The class calls a function', { exact: true }).isVisible()),
)

seen = compiled.length - 1
await page.getByLabel('Function names').fill('print')
const callsTest = await latestTest(seen, 'calls_test')
check(
  'caller and callee reach the spec independently',
  callsTest?.scope === 'CLASS' && callsTest?.className === 'Raamatukogu' && callsTest?.targetType === 'FUNCTION',
  `scope=${callsTest?.scope} className=${callsTest?.className} targetType=${callsTest?.targetType}`,
)
check(
  'switching type left no contains_test fields behind',
  callsTest !== undefined && !('containsWhat' in callsTest) && !('containsWhatArg' in callsTest),
  `keys: ${Object.keys(callsTest ?? {}).join(',')}`,
)
// Declared on the Kotlin class, read by nothing (EZ-1742). Writing it into new specs would
// entrench a field we have asked to have deleted.
check(
  'and the dead targetClassName is not written into new specs',
  callsTest !== undefined && !('targetClassName' in callsTest),
  `keys: ${Object.keys(callsTest ?? {}).join(',')}`,
)
await shot('05-calls')

// --- definition_test: the type with two absorbed model quirks ---------------------------------------
await typeSelects.last().click()
await page.getByRole('option', { name: 'Code defines…', exact: true }).click()

// It names its scope field `scopeType` while its two siblings use `scope` (EZ-1742). The section is
// shared, so getting that wrong would write the scope into a field nothing reads and silently
// default to PROGRAM at grading time.
await page.getByRole('combobox', { name: 'Scope' }).last().click()
await page.getByRole('option', { name: 'A function', exact: true }).click()
await page.getByLabel('Function name').last().fill('main')
seen = compiled.length - 1
let def = await latestTest(seen, 'definition_test')
check(
  'scope is written to scopeType, not scope',
  def?.scopeType === 'FUNCTION' && def?.scope === undefined,
  `scopeType=${def?.scopeType} scope=${def?.scope}`,
)

// superClassName is CLASS-only: tiivad raises outright when it arrives with FUNCTION, so leaving a
// stale one behind after switching kind is a grading-time crash, not a cosmetic bug.
await page.getByRole('combobox', { name: 'Defines' }).click()
await page.getByRole('option', { name: 'A class', exact: true }).click()
check(
  'CLASS offers a superclass field',
  await waitUntil(() => page.getByLabel('Superclass').isVisible()),
)
await page.getByLabel('Superclass').fill('Teos')
await page.getByLabel('Class names').fill('Raamat')
seen = compiled.length - 1
def = await latestTest(seen, 'definition_test')
check('subclass definition reaches the spec', def?.superClassName === 'Teos')
// Kotlin's getDefaultName() names both the class and its superclass, so the editor title has to as
// well — otherwise it and the title in a student's feedback describe the same test differently.
check(
  'and the title names the class and its superclass, as Kotlin does',
  await waitUntil(() =>
    page.getByText('The function defines Raamat, a subclass of Teos', { exact: true }).isVisible()),
)

await page.getByRole('combobox', { name: 'Defines' }).click()
await page.getByRole('option', { name: 'A function', exact: true }).click()
seen = compiled.length - 1
def = await latestTest(seen, 'definition_test')
check(
  'switching back to FUNCTION clears superClassName, which tiivad rejects for FUNCTION',
  def?.superClassName === null,
  `superClassName = ${JSON.stringify(def?.superClassName)}`,
)

// Required and non-null, but read by nothing — kept in sync rather than asked for twice (EZ-1742).
await page.getByLabel('Function names').fill('arvuta\nkuva')
seen = compiled.length - 1
def = await latestTest(seen, 'definition_test')
check(
  'the dead definitionCheckValue is present and tracks the first expected value',
  def?.definitionCheckValue === 'arvuta',
  `definitionCheckValue=${JSON.stringify(def?.definitionCheckValue)}`,
)
check(
  'and there is no second field asking for the same name',
  (await page.getByLabel(/definition value|check value/i).count()) === 0,
)
await shot('06-definition')

// --- function_is_test: the one that is not a GenericCheckLong ---------------------------------------
// Counted relative to the current total, not against zero: the first card is still expanded and has
// a scope selector of its own, so an absolute count would pass without this card losing anything.
const scopesBefore = await page.getByRole('combobox', { name: 'Scope' }).count()
await typeSelects.last().click()
await page.getByRole('option', { name: 'Function property', exact: true }).click()

check(
  'no scope selector, since the predicate needs a named function',
  await waitUntil(async () => (await page.getByRole('combobox', { name: 'Scope' }).count()) === scopesBefore - 1),
  `scope selectors went ${scopesBefore} -> ${await page.getByRole('combobox', { name: 'Scope' }).count()}`,
)
await page.getByLabel('Function name').last().fill('fib')
check(
  'the analyser caveat is surfaced rather than left to be discovered',
  await page.getByText(/only direct recursion is detected/i).isVisible(),
)

// The whole condition is this one boolean; tiivad emits it as a real True/False.
await page.getByLabel('The function must have this property').click()
seen = compiled.length - 1
const fnIs = await latestTest(seen, 'function_is_test')
check(
  'polarity is a real boolean on propertyCheck, not a quantifier',
  fnIs?.propertyCheck?.mustHaveProperty === false,
  `mustHaveProperty=${JSON.stringify(fnIs?.propertyCheck?.mustHaveProperty)}`,
)
check(
  'and it carries no genericCheck, which this type does not have',
  fnIs !== undefined && !('genericCheck' in fnIs),
  `keys: ${Object.keys(fnIs ?? {}).join(',')}`,
)
// tiivad raises if the check is missing, on the grounds that a check-less test passes for everyone.
check(
  'the mandatory check is present with feedback',
  !!fnIs?.propertyCheck?.passedMessage?.trim() && !!fnIs?.propertyCheck?.failedMessage?.trim(),
  `passed=${JSON.stringify(fnIs?.propertyCheck?.passedMessage)}`,
)
await shot('07-function-is')

await browser.close()
process.exit(check.summary() ? 0 : 1)
