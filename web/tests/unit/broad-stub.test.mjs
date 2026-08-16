/**
 * The rule behind `fakeApi`'s "[broad stub]" warning.
 *
 * It exists because one fixture mistake accounted for three separate debugging sessions on
 * 2026-08-16: a string needle that names one endpoint and is also a prefix of a deeper one, so the
 * deeper request gets answered with the wrong shape. The page usually survives — it reads one
 * field, which is simply absent — and the only trace is a contract warning about an endpoint
 * nobody was thinking about.
 *
 * A warning rule is worth testing more than most code, because both of its failure modes are
 * quiet. Too eager and it cries on every legitimate prefix stub until people stop reading it; too
 * timid and it is decoration. The cases below are the actual needles used across the suite.
 */
import { describe, expect, test } from 'vitest'
import { isOverbroad } from '../support/harness.mjs'

describe('needles that swallow a deeper endpoint', () => {
  test.each([
    // The three that actually bit, in order.
    ['/teacher/courses', '/v2/teacher/courses/1/exercises/10'],
    ['/student/courses', '/v2/student/courses/9006/exercises'],
    ['/courses/77/groups', '/v2/courses/77/groups/g1/students'],
  ])('%s also matches %s', (needle, path) => {
    expect(isOverbroad(needle, path)).toBe(true)
  })
})

describe('needles that are doing exactly what they say', () => {
  test.each([
    // Exact matches — the overwhelming majority of stubs in the suite.
    ['/account/checkin', '/v2/account/checkin'],
    ['/courses/9006/basic', '/v2/courses/9006/basic'],
    ['/preview/markdown', '/v2/preview/markdown'],
    // A trailing slash is the spelling that means "the whole family, deliberately".
    ['/submissions/all/students/', '/v2/teacher/courses/1/exercises/2/submissions/all/students/s1'],
    ['/lib/dirs/', '/v2/lib/dirs/root'],
    // Matching part-way through a segment is not swallowing anything: `/exercises` inside
    // `/exercises123` is a different endpoint name, not a parent of one.
    ['/courses/7', '/v2/courses/77/basic'],
  ])('%s against %s is fine', (needle, path) => {
    expect(isOverbroad(needle, path)).toBe(false)
  })

  test('a needle that only appears in the query string is the caller\'s business', () => {
    // `warnIfOverbroad` passes the pathname alone, so a needle aimed at a query parameter simply
    // is not found — and must not be reported as broad.
    expect(isOverbroad('group=g1', '/v2/teacher/courses/1/exercises')).toBe(false)
  })

  test('a needle matching the entire path is exact, not broad', () => {
    expect(isOverbroad('/v2/courses/1/moodle', '/v2/courses/1/moodle')).toBe(false)
  })
})

test('the rule is about a following slash, not about length', () => {
  // The distinction is structural: what follows the match has to start a new path segment.
  expect(isOverbroad('/a', '/a/b')).toBe(true)
  expect(isOverbroad('/a', '/ab')).toBe(false)
  expect(isOverbroad('/a', '/a')).toBe(false)
})
