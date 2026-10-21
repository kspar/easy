/**
 * The admin's similarity providers page (EZ-1667): what an edited provider is saved as, and what each
 * language row says.
 *
 * Both decide things nobody would see go wrong. A language saved as an override when the admin only left
 * it as it was stops following the provider's default, so an experimental language a later release calls
 * stable stays off here, set by nobody; and a row that names JPlag where JPlag compares the language as
 * text claims a token comparison that is not happening.
 */
import { describe, expect, test } from 'vitest'
import {
  defaultsRequest,
  differsFromDefaults,
  draftOf,
  isChanged,
  languageVerdict,
  parsedPriority,
  updateRequest,
} from '../../src/features/admin/similarityProvidersAdmin.ts'

const jplag = {
  id: 'jplag',
  enabled: true,
  priority: 50,
  default_priority: 50,
  overridden: false,
  languages: [
    { language: 'python', support: 'stable', enabled: true, overridden: false },
    { language: 'sql', support: 'experimental', enabled: false, overridden: false },
  ],
}

describe('the similarity providers admin page', () => {
  test('a provider left as it is has nothing to save, and nothing to restore', () => {
    const draft = draftOf(jplag)
    expect(isChanged(jplag, draft)).toBe(false)
    expect(differsFromDefaults(jplag)).toBe(false)
  })

  test('only languages that differ from their default are saved as overrides', () => {
    // SQL switched on is an override; Python left on is its default, and stays out so it keeps following it.
    const draft = { ...draftOf(jplag), languages: { python: true, sql: true } }
    expect(isChanged(jplag, draft)).toBe(true)
    expect(updateRequest(jplag, draft)).toEqual({ enabled: true, priority: 50, languages: [{ language: 'sql', enabled: true }] })
    // A stable language switched off is one too.
    expect(updateRequest(jplag, { ...draftOf(jplag), languages: { python: false, sql: false } }).languages)
      .toEqual([{ language: 'python', enabled: false }])
  })

  test('the priority is a whole number from 0 to 1000, and nothing else is saved', () => {
    const at = (priority) => parsedPriority({ ...draftOf(jplag), priority })
    expect(at('200')).toBe(200)
    expect(at(' 0 ')).toBe(0)
    expect(at('1000')).toBe(1000)
    expect(at('1001')).toBeNull()
    expect(at('-5')).toBeNull()
    expect(at('2.5')).toBeNull()
    expect(at('')).toBeNull()
    expect(updateRequest(jplag, { ...draftOf(jplag), priority: 'high' })).toBeNull()
    expect(isChanged(jplag, { ...draftOf(jplag), priority: '200' })).toBe(true)
  })

  test('restoring the defaults switches it on at its own priority, with no overrides left', () => {
    const moved = { ...jplag, enabled: false, priority: 200, overridden: true }
    expect(differsFromDefaults(moved)).toBe(true)
    expect(defaultsRequest(moved)).toEqual({ enabled: true, priority: 50, languages: [] })
    // An experimental language switched on is not its default either.
    const sqlOn = { ...jplag, languages: [jplag.languages[0], { ...jplag.languages[1], enabled: true }] }
    expect(differsFromDefaults(sqlOn)).toBe(true)
  })

  test('a language row says who compares it, and when that is as text', () => {
    expect(languageVerdict({ language: 'python', enabled: true, provider: 'jplag', compared_as: 'python' }))
      .toEqual({ kind: 'provider', provider: 'jplag', asText: false })
    expect(languageVerdict({ language: 'java', enabled: true, provider: 'jplag', compared_as: 'text' }))
      .toEqual({ kind: 'provider', provider: 'jplag', asText: true })
    expect(languageVerdict({ language: 'sql', enabled: true, provider: null, compared_as: null })).toEqual({ kind: 'nobody' })
    // Switched off as a whole: refused, whoever could compare it.
    expect(languageVerdict({ language: 'sql', enabled: false, provider: null, compared_as: null })).toEqual({ kind: 'off' })
  })
})
