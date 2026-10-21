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

export function isChanged(provider: AdminSimilarityProvider, draft: ProviderDraft): boolean {
  const current = draftOf(provider)
  return draft.enabled !== current.enabled ||
    parsedPriority(draft) !== provider.priority ||
    provider.languages.some((l) => draft.languages[l.language] !== l.enabled)
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
