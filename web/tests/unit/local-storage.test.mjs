/**
 * The shared `localStorage` helpers, and specifically the cases that made them shared.
 *
 * Four hooks had grown their own copy of this, and all four guarded the read while only two
 * guarded the write. So `useSavedGroup` and `useRecentExercises` called a bare `setItem` from
 * inside a click handler — which throws in Safari private browsing, throws when the quota is full,
 * and can throw on mere access inside an iframe with third-party cookies blocked. The last of those
 * is not theoretical: `useEmbedTheme` carries a comment about it because the embed hit it.
 *
 * The failure is not "the preference was not saved". It is an exception escaping an event handler
 * while a teacher clicks a group filter.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import {
  isPlainObject,
  readJson,
  readJsonIf,
  readString,
  writeJson,
  writeString,
} from '../../src/api/localStorage.ts'

/** A localStorage that behaves, backed by a plain object. */
function workingStorage(initial = {}) {
  const store = { ...initial }
  return {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => {
      store[k] = String(v)
    },
    store,
  }
}

/** Safari private browsing, a full quota, and a blocked iframe all look like this. */
function throwingStorage(message = 'QuotaExceededError') {
  return {
    getItem: () => {
      throw new Error(message)
    },
    setItem: () => {
      throw new Error(message)
    },
  }
}

beforeEach(() => vi.stubGlobal('localStorage', workingStorage()))
afterEach(() => vi.unstubAllGlobals())

describe('reading', () => {
  test('parses what is there', () => {
    vi.stubGlobal('localStorage', workingStorage({ k: '{"a":1}' }))
    expect(readJson('k', {})).toEqual({ a: 1 })
  })

  test('falls back when the key is absent', () => {
    expect(readJson('missing', { fallback: true })).toEqual({ fallback: true })
  })

  test('falls back on unparseable JSON rather than throwing', () => {
    // Anything can end up under a key: a half-written value, a rename, another tab's format.
    vi.stubGlobal('localStorage', workingStorage({ k: '{not json' }))
    expect(readJson('k', [])).toEqual([])
  })

  test('falls back when the stored value is literally null', () => {
    // `JSON.parse('null')` succeeds and hands back null, which is almost never what a caller
    // asking for `{}` wants to spread.
    vi.stubGlobal('localStorage', workingStorage({ k: 'null' }))
    expect(readJson('k', { a: 1 })).toEqual({ a: 1 })
  })

  test('falls back when localStorage itself throws', () => {
    vi.stubGlobal('localStorage', throwingStorage())
    expect(readJson('k', 'safe')).toBe('safe')
    expect(readString('k')).toBeNull()
  })
})

describe('reading with a shape check', () => {
  test('an array where an object was expected gets the fallback', () => {
    // The bug this prevents: `JSON.parse('[]')` succeeds, the hook spreads it as a map, and then
    // behaves oddly instead of starting clean.
    vi.stubGlobal('localStorage', workingStorage({ k: '[]' }))
    expect(readJsonIf('k', isPlainObject, { clean: true })).toEqual({ clean: true })
  })

  test('an object where an array was expected gets the fallback', () => {
    vi.stubGlobal('localStorage', workingStorage({ k: '{"a":1}' }))
    expect(readJsonIf('k', Array.isArray, [])).toEqual([])
  })

  test('and a value of the right shape comes through', () => {
    vi.stubGlobal('localStorage', workingStorage({ k: '{"course-1":"g2"}' }))
    expect(readJsonIf('k', isPlainObject, {})).toEqual({ 'course-1': 'g2' })
  })
})

describe('writing', () => {
  test('stores JSON and reports success', () => {
    const storage = workingStorage()
    vi.stubGlobal('localStorage', storage)
    expect(writeJson('k', { a: 1 })).toBe(true)
    expect(storage.store.k).toBe('{"a":1}')
  })

  /**
   * The whole reason this module exists.
   *
   * Two hooks called `setItem` unguarded, from event handlers. On a storage that throws, that
   * exception escapes into React's click handling — the preference is not saved *and* the
   * interaction breaks. Returning false keeps the second half from happening.
   */
  test('a storage that throws yields false rather than an exception', () => {
    vi.stubGlobal('localStorage', throwingStorage())
    expect(() => writeJson('k', { a: 1 })).not.toThrow()
    expect(writeJson('k', { a: 1 })).toBe(false)
    expect(() => writeString('k', 'v')).not.toThrow()
    expect(writeString('k', 'v')).toBe(false)
  })

  test('a value that cannot be serialised also yields false', () => {
    // A circular structure makes JSON.stringify throw, which is the same class of problem and was
    // equally unguarded.
    const circular = {}
    circular.self = circular
    expect(writeJson('k', circular)).toBe(false)
  })
})

describe('isPlainObject', () => {
  test.each([
    [{}, true],
    [{ a: 1 }, true],
    [[], false],
    [null, false],
    ['string', false],
    [42, false],
  ])('%s -> %s', (value, expected) => {
    expect(isPlainObject(value)).toBe(expected)
  })
})
