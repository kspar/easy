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

/** `FUNCTION` is a plain function; `METHOD` is called on an object built by `createObject`. */
export type FunctionType = 'FUNCTION' | 'METHOD'

/** Like `GenericCheck`, but against a file the submission wrote rather than its output. */
export interface OutputFileCheck {
  fileName: string
  checkType: CheckType
  nothingElse?: boolean | null
  expectedValue: string[]
  elementsOrdered?: boolean | null
  dataCategory?: DataCategory
  ignoreCase?: boolean | null
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

/** Whether the run is allowed to raise. `mustNotThrowException` reads as written. */
export interface ExceptionCheck {
  mustNotThrowException: boolean
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

/** Checks one argument *after* the call, for functions that mutate what they are given. */
export interface ParamValueCheck {
  paramNumber: number
  expectedValue: string
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

/**
 * One field of a constructed object. `fieldContent` is emitted as a Python *literal*, not a
 * string (`PyStr(..., forceString = false)`), so `5` stays a number and text needs its own quotes.
 */
export interface FieldData {
  fieldName: string
  fieldContent: string
}

/**
 * The state of an object after construction, compared against `obj.__dict__`.
 *
 * `checkName` and `checkValue` are independent and both are needed: with only `checkName` the
 * fields must exist but may hold anything, with only `checkValue` the values must appear under
 * some name or other. `nothingElse` additionally forbids fields not listed.
 */
export interface ClassInstanceCheck {
  fieldsFinal: FieldData[]
  checkName: boolean
  checkValue: boolean
  nothingElse: boolean
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

/** What `definition_test` requires to be defined. `superClassName` applies only to `CLASS`. */
export type DefinitionCheckType = 'FUNCTION' | 'CLASS'

/** Which analyser predicate `function_is_test` asks for. */
export type FunctionProperty = 'PURE' | 'RECURSIVE'

/**
 * `function_is_test`'s check — the one collapsed test that does *not* use `GenericCheckLong`.
 * `is_pure()` / `is_recursive()` return a bool rather than a set, so there is nothing for a
 * quantifier to quantify over; `mustHaveProperty` is the whole condition and the compiler emits
 * it as a real `True`/`False` rather than a list of strings.
 */
export interface FunctionPropertyCheck {
  mustHaveProperty: boolean
  beforeMessage: string
  passedMessage: string
  failedMessage: string
}

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
  | 'class_instance_test'
  | 'contains_test'
  | 'calls_test'
  | 'definition_test'
  | 'function_is_test'

/**
 * The type dropdown, grouped.
 *
 * The split is the one that matters to a teacher: does this test *run* their code, or read it
 * without running it? That distinction decides whether inputs and outputs are even meaningful,
 * and after the collapse it is the only thing separating four otherwise similar-sounding names.
 *
 * Extending the editor to another TSL test means adding it here, giving `createTest` a blank
 * instance, adding a case to `TslTestBody`, and — for the collapsed static tests — a case to
 * `testDefaultName`.
 */
export const TEST_TYPE_GROUPS: { labelKey: string; types: EditableTestType[] }[] = [
  {
    labelKey: 'tsl.groupExecution',
    types: ['program_execution_test', 'function_execution_test', 'class_instance_test'],
  },
  {
    labelKey: 'tsl.groupStatic',
    types: ['contains_test', 'calls_test', 'definition_test', 'function_is_test'],
  },
  { labelKey: 'tsl.groupOther', types: ['placeholder_test'] },
]

/** Flattened, for everything that only needs to know whether a type has a form. */
export const TEST_TYPES: EditableTestType[] = TEST_TYPE_GROUPS.flatMap((g) => g.types)

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
    case 'definition_test':
      // `scopeType`, not `scope` — this one type names the field differently (EZ-1742).
      // `definitionCheckValue` is required and non-null but read by nothing; it is kept in sync
      // with the check's first expected value rather than being a second field to fill in. See
      // `DefinitionBody`.
      return {
        ...base,
        scopeType: 'PROGRAM',
        definitionCheckType: 'FUNCTION',
        definitionCheckValue: '',
        superClassName: null,
        functionName: null,
        className: null,
        genericCheck: emptyGenericCheckLong(tr('tsl.definesCheckPass'), tr('tsl.definesCheckFail')),
      }
    case 'function_is_test':
      // No scope: the predicate only exists for a named function. And no GenericCheckLong — see
      // FunctionPropertyCheck. tiivad raises outright if this check is missing, because a
      // check-less test would pass unconditionally for every student.
      return {
        ...base,
        functionName: '',
        functionProperty: 'RECURSIVE',
        propertyCheck: {
          mustHaveProperty: true,
          beforeMessage: '',
          passedMessage: tr('tsl.functionIsCheckPass'),
          failedMessage: tr('tsl.functionIsCheckFail'),
        },
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
      // The three `*ErrorMsg` fields are deliberately absent, not blank. They have non-empty
      // Kotlin defaults, so an omitted key means "use the default" while an empty string would
      // override it with nothing. The form writes them only once edited, and deletes them again
      // when cleared.
      return {
        ...base,
        functionName: '',
        functionType: 'FUNCTION',
        createObject: null,
        arguments: [],
        standardInputData: [],
        inputFiles: [],
        genericChecks: [],
        returnValueCheck: null,
        paramValueChecks: [],
        outputFileChecks: [],
      }
    case 'class_instance_test':
      return {
        ...base,
        className: '',
        createObject: '',
        classInstanceChecks: [],
        genericChecks: [],
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

/** A list-valued check field, filtered to plain objects so the JSON tab can't crash a form. */
function objListField<T>(test: TslTest, key: string): T[] {
  const v = test[key]
  if (!Array.isArray(v)) return []
  return v.filter((x): x is T => typeof x === 'object' && x !== null)
}

export const outputFileChecksField = (t: TslTest) => objListField<OutputFileCheck>(t, 'outputFileChecks')
export const paramChecksField = (t: TslTest) => objListField<ParamValueCheck>(t, 'paramValueChecks')
export const instanceChecksField = (t: TslTest) =>
  objListField<ClassInstanceCheck>(t, 'classInstanceChecks').map((c) => ({
    ...c,
    fieldsFinal: Array.isArray(c.fieldsFinal) ? c.fieldsFinal : [],
  }))

export function exceptionCheckField(test: TslTest): ExceptionCheck | null {
  const v = test.exceptionCheck
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return null
  return v as ExceptionCheck
}

export function emptyOutputFileCheck(passedMessage: string, failedMessage: string): OutputFileCheck {
  return {
    fileName: 'output.txt',
    checkType: 'ALL_OF_THESE',
    expectedValue: [],
    elementsOrdered: false,
    dataCategory: 'CONTAINS_STRINGS',
    beforeMessage: '',
    passedMessage,
    failedMessage,
  }
}

export function emptyClassInstanceCheck(passedMessage: string, failedMessage: string): ClassInstanceCheck {
  // Both name and value on by default: "the object has field x set to y" is what a teacher means
  // by checking a field, and each can be turned off for the looser variants.
  return {
    fieldsFinal: [],
    checkName: true,
    checkValue: true,
    nothingElse: false,
    beforeMessage: '',
    passedMessage,
    failedMessage,
  }
}

export function emptyParamValueCheck(passedMessage: string, failedMessage: string): ParamValueCheck {
  // Zero-based: tiivad indexes the argument list with it directly.
  return { paramNumber: 0, expectedValue: '', beforeMessage: '', passedMessage, failedMessage }
}

/**
 * Sets `key` when `value` is non-empty and removes it otherwise.
 *
 * For fields whose Kotlin default is a non-empty string: writing `""` overrides the default with
 * nothing, whereas an absent key falls back to it. Clearing the box has to mean the latter.
 */
export function setOrUnset(test: TslTest, key: string, value: string): TslTest {
  const next = { ...test }
  if (value.trim() === '') delete next[key]
  else next[key] = value
  return next
}

/** `function_is_test`'s check. Falls back to a blank; the field is non-nullable in Kotlin. */
export function propertyCheckField(test: TslTest): FunctionPropertyCheck {
  const v = test.propertyCheck
  if (typeof v !== 'object' || v === null || Array.isArray(v)) {
    return { mustHaveProperty: true, beforeMessage: '', passedMessage: '', failedMessage: '' }
  }
  const c = v as Partial<FunctionPropertyCheck>
  // `mustHaveProperty` defaults to true on the Kotlin side, so an absent one is not "false".
  return {
    mustHaveProperty: c.mustHaveProperty !== false,
    beforeMessage: c.beforeMessage ?? '',
    passedMessage: c.passedMessage ?? '',
    failedMessage: c.failedMessage ?? '',
  }
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
    case 'definition_test': {
      // `scopeType` here, unlike its two siblings.
      const s = t(`tsl.scopeSubject.${enumField<Scope>(test, 'scopeType', 'PROGRAM')}`)
      const kind = enumField<DefinitionCheckType>(test, 'definitionCheckType', 'FUNCTION')
      const value = optStrField(test, 'definitionCheckValue').trim()
      const superClass = optStrField(test, 'superClassName').trim()
      // Kotlin names the thing being defined, so we do too, or the editor title and the title in a
      // student's feedback would describe the same test differently. Before anything is typed
      // there is nothing to name, and the generic type label reads better than a dangling verb.
      if (value === '') return defaultTestName(test.type, t)
      const variant = kind === 'CLASS' && superClass !== '' ? 'SUBCLASS' : kind
      return t(`tsl.definesName.${variant}`, { scope: s, value, superClass })
    }
    case 'function_is_test':
      // Deliberately ignores mustHaveProperty, matching Kotlin: a "must NOT be recursive" test
      // still names itself "Funktsioon on rekursiivne". Noted on EZ-1742 rather than diverging,
      // since this string is what students see when the test has no explicit name.
      return t(`tsl.functionIsName.${enumField<FunctionProperty>(test, 'functionProperty', 'RECURSIVE')}`)
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
