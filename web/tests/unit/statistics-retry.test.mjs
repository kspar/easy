/**
 * The statistics poll's retry ladder — the part of it that can be a pure function.
 *
 * `useStatistics` polls core in a `while` loop for as long as its component is mounted, and until
 * EZ-1844 that loop had no way to stop. Every failure waited a flat five seconds and asked again, so
 * a landing page open against an endpoint that would never answer — which is exactly what an
 * anonymous visitor had, since the endpoint was `@Secured` — talked to core twice every six seconds
 * for as long as the tab stayed open. That is the failure this ladder exists to end, and "it ends"
 * is a property of `retryDelayMs` rather than of the hook: the loop stops precisely when this returns
 * null.
 *
 * The hook itself is not tested here on purpose — see vitest.config.ts on why there is no jsdom.
 * What it does with these numbers (stop, and say the counts are unavailable) needs a browser.
 */
import { describe, expect, test, vi } from 'vitest'

// client.ts reads `config.emsRoot` from a module that loads runtime config at boot, so it has to be
// stubbed before the module under test pulls it in — same as api-client.test.mjs, and a *default*
// export because that is how client.ts imports it.
vi.mock('../../src/config.ts', () => ({ default: { emsRoot: 'https://api.test/v2' } }))

const { retryDelayMs } = await import('../../src/api/statistics.ts')

describe('retryDelayMs', () => {
  test('the ladder ends, which is the whole point', () => {
    // Walk well past the end rather than asserting on one index past it: an off-by-one that made
    // the last rung repeat forever would pass a check of `retryDelayMs(6)` alone if the ladder were
    // ever lengthened without this test being read.
    const rungs = []
    for (let n = 1; n <= 50; n++) {
      const delay = retryDelayMs(n)
      if (delay === null) break
      rungs.push(delay)
    }
    expect(rungs.length).toBeLessThan(50)
    expect(retryDelayMs(rungs.length + 1)).toBeNull()
  })

  test('the delays grow, so a restart is ridden out and a dead endpoint is not', () => {
    const first = retryDelayMs(1)
    expect(first).toBeGreaterThan(0)

    let previous = first
    for (let n = 2; ; n++) {
      const delay = retryDelayMs(n)
      if (delay === null) break
      expect(delay).toBeGreaterThan(previous)
      previous = delay
    }

    // Long enough to survive a core restart, short enough that the page is not still asking an hour
    // later. Both bounds are the decision, not an implementation detail.
    expect(first).toBeLessThanOrEqual(5_000)
    expect(previous).toBeLessThanOrEqual(60_000)
  })

  test('total patience is measured in minutes, not hours', () => {
    let total = 0
    for (let n = 1; ; n++) {
      const delay = retryDelayMs(n)
      if (delay === null) break
      total += delay
    }
    expect(total).toBeGreaterThan(30_000)
    expect(total).toBeLessThan(10 * 60_000)
  })

  test('a nonsensical attempt number gives up rather than indexing off the end', () => {
    // The hook always counts from 1. A 0 or a negative reaching here would mean the caller lost
    // count, and `RETRY_BACKOFF_MS[-1]` is `undefined` — which coerces to a `setTimeout` of 0 and a
    // tight loop against the server, i.e. the loudest possible version of the bug being fixed.
    expect(retryDelayMs(0)).toBeNull()
    expect(retryDelayMs(-1)).toBeNull()
  })
})
