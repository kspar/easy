import type { TFunction } from 'i18next'
import type { SimilarityMetricResp, SimilarityResp, SimilarityScoreResp } from '../../api/types.ts'
import { languageNote } from './similarityLanguage.ts'

/**
 * What the page makes of which provider measured a result (EZ-1667): which notes it owes the teacher,
 * which numbers it shows for a pair, and how it finds the shared code.
 *
 * Here rather than in the page because every one of these is wrong invisibly. A JPlag note under an
 * edit-distance result tells the teacher renaming does not lower a score that renaming lowers; a
 * pinned provider quietly passed over reads as the course's choice having run.
 */

/** The providers the page has words for. Any other is named by its id and described by nothing. */
export type KnownProvider = 'jplag' | 'edit-distance'

export function knownProvider(id: string | null | undefined): KnownProvider | null {
  return id === 'jplag' || id === 'edit-distance' ? id : null
}

/** A provider's name for a teacher: a switch of literal keys, so the i18n test can see each one. */
export function providerName(id: string, t: TFunction): string {
  switch (knownProvider(id)) {
    case 'jplag':
      return t('similarity.providerJplag')
    case 'edit-distance':
      return t('similarity.providerEditDistance')
    default:
      return id
  }
}

/** What choosing a provider means for the scores, or null for one the page has no words for. */
export function providerHelp(id: string, t: TFunction): string | null {
  switch (knownProvider(id)) {
    case 'jplag':
      return t('similarity.providerJplagHelp')
    case 'edit-distance':
      return t('similarity.providerEditDistanceHelp')
    default:
      return null
  }
}

/** How a result was compared, said with the result. */
export function comparedBy(id: string, t: TFunction): string {
  switch (knownProvider(id)) {
    case 'jplag':
      return t('similarity.comparedByJplag')
    case 'edit-distance':
      return t('similarity.comparedByEditDistance')
    default:
      return t('similarity.comparedByOther', { provider: id })
  }
}

/** A language as a teacher calls it. */
export function languageName(language: string, t: TFunction): string {
  switch (language) {
    case 'python':
      return 'Python'
    case 'java':
      return 'Java'
    case 'sql':
      return 'SQL'
    case 'text':
      return t('similarity.settingsPlainText')
    default:
      return language
  }
}

/**
 * Whether the provider compares language tokens - so renaming does not lower its score, and the
 * language it ran as is worth a note. A core older than providers sends no provider; its scores were
 * JPlag's.
 */
export function comparesTokens(result: Pick<SimilarityResp, 'provider'>): boolean {
  return result.provider === undefined || result.provider === 'jplag'
}

/**
 * Whether to paint the provider's own matches, or find the shared code with a character diff. Only an
 * explicit `false` means the latter: a core older than `match_ranges` sent JPlag's matches.
 */
export function usesMatchRanges(result: Pick<SimilarityResp, 'match_ranges'>): boolean {
  return result.match_ranges !== false
}

export type ResultNote = 'pinnedUnavailable' | 'languageUnavailable' | 'languageText' | 'languageSql'

/**
 * The notes owed above the pairs, in the order shown.
 *
 * - The course pinned a provider that may not compare this language now, and another one did.
 * - No provider may compare it, so it was compared as text - said once, as that, rather than also as
 *   the generic "compared as plain text" note, which guesses at a cause this one names.
 * - Otherwise, what the language the tokens were read as means for the score; only for a provider that
 *   reads tokens at all, since edit distance compares every language as characters.
 */
export function resultNotes(result: Pick<SimilarityResp, 'provider' | 'provider_fallback' | 'language'>): ResultNote[] {
  const notes: ResultNote[] = []
  if (result.provider_fallback === 'PINNED_UNAVAILABLE') notes.push('pinnedUnavailable')
  if (result.provider_fallback === 'LANGUAGE_UNAVAILABLE') {
    notes.push('languageUnavailable')
    return notes
  }
  if (!comparesTokens(result)) return notes
  const language = languageNote(result.language)
  if (language === 'text') notes.push('languageText')
  if (language === 'sql') notes.push('languageSql')
  return notes
}

/**
 * How much of the cohort a result covers, when it is not all of it: an optimised run that stopped at the
 * time limit (`incomplete`, a warning - a more similar pair may be among the rest), or one that ruled pairs
 * out because they could not be among the most similar (`ruledOut`, a note). Null for a result that
 * compared every pair, and for an older core that does not say.
 */
export type Coverage = { kind: 'incomplete' | 'ruledOut'; compared: number; total: number }

export function coverage(result: Pick<SimilarityResp, 'complete' | 'pairs_compared' | 'pairs_total'>): Coverage | null {
  const { complete, pairs_compared: compared, pairs_total: total } = result
  if (typeof compared !== 'number' || typeof total !== 'number') return null
  if (complete === false) return { kind: 'incomplete', compared, total }
  if (compared < total) return { kind: 'ruledOut', compared, total }
  return null
}

/**
 * Whether to say the answer was cut to the top pairs by each order. Not for a run that stopped at the time
 * limit: core flags that `truncated` too, which is all a page older than `complete` can be told, but this
 * page says so in [coverage]'s warning - and the cap notice above a list of twelve claimed a hundred.
 */
export function capped(result: Pick<SimilarityResp, 'truncated' | 'complete'>): boolean {
  return result.truncated && result.complete !== false
}

/**
 * The numbers to show for a pair, in the order the provider sent them. A core older than metrics sends
 * none, and the two scores every response carries stand in, as JPlag's two similarities.
 */
export function pairMetrics(score: Pick<SimilarityScoreResp, 'metrics' | 'similarity' | 'max_similarity'>): SimilarityMetricResp[] {
  if (score.metrics && score.metrics.length > 0) return score.metrics
  return [
    { id: 'similarity', value: score.similarity, unit: 'percent' },
    { id: 'max_similarity', value: score.max_similarity, unit: 'percent' },
  ]
}
