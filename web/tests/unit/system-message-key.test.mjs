/**
 * The dismissal key for system messages (EZ-1790).
 *
 * The bug this replaces: dismissals were keyed on `management_notification.id`, a `bigserial`, so a
 * dismissal recorded against a since-deleted "message 1" silently suppressed a brand-new message 1.
 * Found on dev, where the database is periodically restored from an anonymised production dump and
 * row numbers therefore mean nothing across time.
 *
 * These tests are about the two properties that make the key safe to store: the same message always
 * produces the same key, and different messages never share one.
 */
import { describe, expect, test } from 'vitest'
import { dismissalKey, keepCurrentFormat } from '../../src/components/systemMessageKey.ts'

const base = { message: 'Planned outage at 21:00', severity: 'INFO', link_url: null, link_label: null }

describe('stability', () => {
  test('the same content always gives the same key', () => {
    expect(dismissalKey(base)).toBe(dismissalKey({ ...base }))
  })

  test('the id is not part of it, so a restored database cannot change the answer', () => {
    // The whole point: two rows carrying the same announcement under different ids are the same
    // announcement, and one dismissal covers both.
    expect(dismissalKey({ ...base, id: '1' })).toBe(dismissalKey({ ...base, id: '9999' }))
  })
})

describe('separation', () => {
  test('different text gives a different key', () => {
    expect(dismissalKey(base)).not.toBe(dismissalKey({ ...base, message: 'Planned outage at 22:00' }))
  })

  test('a changed severity gives a different key', () => {
    expect(dismissalKey(base)).not.toBe(dismissalKey({ ...base, severity: 'URGENT' }))
  })

  test('adding a link gives a different key', () => {
    expect(dismissalKey(base)).not.toBe(
      dismissalKey({ ...base, link_url: 'https://example.org', link_label: 'Details' }),
    )
  })

  test('the field separator cannot be forged from message text', () => {
    // Without a separator that cannot occur in the content, these two would hash the same material
    // and collide — one dismissal silencing an unrelated message, which is the bug being fixed.
    const a = dismissalKey({ message: 'a', severity: 'b', link_url: null, link_label: null })
    const b = dismissalKey({ message: 'a b', severity: '', link_url: null, link_label: null })
    expect(a).not.toBe(b)
  })
})

describe('format', () => {
  test('keys are prefixed, so a legacy row id can never look like one', () => {
    const key = dismissalKey(base)
    expect(key.startsWith('sm1_')).toBe(true)
    // Row ids are bare decimals. A key must never be mistakable for one, in either direction.
    expect(/^\d+$/.test(key)).toBe(false)
  })

  test('legacy numeric ids are dropped, so old dismissals stop hiding new messages', () => {
    // This is what makes the fix self-applying: nobody has to clear localStorage.
    expect(keepCurrentFormat(['1', '2', '17'])).toEqual([])
  })

  test('and current-format keys are kept', () => {
    const key = dismissalKey(base)
    expect(keepCurrentFormat(['1', key, '2'])).toEqual([key])
  })
})
