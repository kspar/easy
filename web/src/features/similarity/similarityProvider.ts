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
 * - The exercise or course pinned a provider that may not compare this language now, and another one did:
 *   see [passedOver] for what it says.
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
 * A pinned provider passed over: which one was chosen, whose choice it was, and which compared instead and
 * in what language - from the estimate before Find or the result after it, which say it alike. A teacher
 * who pinned JPlag on an SQL exercise, where JPlag's SQL is off until an admin switches it on, read edit
 * distance's text similarities as JPlag's: the note named neither method nor language, sat under three
 * other lines, and said "this course" of a choice made for the exercise.
 */
export interface PassedOver {
  /** Null from a core older than `pinned_provider`, which says only that the choice did not hold. */
  pinned: string | null
  provider: string
  language: string
  /** Whose choice it was; null where core does not say. */
  from: 'exercise' | 'course' | null
}

export function passedOver(
  answer: Partial<Pick<SimilarityResp, 'provider' | 'provider_fallback' | 'language' | 'pinned_provider' | 'provider_from'>> | undefined,
): PassedOver | null {
  if (answer?.provider_fallback !== 'PINNED_UNAVAILABLE' || !answer.provider) return null
  const from = answer.provider_from
  return {
    pinned: answer.pinned_provider ?? null,
    provider: answer.provider,
    language: answer.language ?? '',
    from: from === 'exercise' || from === 'course' ? from : null,
  }
}

/** [over] as a sentence: by name where core says what was pinned, in the old words where it does not. */
export function passedOverText(over: PassedOver, t: TFunction): string {
  if (over.pinned === null || !over.language) return t('similarity.pinnedUnavailable')
  const args = { pinned: providerName(over.pinned, t), language: languageName(over.language, t), provider: providerName(over.provider, t) }
  return over.from === 'exercise'
    ? t('similarity.pinnedPassedOverExercise', args)
    : t('similarity.pinnedPassedOverCourse', args)
}

/**
 * How much of the cohort a result covers, when it is not all of it: an optimised run that stopped at the
 * time limit (`incomplete`, a warning - a more similar pair may be among the rest), or one that ruled pairs
 * out because they could not be among the most similar (`ruledOut`, a note). Null for a result that
 * compared every pair, and for an older core that does not say.
 */
export type Coverage = { kind: 'incomplete' | 'ruledOut'; compared: number; total: number; share: number }

/**
 * [share] is the compared pairs as a whole percent, never 0 when some were and never 100 when some were
 * not: "2,622 of 30,135" asked the teacher to do the sum that says how thin a result is.
 */
export function coverage(result: Pick<SimilarityResp, 'complete' | 'pairs_compared' | 'pairs_total'>): Coverage | null {
  const { complete, pairs_compared: compared, pairs_total: total } = result
  if (typeof compared !== 'number' || typeof total !== 'number') return null
  const exact = total > 0 ? (100 * compared) / total : 100
  const share = compared === 0 ? 0 : compared >= total ? 100 : Math.min(99, Math.max(1, Math.round(exact)))
  if (complete === false) return { kind: 'incomplete', compared, total, share }
  if (compared < total) return { kind: 'ruledOut', compared, total, share }
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
 * How the cap notice reads, when there is one: by both orders where the pairs rank by shared code, by score
 * alone where the rank is the score. Under edit distance it named "the pairs sharing the most code", an
 * order that result has not got.
 */
export function cappedNote(result: Pick<SimilarityResp, 'truncated' | 'complete' | 'scores'>): 'capped' | 'cappedByScore' | null {
  if (!capped(result)) return null
  return ranksBySharedCode(result) ? 'capped' : 'cappedByScore'
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

/**
 * Numbers measured but shown for reference only, in the opened pair rather than its row. Dice over bigram
 * sets is high for any two programs in one language - they share their keywords and indentation - so in
 * the row, as a chip like the score's, it read as a second opinion: 94% beside a text similarity of 55%.
 */
const FOR_REFERENCE = new Set(['dice'])

export function forReference(metric: Pick<SimilarityMetricResp, 'id'>): boolean {
  return FOR_REFERENCE.has(metric.id)
}

/** The numbers in a pair's row: [pairMetrics], less those for reference only. */
export function rowMetrics(score: Pick<SimilarityScoreResp, 'metrics' | 'similarity' | 'max_similarity'>): SimilarityMetricResp[] {
  return pairMetrics(score).filter((metric) => !forReference(metric))
}

/** The pairs in core's order - most shared code first - or by the score the threshold filters on. */
export type PairOrder = 'shared' | 'score'

/**
 * Whether the pairs are ranked by shared code rather than by score, so that ordering by score is worth
 * offering: JPlag's are, and a short copy at 100% sat below long pairs at 75%, with the reason only in a
 * tooltip. Edit distance's rank is its score, and an older core says nothing either way.
 */
export function ranksBySharedCode(result: Pick<SimilarityResp, 'scores'>): boolean {
  return result.scores.some((score) => (score.metrics ?? []).some((metric) => metric.id === 'shared_tokens'))
}

/**
 * [scores] in [order]. By score, the highest maximum first and then the higher average; pairs alike in both
 * keep core's order between them, which is shared code first, so the answer is the same on every render.
 */
export function orderPairs<T extends Pick<SimilarityScoreResp, 'max_similarity' | 'similarity'>>(scores: T[], order: PairOrder): T[] {
  if (order === 'shared') return scores
  return [...scores].sort((x, y) => y.max_similarity - x.max_similarity || y.similarity - x.similarity)
}
