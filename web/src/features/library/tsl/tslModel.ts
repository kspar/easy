/**
 * TypeScript mirror of the TSL spec model in `tsl-common` (`tsl/common/model/*.kt`).
 *
 * Two constraints drive the shape of this file:
 *
 * 1. The spec is decoded server-side by kotlinx.serialization with the default
 *    `ignoreUnknownKeys = false`, so an unknown key is a hard compile error. Everything written
 *    back must therefore be a key that actually exists on the Kotlin class.
 * 2. The visual editor only understands a subset of each test's fields. So tests are carried as
 *    open objects and *patched* rather than rebuilt: spreading the parsed object keeps
 *    `pointsWeight`, `visibleToUser`, `passedNext` and every other field the UI never shows, and
 *    an unrecognised test type round-trips untouched.
 *
 * The sealed `Test` hierarchy serialises with kotlinx's default `"type"` discriminator, matching
 * each subclass's `@SerialName`.
 *
 * The TSL model collapsed 39 narrow test types into 4 parameterised ones (EZ-1607), so the whole
 * universe is now 8 types rather than 43 and full form coverage is actually reachable. Done so
 * far: `placeholder_test`, `program_execution_test`, `function_execution_test`, `contains_test`.
 * Anything else still falls back to raw JSON, preserved byte-for-byte. See `TEST_TYPES` for how
 * to add more.
 */

export type CheckType = 'ALL_OF_THESE' | 'ANY_OF_THESE' | 'MISSING_AT_LEAST_ONE_OF_THESE' | 'NONE_OF_THESE'

export type DataCategory = 'CONTAINS_LINES' | 'CONTAINS_NUMBERS' | 'CONTAINS_STRINGS' | 'EQUALS'

export interface FileData {
  fileName: string
  fileContent: string
}

export interface GenericCheck {
  id: number
  checkType: CheckType
  nothingElse?: boolean | null
  expectedValue: string[]
  elementsOrdered?: boolean | null
  dataCategory?: DataCategory
  outputCategory?: string
  ignoreCase?: boolean | null
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

export interface ReturnValueCheck {
  returnValue: string
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

// --- the collapsed static tests (EZ-1607) ----------------------------------------------------
//
// `contains_test`, `calls_test`, `definition_test` and `function_is_test` replaced 39 types that
// each hardcoded one scope and one target. The scope is now a field, which is why these three
// enums and `GenericCheckLong` are shared rather than per-type.

/** Where the check looks. `MAIN_PROGRAM` is the code outside any def/class. */
export type Scope = 'PROGRAM' | 'MAIN_PROGRAM' | 'FUNCTION' | 'CLASS'

/**
 * What `contains_test` searches for.
 *
 * `KEYWORD_WITH_PRECEDING_ARG` is narrower than it sounds: tiivad maps it to `imports_module` and
 * raises on any argument other than `"import"`, so the UI offers it as "imports a module" and
 * fills the argument in itself rather than exposing a free-text field that only has one legal
 * value. See `run_contains_test` in tiivad's handler.py.
 */
export type ContainsWhat = 'KEYWORD_NO_ARG' | 'KEYWORD_WITH_PRECEDING_ARG' | 'PHRASE'

/**
 * What `calls_test` looks for a call to. Note this is the *callee*; the caller is the `scope`, so
 * "a class method calls a function" is `scope: CLASS` + `targetType: FUNCTION`.
 */
export type TargetType = 'FUNCTION' | 'CLASS' | 'CLASS_FUNCTION'

/**
 * The quantifier on a `GenericCheckLong`. Wider than `CheckType`: `ANY` and `NONE` ask whether
 * the target set is non-empty / empty *without naming anything*, which is how the old boolean
 * checks (`ContainsCheck.mustNotContain`, `CallsCheck.mustNotCall`) survive the collapse.
 */
export type CheckTypeLong =
  | 'ALL_OF_THESE'
  | 'ANY_OF_THESE'
  | 'ANY'
  | 'NONE_OF_THESE'
  | 'MISSING_AT_LEAST_ONE_OF_THESE'
  | 'NONE'

/** True when the quantifier actually reads `expectedValue`; `ANY`/`NONE` ignore it. */
export function quantifierUsesValues(checkType: CheckTypeLong): boolean {
  return checkType !== 'ANY' && checkType !== 'NONE'
}

/** True when `nothingElse` is honoured — tiivad only applies it to these two. */
export function quantifierUsesNothingElse(checkType: CheckTypeLong): boolean {
  return checkType === 'ALL_OF_THESE' || checkType === 'ANY_OF_THESE'
}

/**
 * The single check carried by each collapsed static test. Deliberately *not* `GenericCheck`: no
 * `id`, no `elementsOrdered`, no `outputCategory`, and a wider `checkType`. Emitting any of those
 * keys would be a hard decode error server-side.
 */
export interface GenericCheckLong {
  checkType: CheckTypeLong
  nothingElse?: boolean | null
  expectedValue: string[]
  dataCategory?: DataCategory
  ignoreCase?: boolean | null
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

/**
 * One test. Only `type` and `id` are guaranteed; everything else depends on the test type and is
 * read through narrowing helpers. Unknown keys are preserved by construction.
 */
export interface TslTest {
  type: string
  id: number
  name?: string | null
  [key: string]: unknown
}

export interface TslSpec {
  language?: string
  validateFiles: boolean
  requiredFiles: string[]
  tslVersion: string
  tests: TslTest[]
  [key: string]: unknown
}

/** A test with a full visual form. Anything else falls back to the raw JSON editor. */
export type EditableTestType =
  | 'placeholder_test'
  | 'program_execution_test'
  | 'function_execution_test'
  | 'contains_test'
  | 'calls_test'

/**
 * The types the "test type" dropdown offers. Extending the editor to another TSL test means
 * adding it here, giving `createTest` a blank instance, adding a case to `TslTestBody`, and — for
 * the collapsed static tests — a case to `testDefaultName`.
 */
export const TEST_TYPES: EditableTestType[] = [
  'placeholder_test',
  'program_execution_test',
  'function_execution_test',
  'contains_test',
  'calls_test',
]

export function isEditableType(type: string): type is EditableTestType {
  return (TEST_TYPES as string[]).includes(type)
}

/**
 * Ids are `Long` on the Kotlin side and only need to be unique within a spec. Random 48-bit
 * values stay well inside the exact-integer range so a JSON round-trip can't perturb them.
 */
export function nextId(): number {
  return Math.floor(Math.random() * 2 ** 48)
}

export function emptySpec(): TslSpec {
  return {
    language: 'python3',
    validateFiles: true,
    requiredFiles: ['lahendus.py'],
    tslVersion: '1.0',
    tests: [],
  }
}

export function emptyGenericCheck(passedMessage: string, failedMessage: string): GenericCheck {
  return {
    id: nextId(),
    checkType: 'ALL_OF_THESE',
    expectedValue: [],
    elementsOrdered: false,
    dataCategory: 'CONTAINS_STRINGS',
    beforeMessage: '',
    passedMessage,
    failedMessage,
  }
}

/**
 * A blank `GenericCheckLong`. `dataCategory` and `ignoreCase` are left off: tiivad's
 * `run_contains_test` never reads them, and both have Kotlin-side defaults, so writing them would
 * add noise to the spec without changing any behaviour.
 */
export function emptyGenericCheckLong(passedMessage: string, failedMessage: string): GenericCheckLong {
  return {
    checkType: 'ALL_OF_THESE',
    expectedValue: [],
    beforeMessage: '',
    passedMessage,
    failedMessage,
  }
}

/**
 * A blank test of the given type, with every field the Kotlin class declares.
 *
 * `t` is optional only because the placeholder test needs no copy. The collapsed static tests do:
 * their `genericCheck` is non-nullable, so unlike the execution tests' *optional* checks — which
 * get their default messages from the "add check" button — there is no later moment at which
 * translated feedback could be filled in. Without it the spec ships empty pass/fail messages and
 * students see blank check descriptions in their feedback.
 */
export function createTest(type: EditableTestType, id = nextId(), t?: Translate): TslTest {
  const tr = (key: string) => (t ? t(key) : '')
  const base: TslTest = { type, id, name: null }
  switch (type) {
    case 'placeholder_test':
      return base
    case 'contains_test':
      return {
        ...base,
        scope: 'PROGRAM',
        containsWhat: 'KEYWORD_NO_ARG',
        containsWhatArg: null,
        functionName: null,
        className: null,
        genericCheck: emptyGenericCheckLong(tr('tsl.containsCheckPass'), tr('tsl.containsCheckFail')),
      }
    case 'calls_test':
      // `targetClassName` is declared on the Kotlin class but read by nothing — not the compiler,
      // not tiivad (EZ-1742). Left out rather than written as null: the recommendation on that
      // issue is to delete it, and emitting it into every new spec would entrench it. Specs that
      // already carry it keep it, since tests are patched rather than rebuilt.
      return {
        ...base,
        scope: 'PROGRAM',
        targetType: 'FUNCTION',
        functionName: null,
        className: null,
        genericCheck: emptyGenericCheckLong(tr('tsl.callsCheckPass'), tr('tsl.callsCheckFail')),
      }
    case 'program_execution_test':
      return {
        ...base,
        standardInputData: [],
        inputFiles: [],
        genericChecks: [],
        outputFileChecks: [],
        exceptionCheck: null,
      }
    case 'function_execution_test':
      return {
        ...base,
        functionName: '',
        functionType: 'FUNCTION',
        arguments: [],
        standardInputData: [],
        inputFiles: [],
        genericChecks: [],
        returnValueCheck: null,
        paramValueChecks: [],
        outputFileChecks: [],
      }
  }
}

// Typed reads off the open test object. Defensive because the JSON tab lets anyone type anything.

export function strField(test: TslTest, key: string): string {
  const v = test[key]
  return typeof v === 'string' ? v : ''
}

export function strListField(test: TslTest, key: string): string[] {
  const v = test[key]
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}

export function fileListField(test: TslTest, key: string): FileData[] {
  const v = test[key]
  if (!Array.isArray(v)) return []
  return v.filter(
    (x): x is FileData =>
      typeof x === 'object' && x !== null && typeof (x as FileData).fileName === 'string',
  )
}

export function checkListField(test: TslTest, key: string): GenericCheck[] {
  const v = test[key]
  if (!Array.isArray(v)) return []
  return v.filter((x): x is GenericCheck => typeof x === 'object' && x !== null)
}

export function returnCheckField(test: TslTest): ReturnValueCheck | null {
  const v = test.returnValueCheck
  if (typeof v !== 'object' || v === null) return null
  return v as ReturnValueCheck
}

/**
 * The single `genericCheck` the collapsed static tests carry. Falls back to a blank rather than
 * null: the field is non-nullable in Kotlin, so a spec that lost it is already broken and the
 * form may as well let you fill it back in.
 */
export function genericCheckField(test: TslTest): GenericCheckLong {
  const v = test.genericCheck
  if (typeof v !== 'object' || v === null || Array.isArray(v)) {
    return emptyGenericCheckLong('', '')
  }
  return v as GenericCheckLong
}

/** A field that is either a string or absent — the collapsed tests use null for "not applicable". */
export function optStrField(test: TslTest, key: string): string {
  const v = test[key]
  return typeof v === 'string' ? v : ''
}

/**
 * Reads one of the collapsed tests' enum fields, falling back to a known-good value. Not
 * validated against the union: the JSON tab can put anything here, and a form that renders an
 * unexpected value as its default is friendlier than one that blanks the whole card.
 */
export function enumField<T extends string>(test: TslTest, key: string, fallback: T): T {
  const v = test[key]
  return typeof v === 'string' ? (v as T) : fallback
}

/** Which extra name field a scope implies, if any. */
export function scopeNameField(scope: Scope): 'functionName' | 'className' | null {
  if (scope === 'FUNCTION') return 'functionName'
  if (scope === 'CLASS') return 'className'
  return null
}

export function serializeSpec(spec: TslSpec): string {
  return JSON.stringify(spec, null, 4)
}

export interface ParseResult {
  spec: TslSpec | null
  error: string | null
}

export function parseSpec(text: string): ParseResult {
  if (text.trim() === '') return { spec: emptySpec(), error: null }
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (e) {
    return { spec: null, error: (e as Error).message }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { spec: null, error: 'TSL spec must be a JSON object' }
  }
  const o = parsed as Record<string, unknown>
  if (!Array.isArray(o.tests)) {
    return { spec: null, error: 'TSL spec is missing a "tests" array' }
  }
  const badTest = o.tests.findIndex(
    (t) => typeof t !== 'object' || t === null || typeof (t as TslTest).type !== 'string',
  )
  if (badTest >= 0) {
    return { spec: null, error: `Test at index ${badTest} has no "type"` }
  }
  return { spec: o as unknown as TslSpec, error: null }
}

/**
 * Display name for a test *type*, used where there is no instance to read — the type dropdown.
 * Falls back to the raw discriminator for types that have no form (and therefore no translated
 * name) yet.
 */
export function defaultTestName(type: string, t: (key: string) => string): string {
  const key = `tsl.defaultName.${type}`
  const translated = t(key)
  return translated === key ? type : translated
}

type Translate = (key: string, opts?: Record<string, unknown>) => string

/**
 * Display name for a test *instance*. The collapsed types (EZ-1607) took their distinguishing
 * detail into fields, so "contains test" alone no longer says what it checks — the name has to be
 * built from scope and target the way Kotlin's `getDefaultName()` does.
 *
 * Worth knowing: this is the editor's label only. When `name` is null the spec carries no name at
 * all, and the title students see in feedback comes from `getDefaultName()` on the Kotlin side,
 * which is hardcoded Estonian. So the two agree in `et` by construction and diverge in `en` —
 * a pre-existing wart of the model, not something this function can fix.
 */
export function testDefaultName(test: TslTest, t: Translate): string {
  // Read defensively: the JSON tab lets anyone type anything, and a name is the last place that
  // should throw.
  const scope = t(`tsl.scopeSubject.${enumField<Scope>(test, 'scope', 'PROGRAM')}`)
  switch (test.type) {
    case 'contains_test':
      return t(`tsl.containsName.${enumField<ContainsWhat>(test, 'containsWhat', 'KEYWORD_NO_ARG')}`, { scope })
    case 'calls_test':
      return t(`tsl.callsName.${enumField<TargetType>(test, 'targetType', 'FUNCTION')}`, { scope })
    default:
      return defaultTestName(test.type, t)
  }
}

/** A copy of `test` with a fresh id, so both can live in the same spec. */
export function duplicateTest(test: TslTest, copySuffix: string): TslTest {
  return {
    ...structuredClone(test),
    id: nextId(),
    name: `${test.name?.trim() ? test.name : test.type} ${copySuffix}`,
  }
}
