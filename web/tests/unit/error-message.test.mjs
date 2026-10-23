/**
 * `errorMessage` has a sentence for every error code core can send.
 *
 * A code without one falls back to "Something went wrong", which is the bug `errorMessage.ts` was
 * written to end — and nothing notices, because the fallback is a perfectly good-looking sentence.
 * That is how `TEXT_CONTAINS_NUL` would have shipped: core refusing a solution with a NUL character
 * in it, by name, and the student told only that something went wrong with a solution that looks
 * fine, since the character is invisible.
 *
 * So the list of codes is read from core's own enum, not copied here, and a new code in core fails
 * this until it is given a sentence or added to [DELIBERATELY_ABSENT] with a reason.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, test, vi } from 'vitest'

// client.ts loads runtime config at import; errorMessage only needs its ApiResponseError class.
vi.mock('../../src/config.ts', () => ({ default: { emsRoot: 'https://api.test/v2' } }))

const { ApiResponseError } = await import('../../src/api/client.ts')
const { errorMessage } = await import('../../src/api/errorMessage.ts')

const HERE = dirname(fileURLToPath(import.meta.url))
const ERROR_RESPONSE = join(HERE, '../../../core/src/main/kotlin/core/exception/error_response.kt')

/** Each one with the reason, as in the comment at the bottom of `CODE_KEYS`. */
const DELIBERATELY_ABSENT = new Set([
  // X-018 shows the compiler's own diagnostic instead; a one-liner would be a downgrade.
  'TSL_COMPILE_FAILED',
])

const coreCodes = [...readFileSync(ERROR_RESPONSE, 'utf8').matchAll(/^\s*([A-Z][A-Z0-9_]+)\("\1"\)/gm)].map(
  (m) => m[1],
)

/** The key itself, so a test reads which sentence was chosen rather than its English. */
const t = (key) => key

const messageFor = (code) => errorMessage(new ApiResponseError(400, { id: 'x', code, attrs: {}, log_msg: '' }), t)

describe('every code core sends has a sentence', () => {
  test('the enum was read at all', () => {
    // A regex that matches nothing would make the test below pass vacuously.
    expect(coreCodes.length).toBeGreaterThan(20)
    expect(coreCodes).toContain('ENTITY_WITH_ID_NOT_FOUND')
  })

  test.each(coreCodes.filter((c) => !DELIBERATELY_ABSENT.has(c)))('%s', (code) => {
    expect(messageFor(code)).not.toBe('general.somethingWentWrong')
  })

  test('the deliberate absences are still codes core sends', () => {
    for (const code of DELIBERATELY_ABSENT) expect(coreCodes).toContain(code)
  })
})

test('a NUL character in the text gets its own sentence, not the generic invalid-value one', () => {
  expect(messageFor('TEXT_CONTAINS_NUL')).toBe('errors.textContainsNul')
})
