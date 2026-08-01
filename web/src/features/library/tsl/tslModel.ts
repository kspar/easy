/**
 * TypeScript mirror of the TSL spec model in `tsl-common` (`tsl/common/model/*.kt`).
 *
 * Two constraints drive the shape of this file:
 *
 * 1. The spec is decoded server-side by kotlinx.serialization with the default
 *    `ignoreUnknownKeys = false`, so an unknown key is a hard compile error. Everything written
 *    back must therefore be a key that actually exists on the Kotlin class.
 * 2. The visual editor only understands a subset of each test's fields, and only some of the 43
 *    test types at all. So tests are carried as open objects and *patched* rather than rebuilt:
 *    spreading the parsed object keeps `pointsWeight`, `visibleToUser`, `passedNext` and every
 *    other field the UI never shows, and an unrecognised test type round-trips untouched.
 *
 * The sealed `Test` hierarchy serialises with kotlinx's default `"type"` discriminator, matching
 * each subclass's `@SerialName`.
 *
 * Currently a proof of concept: `placeholder_test`, `program_execution_test` and
 * `function_execution_test` have real forms — the same three wui shipped. Every other type is
 * shown as raw JSON and preserved byte-for-byte. See `TEST_TYPES` for how to add more.
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
export type EditableTestType = 'placeholder_test' | 'program_execution_test' | 'function_execution_test'

/**
 * The types the "test type" dropdown offers. Extending the editor to another TSL test means
 * adding it here, giving `createTest` a blank instance, and adding a case to `TslTestBody`.
 */
export const TEST_TYPES: EditableTestType[] = [
  'placeholder_test',
  'program_execution_test',
  'function_execution_test',
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

/** A blank test of the given type, with every field the Kotlin class declares. */
export function createTest(type: EditableTestType, id = nextId()): TslTest {
  const base: TslTest = { type, id, name: null }
  switch (type) {
    case 'placeholder_test':
      return base
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
 * Display name for a test type. Falls back to the raw discriminator for the types that have no
 * form (and therefore no translated name) yet.
 */
export function defaultTestName(type: string, t: (key: string) => string): string {
  const key = `tsl.defaultName.${type}`
  const translated = t(key)
  return translated === key ? type : translated
}

/** A copy of `test` with a fresh id, so both can live in the same spec. */
export function duplicateTest(test: TslTest, copySuffix: string): TslTest {
  return {
    ...structuredClone(test),
    id: nextId(),
    name: `${test.name?.trim() ? test.name : test.type} ${copySuffix}`,
  }
}
