import { ApiResponseError } from '../../api/client.ts'

/**
 * Codes this endpoint refuses with, as opposed to failing with.
 *
 * The page's own `similarity.failed` string is written for the common case — a comparison that ran
 * out of time on a big course — and tells the teacher to narrow to one group. That advice is right
 * for a timeout and wrong for both of these: `TOO_MANY_SUBMISSIONS` already says the selection is
 * too large and carries the actual limit, and `SIMILARITY_BUSY` is a global lock, so a smaller group
 * would fail identically. `SIMILARITY_TIMEOUT` carries its own budget and its own advice. Showing
 * the generic timeout sentence for any of them sends the teacher round a loop that cannot
 * terminate.
 *
 * Kept as a set rather than asking `errorMessage` whether it recognised the code, because
 * `errorMessage` always returns *something* — its fallback is the generic "something went wrong",
 * which is a worse answer than the timeout advice for a genuine timeout.
 */
const REFUSALS = new Set(['TOO_MANY_SUBMISSIONS', 'SIMILARITY_BUSY', 'SIMILARITY_TIMEOUT'])

/** True when the failure is a named refusal that deserves its own sentence. */
export function isSimilarityRefusal(error: unknown): boolean {
  if (!(error instanceof ApiResponseError)) return false
  const code = error.errorBody?.code
  return code != null && REFUSALS.has(code)
}
