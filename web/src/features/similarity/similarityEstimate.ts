import type { TFunction } from 'i18next'
import type { SimilarityEstimateResp } from '../../api/types.ts'

/**
 * What the page says before Find, from core's estimate of the check (EZ-1667).
 *
 * Here rather than in the page because a wrong answer looks like a right one. Read as "fits" when it
 * does not, the teacher clicks and waits for a refusal the page could have given; read as "too
 * costly" when it fits, Find is switched off for a check that would have run. And an answer the page
 * cannot read - an older core with no estimate, a proxy's empty body - must fall back to what the page
 * said before estimates, not to "about NaN s".
 */
export type EstimateView =
  | { kind: 'unknown' }
  | { kind: 'nothing'; submissions: number; notCompared: number }
  | { kind: 'fits'; submissions: number; pairs: number; seconds: number; notCompared: number }
  | { kind: 'tooCostly'; submissions: number; pairs: number; seconds: number; budget: number; notCompared: number }
  | { kind: 'partial'; submissions: number; pairs: number; seconds: number; budget: number; notCompared: number }

const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0

export function estimateView(estimate: Partial<SimilarityEstimateResp> | undefined): EstimateView {
  if (!estimate) return { kind: 'unknown' }
  const { submissions, pairs, estimated_sec: seconds, budget_sec: budget, within_budget: fits, optimised } = estimate
  if (!isCount(submissions) || !isCount(pairs) || !isCount(seconds) || typeof fits !== 'boolean') return { kind: 'unknown' }
  // Those in no pair, said with the count that leaves them out: 27 submissions and 120 pairs, with nothing
  // on why not 351, and after the run 91. An older core does not say, and is taken to compare them all.
  const notCompared = isCount(estimate.not_compared) ? estimate.not_compared : 0
  // No pair at all - every solution blank, too long for this provider, or one text three or more authors
  // handed in, which is one comparand - is not "about 0 s". Find still runs it: the result names who could
  // not be compared and why, or the group that handed in the same text.
  if (pairs === 0) return { kind: 'nothing', submissions, notCompared }
  if (fits) return { kind: 'fits', submissions, pairs, seconds, notCompared }
  // Over the budget, optimised is not refused: it stops at the budget with the likeliest pairs found.
  if (optimised === true) return { kind: 'partial', submissions, pairs, seconds, budget: isCount(budget) ? budget : 0, notCompared }
  return { kind: 'tooCostly', submissions, pairs, seconds, budget: isCount(budget) ? budget : 0, notCompared }
}

/** Whether the check compares each student's first attempt rather than the latest (EZ-1667). */
export function comparesFirstAttempts(estimate: Partial<SimilarityEstimateResp> | undefined): boolean {
  return estimate?.attempts === 'first'
}

/**
 * Whether the exercise compares by settings of its own (EZ-1667), from where the estimate says each came
 * from: `all` when both are its own, `some` when one is, null when it follows the course's or the
 * defaults - or core does not say. Said on the page, because a teacher who changed the course's settings
 * would otherwise take this exercise's result for one made by them.
 */
export function ownSettings(estimate: Partial<SimilarityEstimateResp> | undefined): 'all' | 'some' | null {
  const sources = [estimate?.provider_from, estimate?.mode_from, estimate?.attempts_from]
  const own = sources.filter((from) => from === 'exercise').length
  if (own === 0) return null
  // All of those core says: an older core says nothing of attempts, and two of two is all it has.
  return own === sources.filter((from) => from !== undefined).length ? 'all' : 'some'
}

/** Refusals the check would give as well, which the estimate already knows: said before Find, not after. */
const REFUSALS = new Set(['TOO_MANY_SUBMISSIONS', 'SIMILARITY_UNAVAILABLE'])

/**
 * The estimate's refusal, if it is one the check would give too (EZ-1667): more submissions than one check
 * takes, or a language nobody may compare. Found in the stress test: the page fell back to "may take
 * long", kept Find on, and gave the reason only after the click. Any other failure - an older core with no
 * estimate, a network error - says nothing about the check, so it is not one.
 */
export function estimateRefusal(error: unknown): string | null {
  const code = (error as { errorBody?: { code?: unknown } | null } | null | undefined)?.errorBody?.code
  return typeof code === 'string' && REFUSALS.has(code) ? code : null
}

/**
 * A duration as a teacher reads it: seconds under a minute and a half, whole minutes, rounded up,
 * from there. "About 95 s" is a number to convert; "about 2 min" is not.
 *
 * Rounded up but never raised: core already rounds an estimate up to a whole second, and a floor of
 * one here turned a budget of none into "1 s" - "about 1 s, longer than the 1 s a check may run".
 */
export function formatDuration(seconds: number, t: TFunction): string {
  if (seconds < 90) return t('similarity.durationSeconds', { value: Math.ceil(seconds) })
  return t('similarity.durationMinutes', { value: Math.ceil(seconds / 60) })
}
