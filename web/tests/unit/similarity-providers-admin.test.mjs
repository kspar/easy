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
  languageVerdict,
  moveUpRequests,
  parsedPriority,
  pinnedCount,
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
  test('a provider left as it is has nothing to restore, and saves as it is', () => {
    expect(differsFromDefaults(jplag)).toBe(false)
    expect(updateRequest(jplag, draftOf(jplag))).toEqual({ enabled: true, priority: 50, languages: [] })
  })

  test('only languages that differ from their default are saved as overrides', () => {
    // SQL switched on is an override; Python left on is its default, and stays out so it keeps following it.
    const draft = { ...draftOf(jplag), languages: { python: true, sql: true } }
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
  })

  test('moving a provider up swaps the two priorities, so nothing else moves', () => {
    const edit = { ...jplag, id: 'edit-distance', priority: 100, default_priority: 100 }
    const order = [edit, jplag]
    expect(moveUpRequests(order, 1).map((r) => [r.id, r.body.priority])).toEqual([['jplag', 100], ['edit-distance', 50]])
    // The top one, or a place past the end, has nowhere to go.
    expect(moveUpRequests(order, 0)).toEqual([])
    expect(moveUpRequests(order, 2)).toEqual([])
    // Equal, the order is by id: the moved one goes one above, or at the top of the range the other one below.
    expect(moveUpRequests([{ ...edit, priority: 50 }, jplag], 1).map((r) => [r.id, r.body.priority])).toEqual([['jplag', 51]])
    expect(moveUpRequests([{ ...edit, priority: 1000 }, { ...jplag, priority: 1000 }], 1).map((r) => [r.id, r.body.priority])).toEqual([['edit-distance', 999]])
    // What else is set stays as it is: SQL switched on goes along.
    const sqlOn = { ...jplag, languages: [jplag.languages[0], { ...jplag.languages[1], enabled: true }] }
    expect(moveUpRequests([edit, sqlOn], 1)[0].body.languages).toEqual([{ language: 'sql', enabled: true }])
  })

  test('the courses and exercises that chose a provider are counted, and an older core says none', () => {
    expect(pinnedCount({ ...jplag, pinned_courses: 2, pinned_exercises: 3 })).toBe(5)
    expect(pinnedCount(jplag)).toBe(0)
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
