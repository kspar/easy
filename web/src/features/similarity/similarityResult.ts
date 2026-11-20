import { ApiResponseError } from '../../api/client.ts'
import type { SimilarityScoreResp, SimilaritySkippedResp, SubmissionRow } from '../../api/types.ts'

/**
 * What the similarity page makes of a result. Here rather than in the page because each is wrong
 * invisibly: a name under the wrong reason, or pairs in the wrong order, reads as plausibly as the right answer.
 */

/**
 * Codes the check refuses with, each of which gets its own sentence: too many names the limit, a timeout
 * says the time ran out, and busy says to wait - one check runs at a time across the server, so the page's
 * own `similarity.failed`, which suggests a single group, would send the teacher round the same refusal.
 */
const REFUSALS = new Set(['TOO_MANY_SUBMISSIONS', 'SIMILARITY_BUSY', 'SIMILARITY_TIMEOUT'])

export function isSimilarityRefusal(error: unknown): boolean {
  if (!(error instanceof ApiResponseError)) return false
  const code = error.errorBody?.code
  return code != null && REFUSALS.has(code)
}

/**
 * Refused because a check is already running, which may be the teacher's own: one they left by changing
 * the exercise or the page keeps running on the server until it ends, so the page asks whether it is theirs.
 */
export function isSimilarityBusy(error: unknown): boolean {
  return error instanceof ApiResponseError && error.errorBody?.code === 'SIMILARITY_BUSY'
}

/** Whether the pairs carry the lines they match on: JPlag's do; edit distance's say how alike, not where. */
export function usesMatchRanges(provider: string): boolean {
  return provider === 'jplag'
}

/** CodeMirror picks a grammar by extension, so the language core compared in is handed over as a file name. */
export function highlightFileName(language: string): string {
  switch (language) {
    case 'python':
      return 'solution.py'
    case 'sql':
      return 'solution.sql'
    default:
      return 'solution.txt'
  }
}

/** The pairs in core's order - most shared code first - or by the score the threshold filters on. */
export type PairOrder = 'shared' | 'score'

/**
 * [scores] in [order]. By score, the highest maximum first, then the higher average; pairs alike in both
 * keep core's order between them, so the list is the same on every render.
 */
export function orderPairs<T extends Pick<SimilarityScoreResp, 'max_similarity' | 'similarity'>>(scores: T[], order: PairOrder): T[] {
  if (order === 'shared') return scores
  return [...scores].sort((x, y) => y.max_similarity - x.max_similarity || y.similarity - x.similarity)
}

/** Why a submission could not be compared, as the page groups it; anything else core sends is OTHER. */
export type SkippedReason = 'CANNOT_PARSE' | 'TOO_LARGE' | 'TOO_COMPLEX' | 'TOO_REPETITIVE' | 'TOO_SMALL' | 'OTHER'

export interface SkippedStudent {
  submissionId: string
  /** Null when the submission is not among the rows the page compared. */
  name: string | null
}

/**
 * Suspicious first: a solution that runs but could not be compared - padded past the size cap, nested
 * until the parser would take minutes - is the one worth opening, and TOO_SMALL, mostly empty
 * submissions, the least.
 */
const ORDER: readonly SkippedReason[] = ['CANNOT_PARSE', 'TOO_LARGE', 'TOO_COMPLEX', 'TOO_REPETITIVE', 'TOO_SMALL', 'OTHER']

/**
 * Who could not be compared, and why, by name - from the rows the page compared, which carry the names
 * the response does not. A count alone does not say where to look when a skip is not an accident.
 * Named ones alphabetically, any without a name after them.
 */
export function skippedByReason(
  skipped: readonly SimilaritySkippedResp[],
  rows: readonly SubmissionRow[],
): { reason: SkippedReason; students: SkippedStudent[] }[] {
  const nameOf = new Map<string, string>()
  for (const row of rows) if (row.submission) nameOf.set(row.submission.id, `${row.given_name} ${row.family_name}`)

  const groups = new Map<SkippedReason, SkippedStudent[]>()
  for (const s of skipped) {
    const reason = ORDER.includes(s.reason as SkippedReason) ? (s.reason as SkippedReason) : 'OTHER'
    groups.set(reason, [...(groups.get(reason) ?? []), { submissionId: s.id, name: nameOf.get(s.id) ?? null }])
  }
  const byName = (a: SkippedStudent, b: SkippedStudent) =>
    a.name === null ? (b.name === null ? 0 : 1) : b.name === null ? -1 : a.name.localeCompare(b.name)
  return ORDER.filter((reason) => groups.has(reason)).map((reason) => ({ reason, students: [...groups.get(reason)!].sort(byName) }))
}

/** Seconds as a clock: 47 is 0:47, 120 is 2:00. */
export function formatClock(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}
