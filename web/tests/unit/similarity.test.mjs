/**
 * What the similarity page decides from a result, where being wrong is invisible: a highlight one line
 * off, a student named under the wrong reason, pairs in the wrong order, or a refusal answered with
 * advice that cannot help all read as plausibly as the right answer.
 */
import { describe, expect, test } from 'vitest'
import { matchEntries, matchedLineNumbers } from '../../src/features/similarity/similarityHighlight.ts'
import { isSimilarityBusy, isSimilarityRefusal, orderPairs, skippedByReason } from '../../src/features/similarity/similarityResult.ts'
import { ApiResponseError } from '../../src/api/client.ts'
import { errorMessage } from '../../src/api/errorMessage.ts'

describe('the lines a JPlag match paints', () => {
  test('cover each range, overlapping ones once, in order', () => {
    expect(matchedLineNumbers([{ startLine: 4, endLine: 6 }, { startLine: 2, endLine: 5 }], 20)).toEqual([2, 3, 4, 5, 6])
  })

  test('stop at the end of the document, where painting past it would switch the side off', () => {
    expect(matchedLineNumbers([{ startLine: 8, endLine: 12 }], 9)).toEqual([8, 9])
  })

  test('leave out an end core could not place, rather than paint the file from its top', () => {
    // 0 is core's "no line" for a token it could not place.
    expect(matchedLineNumbers([{ startLine: 0, endLine: 3 }], 20)).toEqual([3])
    expect(matchedLineNumbers([{ startLine: 0, endLine: 0 }], 20)).toEqual([])
  })
})

describe('the matched blocks to pick from', () => {
  const match = (from, tokens) => ({ start_line_1: from, end_line_1: from + 1, start_line_2: from + 10, end_line_2: from + 11, tokens })

  test('come largest first, each keeping its place in the response, and those not placed on both sides are left out', () => {
    const { shown, more } = matchEntries([match(1, 6), match(5, 40), { ...match(9, 90), start_line_2: 0 }])
    expect(shown.map((m) => m.index)).toEqual([1, 0])
    expect(more).toBe(0)
  })

  test('stop at thirty, and say how many more', () => {
    const { shown, more } = matchEntries(Array.from({ length: 34 }, (_, i) => match(i + 1, 10)))
    expect(shown).toHaveLength(30)
    expect(more).toBe(4)
  })
})

describe('the order of pairs', () => {
  const pairs = [
    { id: 'long', similarity: 70, max_similarity: 75 },
    { id: 'short copy', similarity: 40, max_similarity: 100 },
    { id: 'tie, first', similarity: 70, max_similarity: 75 },
  ]

  test('by score puts a short copy pasted whole into a longer solution first, and keeps ties as core sent them', () => {
    expect(orderPairs(pairs, 'score').map((p) => p.id)).toEqual(['short copy', 'long', 'tie, first'])
  })

  test("by shared code is core's own order", () => {
    expect(orderPairs(pairs, 'shared').map((p) => p.id)).toEqual(['long', 'short copy', 'tie, first'])
  })
})

describe('who could not be compared', () => {
  const row = (submission, given, family) => ({ submission: { id: submission }, given_name: given, family_name: family })

  test('is named under each reason, suspicious reasons first, names in order, anything unknown under other', () => {
    const groups = skippedByReason(
      [
        { id: 's1', reason: 'TOO_SMALL' },
        { id: 's2', reason: 'TOO_COMPLEX' },
        { id: 's3', reason: 'TOO_SMALL' },
        { id: 's4', reason: 'SOMETHING_NEW' },
        { id: 's5', reason: 'TOO_SMALL' },
      ],
      [row('s1', 'Mari', 'Tamm'), row('s2', 'Jaan', 'Kask'), row('s3', 'Anu', 'Saar'), row('s4', 'Ott', 'Puu')],
    )
    expect(groups.map((g) => [g.reason, g.students.map((s) => s.name)])).toEqual([
      ['TOO_COMPLEX', ['Jaan Kask']],
      ['TOO_SMALL', ['Anu Saar', 'Mari Tamm', null]],
      ['OTHER', ['Ott Puu']],
    ])
  })
})

describe('a refused check', () => {
  const apiError = (code, attrs = {}) => new ApiResponseError(400, code === null ? null : { id: 'x', code, attrs, log_msg: 'failed' })
  const t = (key, options) => `${key} ${JSON.stringify(options ?? {})}`

  test('gets its own sentence, and too many names the limit core sent', () => {
    for (const code of ['TOO_MANY_SUBMISSIONS', 'SIMILARITY_BUSY', 'SIMILARITY_TIMEOUT']) expect(isSimilarityRefusal(apiError(code))).toBe(true)
    expect(errorMessage(apiError('TOO_MANY_SUBMISSIONS', { limit: '500', requested: '731' }), t)).toBe('errors.tooManySubmissionsLimit {"value":"500"}')
    expect(errorMessage(apiError('SIMILARITY_BUSY'), t)).toBe('errors.similarityBusy {}')
  })

  test('but any other failure keeps the advice to try a single group', () => {
    expect(isSimilarityRefusal(apiError('ENTITY_WITH_ID_NOT_FOUND'))).toBe(false)
    expect(isSimilarityRefusal(apiError(null))).toBe(false)
    expect(isSimilarityRefusal(new TypeError('fetch failed'))).toBe(false)
  })

  test('as busy, and only then, makes the page ask whether the running check is the teacher\'s own', () => {
    expect(isSimilarityBusy(apiError('SIMILARITY_BUSY'))).toBe(true)
    for (const code of ['TOO_MANY_SUBMISSIONS', 'SIMILARITY_TIMEOUT', null]) expect(isSimilarityBusy(apiError(code))).toBe(false)
    expect(isSimilarityBusy(new TypeError('fetch failed'))).toBe(false)
  })
})
