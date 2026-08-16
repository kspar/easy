/**
 * The flake hunter's own arithmetic.
 *
 * Worth testing because the first version of it could not detect a flake. `--repeat-each=N` emits
 * **N separate spec entries with the same title**, not N results under one — and keying a Map by
 * title with `.set()` silently kept only the last repeat, so every spec reported as 1/1 and a
 * genuinely intermittent one was classified as "failed every time". It looked entirely plausible.
 *
 * The shapes below are what Playwright's JSON reporter actually produces, taken from a real
 * `--repeat-each=2` run.
 */
import { describe, expect, test } from 'vitest'
import { classify, describe as render, tally } from '../support/flake-report.mjs'

/** One spec entry, as `--repeat-each` emits one per repeat. */
const entry = (title, status) => ({ title, tests: [{ results: [{ status }] }] })

const report = (...specs) => ({ suites: [{ specs }] })

describe('counting repeats', () => {
  test('repeats of the same title are accumulated, not overwritten', () => {
    // The bug. Two entries, one failing: without accumulation this reads as 1 of 1.
    const counts = tally(report(entry('grade-table', 'passed'), entry('grade-table', 'failed')))

    expect(counts.get('grade-table')).toEqual({ total: 2, failed: 1 })
  })

  test('across nested suites, which is how Playwright groups by file', () => {
    const nested = {
      suites: [
        { suites: [{ specs: [entry('a', 'passed')] }] },
        { suites: [{ specs: [entry('a', 'failed')] }] },
      ],
    }
    expect(tally(nested).get('a')).toEqual({ total: 2, failed: 1 })
  })

  test('a skipped run counts as neither pass nor fail', () => {
    // `library-exercise-tsl-live` skips without a real core. Counting a skip as a pass would let a
    // spec that quietly stopped running look perfectly stable — which is the failure this suite's
    // check-count ratchet exists for, one level down.
    expect(tally(report(entry('live', 'skipped'), entry('live', 'skipped'))).has('live')).toBe(false)
    expect(tally(report(entry('live', 'skipped'), entry('live', 'failed'))).get('live'))
      .toEqual({ total: 1, failed: 1 })
  })
})

describe('classifying', () => {
  const counts = (o) => new Map(Object.entries(o))

  test('failing some of the time is intermittent', () => {
    const r = classify(counts({ flaky: { total: 5, failed: 2 } }))
    expect(r.intermittent.map(([t]) => t)).toEqual(['flaky'])
    expect(r.alwaysFailing).toEqual([])
  })

  test('failing every time is not — that is a real failure and CI already says so', () => {
    const r = classify(counts({ broken: { total: 5, failed: 5 } }))
    expect(r.intermittent).toEqual([])
    expect(r.alwaysFailing.map(([t]) => t)).toEqual(['broken'])
  })

  test('passing every time is neither', () => {
    const r = classify(counts({ fine: { total: 5, failed: 0 } }))
    expect(r.intermittent).toEqual([])
    expect(r.alwaysFailing).toEqual([])
  })

  test('one failure in five is caught — the case the whole job exists for', () => {
    // A spec at this rate passes most pushes and fails somebody else's. It is exactly what
    // `retries: 0` refuses to paper over, and exactly what a single run cannot see.
    expect(classify(counts({ rare: { total: 5, failed: 1 } })).intermittent).toHaveLength(1)
  })
})

describe('the report a human reads', () => {
  test('names the intermittent specs and their rate', () => {
    const out = render(classify(new Map([['grade-table', { total: 5, failed: 2 }]])))
    expect(out).toContain('INTERMITTENT')
    expect(out).toContain('grade-table  failed 2 of 5')
  })

  test('says so plainly when there is nothing', () => {
    expect(render(classify(new Map([['a', { total: 5, failed: 0 }]])))).toContain('Nothing intermittent')
  })
})
