import type { TFunction } from 'i18next'
import type { SimilarityEstimateResp } from '../../api/types.ts'

/**
 * What the page says before Find, from core's count of what the check would compare (EZ-1667).
 *
 * Counted, not timed: how long a check takes depends on the server and on the code, and the model that
 * once said it was out by four to a hundred times, so the page refused checks that would have run and
 * let through ones that timed out. What a check may take at most - its budget - is a fact, and is what
 * the page says instead. Here rather than in the page because a wrong answer looks like a right one: an
 * answer the page cannot read - an older core with no estimate, a proxy's empty body - must fall back to
 * what the page said before estimates, not to "NaN pairs".
 */
export type EstimateView =
  | { kind: 'unknown' }
  | { kind: 'nothing'; submissions: number; notCompared: number }
  | { kind: 'counted'; submissions: number; pairs: number; budget: number; notCompared: number; mode: SimilarityRunMode | null }

/**
 * What a check does when its budget runs out, by its mode: `optimised` shows the likeliest pairs found by then,
 * `full` is stopped early without results. Said beside the budget, as "at most 2 min" alone was the same for
 * both, and one of them ends with nothing. Null for a mode the page does not know.
 */
export type SimilarityRunMode = 'optimised' | 'full'

const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0

export function estimateView(estimate: Partial<SimilarityEstimateResp> | undefined): EstimateView {
  if (!estimate) return { kind: 'unknown' }
  // An older core's time and verdict are not read: nothing is refused before it runs any more, and Find
  // switched off by them would keep a teacher from a check this core would run.
  const { submissions, pairs, budget_sec: budget } = estimate
  if (!isCount(submissions) || !isCount(pairs) || !isCount(budget)) return { kind: 'unknown' }
  // Those in no pair, said with the count that leaves them out: 27 submissions and 120 pairs, with nothing
  // on why not 351, and after the run 91. An older core does not say, and is taken to compare them all.
  const notCompared = isCount(estimate.not_compared) ? estimate.not_compared : 0
  // No pair at all - every solution blank, too long for this provider, or one text three or more authors
  // handed in, which is one comparand - is not "0 pairs to compare". Find still runs it: the result names
  // who could not be compared and why, or the group that handed in the same text.
  if (pairs === 0) return { kind: 'nothing', submissions, notCompared }
  const mode = estimate.mode === 'optimised' || estimate.mode === 'full' ? estimate.mode : null
  return { kind: 'counted', submissions, pairs, budget, notCompared, mode }
}

/** Seconds as a clock, for the time a running check has taken: 47 is 0:47, 120 is 2:00. */
export function formatClock(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
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
 * from there. "At most 95 s" is a number to convert; "at most 2 min" is not.
 *
 * Rounded up but never raised: a floor of one turned a budget of none into "1 s".
 */
export function formatDuration(seconds: number, t: TFunction): string {
  if (seconds < 90) return t('similarity.durationSeconds', { value: Math.ceil(seconds) })
  return t('similarity.durationMinutes', { value: Math.ceil(seconds / 60) })
}
