/**
 * The gate's own bookkeeping: that the ratchet files describe the specs that exist, and that the
 * quarantine rules are enforced rather than merely written down.
 *
 * These run in vitest — seconds, no browser — on purpose. Everything here can be wrong in a way
 * that makes the *browser* suite quieter, so finding out from the browser suite would be finding
 * out from the thing that was just disarmed. A stale key in expected-checks.json, an expired
 * quarantine entry, a spec that nobody is counting: all of them are green until something asks.
 */
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, test } from 'vitest'
import { MAX_DAYS, QUARANTINE_PATH, parseQuarantine } from '../support/quarantine.mjs'
import { EXPECTED_CHECKS_PATH, expectedChecks } from '../support/expected-checks.mjs'
import { BASELINE_PATH } from '../support/contract-budget.mjs'
import { isAlwaysSkipped } from '../support/spec-inventory.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SPEC_DIR = join(HERE, '../browser')

const specs = readdirSync(SPEC_DIR).filter((f) => f.endsWith('.spec.mjs')).sort()
const runnable = specs.filter((f) => !isAlwaysSkipped(join(SPEC_DIR, f)))

const keysOf = (path) =>
  Object.keys(JSON.parse(readFileSync(path, 'utf8'))).filter((k) => !k.startsWith('_'))

describe('the ratchet files describe the specs that exist', () => {
  test('there are specs at all', () => {
    // A guard whose scan silently returns nothing is worse than no guard: everything below would
    // pass vacuously, and the file would read as coverage.
    expect(specs.length).toBeGreaterThan(20)
  })

  /**
   * Both directions, and the second is the one that matters.
   *
   * A key with no spec is stale — harmless but misleading. A spec with no key is *unratcheted*:
   * it can quietly stop asserting anything and nothing will say so. The runner prints a note when
   * it meets one, but a note in a passing run is not a gate, which is what this test is.
   */
  test('every spec has a check count, and every count has a spec', () => {
    const recorded = Object.keys(expectedChecks()).sort()
    expect(recorded.filter((k) => !specs.includes(k))).toEqual([])
    expect(
      runnable.filter((s) => !recorded.includes(s)),
      `record them with \`npm run test:browser:record\` on a green suite`,
    ).toEqual([])
  })

  /**
   * The opt-out is allowed, but not silently and not in bulk.
   *
   * A spec that skips itself contributes nothing to the gate, so the number of them is a number
   * worth watching: one is a documented tool that needs a live backend, and five would be a suite
   * that had quietly been switched off a spec at a time.
   */
  test('at most one spec opts out of running', () => {
    expect(specs.filter((f) => isAlwaysSkipped(join(SPEC_DIR, f)))).toEqual([
      'library-exercise-tsl-live.spec.mjs',
    ])
  })

  test('every check count is a positive number', () => {
    for (const [spec, count] of Object.entries(expectedChecks())) {
      expect(Number.isInteger(count) && count > 0, `${spec} => ${count}`).toBe(true)
    }
  })

  /**
   * Contract budgets are allowed to be absent — a new spec is not asked to think about fixture
   * drift on day one — but a budget for a spec that no longer exists is drift of its own.
   */
  test('no contract budget names a spec that is gone', () => {
    expect(keysOf(BASELINE_PATH).filter((k) => !specs.includes(k))).toEqual([])
  })

  test('the documentation keys survived the last regeneration', () => {
    // `record-checks.mjs` rewrites the file wholesale. If the `_`-prefixed keys ever stop coming
    // back, the file becomes a list of numbers with no statement of the rule they enforce, and the
    // first person to see one fail will simply lower it.
    const raw = JSON.parse(readFileSync(EXPECTED_CHECKS_PATH, 'utf8'))
    expect(Object.keys(raw)).toContain('_rule')
  })
})

describe('quarantine', () => {
  test('the committed file is loadable — which is also its expiry check', () => {
    // The reason this is a unit test and not just a load-time throw in the browser suite: an
    // expired entry should cost seconds to discover, not a full browser run.
    expect(() => parseQuarantine(JSON.parse(readFileSync(QUARANTINE_PATH, 'utf8')))).not.toThrow()
  })

  const good = {
    spec: 'grade-table.spec.mjs',
    label: 'a check that is genuinely unstable',
    issue: 'EZ-1234',
    expires: '2026-01-08',
    note: 'why it is owed',
  }
  const now = new Date('2026-01-01T12:00:00Z')

  test('a complete entry is accepted', () => {
    expect(parseQuarantine([good], now)).toHaveLength(1)
  })

  test.each(['spec', 'label', 'issue', 'expires', 'note'])('%s is required', (field) => {
    const { [field]: _dropped, ...rest } = good
    expect(() => parseQuarantine([rest], now)).toThrow(new RegExp(`missing "${field}"`))
  })

  test('an issue that is not a YouTrack id is refused', () => {
    // "flaky", "TODO" and "ask Kaspar" are all things people write here, and none of them is
    // something anybody can look up later.
    expect(() => parseQuarantine([{ ...good, issue: 'flaky' }], now)).toThrow(/not a YouTrack id/)
  })

  test('an expired entry fails, which is the whole point', () => {
    expect(() => parseQuarantine([{ ...good, expires: '2025-12-31' }], now)).toThrow(/expired on/)
  })

  test('the day it expires is still allowed', () => {
    // Off-by-one in the merciful direction: an entry expiring today should not fail the build of
    // the commit that is fixing it.
    expect(parseQuarantine([{ ...good, expires: '2026-01-01' }], now)).toHaveLength(1)
  })

  test(`an expiry more than ${MAX_DAYS} days out is refused`, () => {
    expect(() => parseQuarantine([{ ...good, expires: '2026-06-01' }], now)).toThrow(/more than/)
  })

  test('a date that is not a date is refused', () => {
    expect(() => parseQuarantine([{ ...good, expires: 'next sprint' }], now)).toThrow(/not a YYYY-MM-DD/)
  })

  test('everything wrong is reported at once', () => {
    // A validator that stops at the first problem turns fixing three entries into three runs.
    let message = ''
    try {
      parseQuarantine([{ ...good, issue: '' }, { ...good, expires: '2025-01-01' }], now)
    } catch (e) {
      message = e.message
    }
    expect(message).toMatch(/missing "issue"/)
    expect(message).toMatch(/expired on/)
  })

  test('a file that is not an array is refused', () => {
    expect(() => parseQuarantine({ 'grade-table.spec.mjs': 'flaky' }, now)).toThrow(/must be a JSON array/)
  })
})
