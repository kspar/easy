import type { AdminSimilarityLanguage, AdminSimilarityProvider, AdminSimilarityProviderReq } from '../../api/types.ts'

/**
 * The admin's similarity providers page (EZ-1667): what an edited provider becomes, and what each
 * language row says.
 *
 * Here rather than in the page because both are wrong without a sign. A language sent as an override
 * when the admin only left it as it was stops following the provider's default - an experimental
 * language a later release calls stable would stay off on this server, set by nobody. And a language
 * row that says "JPlag" where JPlag compares it as text claims a token comparison that is not happening.
 */

/** One provider as the admin is editing it: the priority as typed, so that half-typed input stays put. */
export interface ProviderDraft {
  enabled: boolean
  priority: string
  languages: Record<string, boolean>
}

export const MAX_PRIORITY = 1000

export function draftOf(provider: AdminSimilarityProvider): ProviderDraft {
  return {
    enabled: provider.enabled,
    priority: String(provider.priority),
    languages: Object.fromEntries(provider.languages.map((l) => [l.language, l.enabled])),
  }
}

/** A whole number from 0 to [MAX_PRIORITY], which is what core accepts; null for anything else. */
export function parsedPriority(draft: ProviderDraft): number | null {
  const text = draft.priority.trim()
  if (!/^\d+$/.test(text)) return null
  const value = Number(text)
  return value <= MAX_PRIORITY ? value : null
}

/** What a language is on by default: stable ones are, experimental ones wait for an admin. */
function languageDefault(support: string): boolean {
  return support === 'stable'
}

/**
 * The provider's settings as core takes them: only the languages that differ from their default go as
 * overrides. Core replaces the provider's overrides with the list, so one left out follows its default
 * again - which is what a language set back to its default should do.
 */
export function updateRequest(provider: AdminSimilarityProvider, draft: ProviderDraft): AdminSimilarityProviderReq | null {
  const priority = parsedPriority(draft)
  if (priority === null) return null
  return {
    enabled: draft.enabled,
    priority,
    languages: provider.languages
      .filter((l) => (draft.languages[l.language] ?? l.enabled) !== languageDefault(l.support))
      .map((l) => ({ language: l.language, enabled: draft.languages[l.language] ?? l.enabled })),
  }
}

/** The provider's own defaults: on, at its default priority, every language as its support says. */
export function defaultsRequest(provider: AdminSimilarityProvider): AdminSimilarityProviderReq {
  return { enabled: true, priority: provider.default_priority, languages: [] }
}

/** Whether "restore defaults" would change anything. */
export function differsFromDefaults(provider: AdminSimilarityProvider): boolean {
  return !provider.enabled || provider.priority !== provider.default_priority ||
    provider.languages.some((l) => l.enabled !== languageDefault(l.support))
}

/**
 * What moves [providers]`[index]` one place up the order, which is by priority and then by id: the two
 * priorities swapped where they differ, so no other provider moves; where they are equal, the moved one
 * raised by one - or, at the top of the range, the other lowered. A number from 0 to 1000 was the control
 * for ordering two providers, and an admin had to work out which way was up.
 */
export function moveUpRequests(providers: AdminSimilarityProvider[], index: number): { id: string; body: AdminSimilarityProviderReq }[] {
  if (index <= 0 || index >= providers.length) return []
  const moved = providers[index]
  const above = providers[index - 1]
  const at = (provider: AdminSimilarityProvider, priority: number) => {
    const body = updateRequest(provider, draftOf(provider))
    return body ? [{ id: provider.id, body: { ...body, priority } }] : []
  }
  if (moved.priority < above.priority) return [...at(moved, above.priority), ...at(above, moved.priority)]
  if (above.priority < MAX_PRIORITY) return at(moved, above.priority + 1)
  return at(above, above.priority - 1)
}

/** How many courses and course exercises chose [provider]: none for an older core, which does not say. */
export function pinnedCount(provider: AdminSimilarityProvider): number {
  return (provider.pinned_courses ?? 0) + (provider.pinned_exercises ?? 0)
}

/** What a language row says about who compares it. */
export type LanguageVerdict =
  | { kind: 'off' }
  | { kind: 'nobody' }
  | { kind: 'provider'; provider: string; asText: boolean }

export function languageVerdict(row: AdminSimilarityLanguage): LanguageVerdict {
  if (!row.enabled) return { kind: 'off' }
  if (row.provider === null) return { kind: 'nobody' }
  return { kind: 'provider', provider: row.provider, asText: row.compared_as !== null && row.compared_as !== row.language }
}
