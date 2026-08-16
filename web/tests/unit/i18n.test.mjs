/**
 * The two translation files, against each other and against the code that uses them.
 *
 * This app is bilingual and **Estonian is the default**, so an English-speaking developer is the
 * least likely person to notice a broken Estonian string. Every failure here is silent in exactly
 * that way:
 *
 * - a key present in `en.json` and missing from `et.json` renders the raw key — `submission.grade`
 *   — to an Estonian user, and looks fine to everyone who tests in English
 * - a key whose Estonian text drops a `{{count}}` placeholder silently loses the number, so
 *   "3 ülesannet" becomes "ülesannet"
 * - a typo'd key in `t('...')` renders the typo, and only on the screen nobody opened
 *
 * None of it fails a build, a type check, or a browser test that does not happen to visit that
 * screen in that language. It costs milliseconds to check here instead.
 */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, test } from 'vitest'
import en from '../../src/i18n/en.json' with { type: 'json' }
import et from '../../src/i18n/et.json' with { type: 'json' }

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, '../../src')

/** `{ 'submission.grade': 'Grade', … }` — nested objects flattened to dotted paths. */
function flatten(obj, prefix = '') {
  return Object.fromEntries(
    Object.entries(obj).flatMap(([k, v]) =>
      v && typeof v === 'object' && !Array.isArray(v)
        ? Object.entries(flatten(v, `${prefix}${k}.`))
        : [[`${prefix}${k}`, v]],
    ),
  )
}

const EN = flatten(en)
const ET = flatten(et)

/** The `{{name}}` slots in a string, sorted so order is not part of the comparison. */
const placeholders = (s) => [...String(s).matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()

describe('the two languages describe the same strings', () => {
  test('there are strings at all', () => {
    // A loader that silently returned {} would make every test below pass.
    expect(Object.keys(EN).length).toBeGreaterThan(700)
  })

  test('every English key has an Estonian one', () => {
    expect(Object.keys(EN).filter((k) => !(k in ET))).toEqual([])
  })

  test('and every Estonian key has an English one', () => {
    // The other direction matters less to users and more to maintenance: a key only in et.json is
    // either a typo or a string the English file forgot, and both rot quietly.
    expect(Object.keys(ET).filter((k) => !(k in EN))).toEqual([])
  })

  test('no value is blank in either language', () => {
    const blank = Object.keys(EN).filter((k) => !String(EN[k]).trim() || !String(ET[k] ?? '').trim())
    expect(blank).toEqual([])
  })

  /**
   * The one that actually loses information.
   *
   * i18next substitutes `{{count}}` and friends at render time. If a translation drops one, the
   * value simply does not appear — no error, no fallback, just a sentence missing its number.
   */
  test('both languages interpolate the same values', () => {
    const mismatched = Object.keys(EN)
      .filter((k) => k in ET)
      .filter((k) => placeholders(EN[k]).join(',') !== placeholders(ET[k]).join(','))
      .map((k) => `${k}: en{${placeholders(EN[k])}} et{${placeholders(ET[k])}}`)
    expect(mismatched).toEqual([])
  })
})

describe('the keys the code asks for exist', () => {
  /** Every `t('some.key')` literal in src/. Template literals and computed keys are out of reach. */
  const usedKeys = (() => {
    const walk = (dir) =>
      readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)],
      )
    const found = new Set()
    for (const file of walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f))) {
      for (const m of readFileSync(file, 'utf8').matchAll(/\bt\(\s*'([a-zA-Z0-9_.]+)'/g)) {
        found.add(m[1])
      }
    }
    return [...found]
  })()

  test('the scan found the call sites', () => {
    // Same reason as above: a regex that stopped matching would turn this whole block into
    // decoration, and it would still be green.
    expect(usedKeys.length).toBeGreaterThan(500)
  })

  /**
   * i18next resolves a **plural** key through suffixed siblings, so `t('library.selected')` is
   * satisfied by `library.selected_one` and `library.selected_other` and there is deliberately no
   * bare `library.selected`. Ten keys in this app are like that; treating them as missing was this
   * test's first result, and would have been a false accusation.
   */
  const resolves = (key) =>
    key in EN || Object.keys(EN).some((k) => k.startsWith(`${key}_`))

  test('every literal t() key resolves in en.json', () => {
    expect(usedKeys.filter((k) => !resolves(k))).toEqual([])
  })

  test('and in et.json, since that is the default language', () => {
    const resolvesEt = (key) => key in ET || Object.keys(ET).some((k) => k.startsWith(`${key}_`))
    expect(usedKeys.filter((k) => !resolvesEt(k))).toEqual([])
  })
})
