/**
 * The TSL model's key discipline: what an *absent* key means, and what survives an edit.
 *
 * The visual builder edits a JSON document that kotlinx.serialization decodes on the Kotlin side
 * with `ignoreUnknownKeys = false`. Two rules follow, and both are invisible when broken:
 *
 * 1. **Absent means "the Kotlin default", and an empty string does not.** Several fields default
 *    to a real sentence — "Could not find {expected} in the code" and friends. Writing `""` there
 *    overrides that default with nothing, so a student's feedback silently becomes blank. Clearing
 *    a box in the editor has to *remove the key*, not set it empty. That is `setOrUnset`'s whole
 *    job, and nothing tested it.
 * 2. **A key the editor does not render must survive being edited around.** A teacher can hand-write
 *    a spec, and the builder is not a superset of the format. If an edit rebuilt the object from
 *    the fields it knows about, everything else would vanish — a data-loss bug whose symptom is a
 *    test that quietly stops running.
 *
 * The browser specs (`library-exercise-tsl`, `-tsl-static`) drive the editor and assert on the
 * payload the compiler receives. They cannot enumerate the edge cases; this can, in milliseconds.
 */
import { describe, expect, test } from 'vitest'
import {
  TEST_TYPES,
  createTest,
  isEditableType,
  nextId,
  pointsWeightField,
  propertyCheckField,
  quantifierUsesNothingElse,
  quantifierUsesValues,
  setOrDefault,
  setOrUnset,
  visibleToUserField,
} from '../../src/features/library/tsl/tslModel.ts'

/** A test as a teacher might hand-write it: known fields, plus one the editor has no form for. */
const handWritten = () => ({
  type: 'contains_test',
  id: 42,
  name: 'Adds two numbers',
  scope: 'PROGRAM',
  // Not rendered by any section. If an edit drops this, the spec silently loses a rule.
  someFutureField: { nested: ['value'] },
})

describe('absent means the Kotlin default', () => {
  test('setOrDefault stores a value that differs from the default', () => {
    expect(setOrDefault({ type: 't' }, 'k', 5, 1)).toEqual({ type: 't', k: 5 })
  })

  test('and removes the key when the value returns to the default', () => {
    // Not "sets it to the default". An explicit value that happens to equal today's default would
    // pin it, so a later change to the Kotlin side would not reach specs already written.
    const set = setOrDefault({ type: 't' }, 'k', 5, 1)
    expect(setOrDefault(set, 'k', 1, 1)).toEqual({ type: 't' })
    expect('k' in setOrDefault(set, 'k', 1, 1)).toBe(false)
  })

  test('setOrUnset removes the key for an empty string', () => {
    const set = setOrUnset({ type: 't' }, 'msg', 'Custom message')
    expect(set.msg).toBe('Custom message')
    expect('msg' in setOrUnset(set, 'msg', '')).toBe(false)
  })

  test('and for whitespace, which is what an emptied box actually contains', () => {
    const set = setOrUnset({ type: 't' }, 'msg', 'Custom message')
    expect('msg' in setOrUnset(set, 'msg', '   ')).toBe(false)
  })

  test('but keeps a value with meaningful surrounding whitespace', () => {
    // The trim decides *whether* to keep it, not what to keep — a message ending in a space is
    // still a message.
    expect(setOrUnset({ type: 't' }, 'msg', ' hello ').msg).toBe(' hello ')
  })

  test('neither mutates the test it was given', () => {
    // The editor holds these in React state, so a mutation would update the model without a
    // re-render and the screen would disagree with what gets compiled.
    const original = { type: 't', k: 1 }
    setOrDefault(original, 'k', 9, 1)
    setOrUnset(original, 'k', '')
    expect(original).toEqual({ type: 't', k: 1 })
  })
})

describe('fields the editor does not render survive an edit', () => {
  test('setOrDefault preserves them', () => {
    const edited = setOrDefault(handWritten(), 'scope', 'FUNCTION', 'PROGRAM')
    expect(edited.someFutureField).toEqual({ nested: ['value'] })
    expect(edited.name).toBe('Adds two numbers')
  })

  test('setOrUnset preserves them, including while removing another key', () => {
    const edited = setOrUnset(handWritten(), 'name', '')
    expect('name' in edited).toBe(false)
    expect(edited.someFutureField).toEqual({ nested: ['value'] })
    expect(edited.id).toBe(42)
  })

  test('a sequence of edits preserves them', () => {
    // The realistic case: several fields touched in one session.
    let t = handWritten()
    t = setOrDefault(t, 'scope', 'FUNCTION', 'PROGRAM')
    t = setOrUnset(t, 'containsWhatArg', 'print')
    t = setOrDefault(t, 'pointsWeight', 3, 1)
    t = setOrDefault(t, 'pointsWeight', 1, 1)
    expect(t.someFutureField).toEqual({ nested: ['value'] })
    expect('pointsWeight' in t).toBe(false)
  })
})

describe('defaults read back the way Kotlin declares them', () => {
  test('an absent points weight is 1, not 0', () => {
    expect(pointsWeightField({ type: 't' })).toBe(1)
  })

  test('an explicit weight is honoured, including zero', () => {
    // 0 is a real choice — a test that runs but does not count — and a falsy check would erase it.
    expect(pointsWeightField({ type: 't', pointsWeight: 3 })).toBe(3)
    expect(pointsWeightField({ type: 't', pointsWeight: 0 })).toBe(0)
  })

  test('and a nonsense weight falls back rather than reaching the compiler', () => {
    for (const bad of ['3', null, NaN, Infinity, {}]) {
      expect(pointsWeightField({ type: 't', pointsWeight: bad }), String(bad)).toBe(1)
    }
  })

  test('an absent visibleToUser means visible', () => {
    expect(visibleToUserField({ type: 't' })).toBe(true)
    expect(visibleToUserField({ type: 't', visibleToUser: true })).toBe(true)
  })

  test('and only an explicit false hides a test', () => {
    expect(visibleToUserField({ type: 't', visibleToUser: false })).toBe(false)
  })

  test('an absent propertyCheck must have mustHaveProperty true, not false', () => {
    // The Kotlin default is true. Reading an absent object as all-falsy would invert the meaning of
    // every function_is_test that never touched the box.
    expect(propertyCheckField({ type: 't' }).mustHaveProperty).toBe(true)
    expect(propertyCheckField({ type: 't', propertyCheck: {} }).mustHaveProperty).toBe(true)
  })

  test('and an explicit false is honoured', () => {
    expect(
      propertyCheckField({ type: 't', propertyCheck: { mustHaveProperty: false } }).mustHaveProperty,
    ).toBe(false)
  })

  test('a propertyCheck of the wrong shape falls back rather than throwing', () => {
    for (const bad of [null, 'yes', [], 42]) {
      expect(propertyCheckField({ type: 't', propertyCheck: bad }).mustHaveProperty, String(bad)).toBe(true)
    }
  })
})

/**
 * Coverage by construction: every editable type, whatever the list grows to.
 *
 * Adding a type to `TEST_TYPE_GROUPS` without teaching `createTest` about it would otherwise
 * produce a test with no body and no name, and the first sign would be a compiler error on a
 * teacher's screen.
 */
describe('every editable test type can be created', () => {
  test('the list is not empty', () => {
    expect(TEST_TYPES.length).toBeGreaterThanOrEqual(8)
  })

  test.each(TEST_TYPES)('%s', (type) => {
    const created = createTest(type, 7)
    expect(created.type).toBe(type)
    expect(created.id).toBe(7)
    // `name: null` is deliberate — the card derives a title from the scope and target until the
    // teacher names it. Absent would be a different thing to Kotlin.
    expect('name' in created).toBe(true)
    expect(isEditableType(type)).toBe(true)
  })

  test('an id is generated when none is given, inside the exact-integer range', () => {
    // Ids are Kotlin `Long`s; anything above 2^53 would be perturbed by a JSON round-trip.
    for (let i = 0; i < 200; i++) {
      const id = nextId()
      expect(Number.isSafeInteger(id)).toBe(true)
      expect(id).toBeGreaterThanOrEqual(0)
    }
  })

  test('a type with no form is not editable', () => {
    expect(isEditableType('some_unknown_test')).toBe(false)
  })
})

/**
 * Quantifiers decide which fields the form shows — and emitting a field tiivad ignores is not
 * harmless, because the spec is decoded with `ignoreUnknownKeys = false`.
 *
 * The whole matrix, because it is six values and my first guess at two of them was wrong:
 * `ANY_OF_THESE` does honour `nothingElse`; it is the bare `ANY` that ignores both. Two cases
 * chosen by intuition would have pinned the intuition.
 */
describe('quantifiers decide which fields the form shows', () => {
  test.each([
    // checkType, reads expectedValue, honours nothingElse
    ['ALL_OF_THESE', true, true],
    ['ANY_OF_THESE', true, true],
    ['NONE_OF_THESE', true, false],
    ['MISSING_AT_LEAST_ONE_OF_THESE', true, false],
    ['ANY', false, false],
    ['NONE', false, false],
  ])('%s: values=%s nothingElse=%s', (checkType, usesValues, usesNothingElse) => {
    expect(quantifierUsesValues(checkType)).toBe(usesValues)
    expect(quantifierUsesNothingElse(checkType)).toBe(usesNothingElse)
  })
})
