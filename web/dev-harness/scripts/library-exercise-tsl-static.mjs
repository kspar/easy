/**
 * The test-type forms: the four collapsed static tests (EZ-1607), plus `class_instance_test` and
 * the execution-test fields that used to be reachable only through the JSON tab.
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
 * Runs `action`, then waits until the compiler has been handed a spec *different* from the one it
 * had beforehand, and returns it.
 *
 * Waiting on a changed spec rather than on a higher compile count is the whole point. The
 * model→text→compile hop is debounced, so a compile already in flight when the action starts will
 * satisfy a count check and hand back the state from before the edit. An earlier version of this
 * file did exactly that and passed anyway, purely on timing — until an unrelated component made
 * the page render a little differently and it started reporting a real assertion as broken.
 */
async function afterEdit(action) {
  // Settle first. Snapshotting while a compile from the *previous* edit is still in flight means
  // that compile lands during `action` and satisfies the "changed" wait below, handing back a spec
  // from one edit ago. Under parallel load that is the common case, not the rare one.
  await quiet()
  const before = JSON.stringify(compiled.at(-1) ?? null)
  await action()
  await waitUntil(() => JSON.stringify(compiled.at(-1) ?? null) !== before, { timeout: 15_000 })
  return compiled.at(-1)
}

/** Waits until at least one compile has landed and none has arrived for a beat. */
async function quiet() {
  await waitUntil(
    async () => {
      const before = compiled.length
      await page.waitForTimeout(250)
      // `before > 0` is the part CI needed. Zero compiles is not a quiet editor, it is one whose
      // first compile has not landed yet — and calling that settled meant snapshotting `null`,
      // then mistaking the *initial* compile for the edit's result. Locally the load always beat
      // the first assertion; on a slower runner it did not.
      return before > 0 && compiled.length === before
    },
    { timeout: 15_000, interval: 0 },
  )
}

const testOfType = (spec, type) => spec.tests.find((t) => t.type === type)

/** Presets and "Add test" append, so the newest card is the last test in the spec. */
const lastTest = (spec) => spec.tests.at(-1)

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
let spec = await afterEdit(() => selectOption('Scope', 'Whole program'))
let test = testOfType(spec, 'contains_test')
check(
  'PROGRAM scope hides the function name field',
  await waitUntil(async () => (await page.getByLabel('Function name').count()) === 0),
)
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
spec = await afterEdit(() => selectOption('Scope', 'Whole program'))
check('leaving CLASS clears className too', testOfType(spec, 'contains_test').className === null)

// --- "imported module" is a mode, not a free-text argument ---------------------------------------
spec = await afterEdit(() => selectOption('Looks for', 'Imported module'))
test = testOfType(spec, 'contains_test')
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
spec = await afterEdit(() => page.getByLabel('Module names').fill('csv\nmath'))
test = testOfType(spec, 'contains_test')

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

// --- the add-test menu, and what a preset produces ---------------------------------------------------
// The collapse cost discovery: "a loop" is no longer a test type, it is a contains_test whose
// expected values happen to be `for` and `while`, and nothing on screen would say so. A preset is
// the compensation, so what matters is that it lands *already configured* — a preset that produced
// a blank contains_test would be a rename of "Add test" and nothing more.
const typeSelects = page.getByRole('combobox', { name: 'Test type' })
await page.getByRole('button', { name: 'Add test' }).click()
check(
  'the add menu is grouped by intent, not a flat list of type names',
  await waitUntil(async () => (await page.getByText('What the code contains', { exact: true }).count()) > 0) &&
    (await page.getByText('Run the code', { exact: true }).count()) > 0,
)
// The newest card, not the first contains_test in the spec — card one is also a contains_test, and
// `find` would happily assert against it and pass on values this preset never set.
const loop = lastTest(await afterEdit(() => page.getByRole('menuitem', { name: 'Uses a loop' }).click()))
check(
  'the loop preset arrives as a keyword check, already filled in',
  loop?.scope === 'PROGRAM' &&
    loop?.containsWhat === 'KEYWORD_NO_ARG' &&
    JSON.stringify(loop?.genericCheck?.expectedValue) === '["for","while"]',
  `scope=${loop?.scope} what=${loop?.containsWhat} values=${JSON.stringify(loop?.genericCheck?.expectedValue)}`,
)
check(
  'and as ANY_OF_THESE, since either keyword is a loop',
  loop?.genericCheck?.checkType === 'ANY_OF_THESE',
  `checkType=${loop?.genericCheck?.checkType}`,
)
// A preset that lands in the required-field error state has not saved anyone any work.
check(
  'the preset is immediately valid rather than needing a field filled in',
  await waitUntil(async () => (await page.locator('.Mui-error').count()) === 0),
)
await shot('08-preset-loop')

// Delete it again so the rest of the assertions keep counting one contains_test.
await page.getByRole('button', { name: 'More options' }).last().click()
await page.getByRole('menuitem', { name: 'Delete' }).click()
await waitUntil(async () => (await typeSelects.count()) === 1)

// --- creating a blank one and switching its type -----------------------------------------------------
await page.getByRole('button', { name: 'Add test' }).click()
await page.getByRole('menuitem', { name: 'Empty test' }).click()
check(
  'a new test card is added, expanded',
  await waitUntil(async () => (await typeSelects.count()) === 2),
)
check(
  'and the type dropdown groups the types the same way',
  await (async () => {
    await typeSelects.last().click()
    const grouped = (await page.getByRole('listbox').getByText('Inspect the code', { exact: true }).count()) > 0
    await page.keyboard.press('Escape')
    return grouped
  })(),
)

// Switching a blank test to contains_test must produce every field the Kotlin class declares, or
// the first save fails on a missing non-nullable.
const created = lastTest(
  await afterEdit(async () => {
    await typeSelects.last().click()
    await page.getByRole('option', { name: 'Code contains…', exact: true }).click()
  }),
)
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

const callsTest = testOfType(
  await afterEdit(() => page.getByLabel('Function names').fill('print')),
  'calls_test',
)
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
let def = testOfType(
  await afterEdit(() => page.getByLabel('Function name').last().fill('main')),
  'definition_test',
)
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
def = testOfType(
  await afterEdit(() => page.getByLabel('Class names').fill('Raamat')),
  'definition_test',
)
check('subclass definition reaches the spec', def?.superClassName === 'Teos')
// Kotlin's getDefaultName() names both the class and its superclass, so the editor title has to as
// well — otherwise it and the title in a student's feedback describe the same test differently.
check(
  'and the title names the class and its superclass, as Kotlin does',
  await waitUntil(() =>
    page.getByText('The function defines Raamat, a subclass of Teos', { exact: true }).isVisible()),
)

def = testOfType(
  await afterEdit(async () => {
    await page.getByRole('combobox', { name: 'Defines' }).click()
    await page.getByRole('option', { name: 'A function', exact: true }).click()
  }),
  'definition_test',
)
check(
  'switching back to FUNCTION clears superClassName, which tiivad rejects for FUNCTION',
  def?.superClassName === null,
  `superClassName = ${JSON.stringify(def?.superClassName)}`,
)

// Required and non-null, but read by nothing — kept in sync rather than asked for twice (EZ-1742).
def = testOfType(
  await afterEdit(() => page.getByLabel('Function names').fill('arvuta\nkuva')),
  'definition_test',
)
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
const fnIs = testOfType(
  await afterEdit(() => page.getByLabel('The function must have this property').click()),
  'function_is_test',
)
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

// --- class_instance_test: the one nested structure in the model -------------------------------------
const inst = lastTest(
  await afterEdit(async () => {
    await typeSelects.last().click()
    await page.getByRole('option', { name: 'Class instance test', exact: true }).click()
  }),
)
check(
  'class_instance_test declares every field Kotlin requires',
  inst !== undefined &&
    'className' in inst &&
    'createObject' in inst &&
    'classInstanceChecks' in inst &&
    'genericChecks' in inst &&
    'outputFileChecks' in inst,
  inst ? Object.keys(inst).join(',') : 'not found',
)
// A code body, not an expression — tiivad indents it into a function that must return the object,
// so this has to be a multi-line editor rather than a text field.
check(
  'the constructor is a code editor',
  (await page.locator('.cm-content').count()) > 0,
)

await page.getByLabel('Class name').last().fill('Raamat')
await page.getByRole('button', { name: 'Object state check' }).click()
await page.getByRole('button', { name: 'Field', exact: true }).click()
// By role and exact name: getByLabel does a substring match, so "Field name" also picks up the
// "Check field names" checkbox and "Value" picks up "Check values".
await page.getByRole('textbox', { name: 'Field name', exact: true }).fill('pealkiri')
const instSpec = await afterEdit(() =>
  page.getByRole('textbox', { name: 'Value', exact: true }).fill("'Tõde ja õigus'"),
)
const built = lastTest(instSpec)
check(
  'the nested fieldsFinal list reaches the spec',
  JSON.stringify(built?.classInstanceChecks?.[0]?.fieldsFinal) ===
    JSON.stringify([{ fieldName: 'pealkiri', fieldContent: "'Tõde ja õigus'" }]),
  JSON.stringify(built?.classInstanceChecks?.[0]?.fieldsFinal),
)
check(
  'with name and value checking on by default',
  built?.classInstanceChecks?.[0]?.checkName === true &&
    built?.classInstanceChecks?.[0]?.checkValue === true,
)
// Both off compares nothing and passes for every student — invisible unless the form says so.
await page.getByLabel('Check field names').uncheck()
await page.getByLabel('Check values').uncheck()
check(
  'turning both off warns that the check compares nothing',
  await waitUntil(() => page.getByText(/compares nothing and passes for everyone/i).isVisible()),
)
await shot('09-class-instance')

// --- an error message override, and clearing it again -----------------------------------------------
// These have non-empty Kotlin defaults, so the key must disappear when the box is emptied rather
// than being saved as "" — otherwise clearing it silently replaces the default with no message.
await page.getByRole('button', { name: 'Add test' }).click()
await page.getByRole('menuitem', { name: 'Call a function' }).click()
await page.getByRole('button', { name: /Error messages/ }).click()
const set = lastTest(await afterEdit(() => page.getByLabel('Function not defined').fill('Kirjuta funktsioon')))
check(
  'an overridden error message is written',
  set?.functionNotDefinedErrorMsg === 'Kirjuta funktsioon',
  `= ${JSON.stringify(set?.functionNotDefinedErrorMsg)}`,
)
const cleared = lastTest(await afterEdit(() => page.getByLabel('Function not defined').fill('')))
check(
  'and clearing it removes the key rather than saving an empty string',
  cleared !== undefined && !('functionNotDefinedErrorMsg' in cleared),
  `keys: ${Object.keys(cleared ?? {}).join(',')}`,
)
await shot('10-error-messages')

await browser.close()
process.exit(check.summary() ? 0 : 1)
