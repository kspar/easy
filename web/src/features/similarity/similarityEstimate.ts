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
  | { kind: 'nothing'; submissions: number }
  | { kind: 'fits'; submissions: number; pairs: number; seconds: number }
  | { kind: 'tooCostly'; submissions: number; pairs: number; seconds: number; budget: number }
  | { kind: 'partial'; submissions: number; pairs: number; seconds: number; budget: number }

const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0

export function estimateView(estimate: Partial<SimilarityEstimateResp> | undefined): EstimateView {
  if (!estimate) return { kind: 'unknown' }
  const { submissions, pairs, estimated_sec: seconds, budget_sec: budget, within_budget: fits, optimised } = estimate
  if (!isCount(submissions) || !isCount(pairs) || !isCount(seconds) || typeof fits !== 'boolean') return { kind: 'unknown' }
  // No pair at all - every solution blank, or too long for this provider - is not "about 0 s". Find still
  // runs it: the result names who could not be compared, and why.
  if (pairs === 0) return { kind: 'nothing', submissions }
  if (fits) return { kind: 'fits', submissions, pairs, seconds }
  // Over the budget, optimised is not refused: it stops at the budget with the likeliest pairs found.
  if (optimised === true) return { kind: 'partial', submissions, pairs, seconds, budget: isCount(budget) ? budget : 0 }
  return { kind: 'tooCostly', submissions, pairs, seconds, budget: isCount(budget) ? budget : 0 }
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
