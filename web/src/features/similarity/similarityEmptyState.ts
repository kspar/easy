/**
 * Which sentence to show when the result has nothing to display.
 *
 * Extracted from the page because being wrong here is invisible: both branches render a plausible
 * grey line, and the only way to tell them apart is to know what the numbers were. They shipped the
 * wrong way round — the threshold was blamed when pairs were above it but had no renderable
 * submissions, and "nothing found" was shown when the slider had hidden a 91% pair. A teacher who
 * reads "no similar pairs found" stops looking, which is the expensive direction to get wrong.
 */
export type EmptyReason = 'noPairs' | 'allFiltered'

/**
 * @param scored how many pairs core returned at all
 * @param aboveThreshold how many of those clear the slider
 * @param groups how many groups of identical submissions the result lists beside its pairs
 *
 * The threshold is the explanation exactly when something scored and nothing survived it. If
 * nothing scored, the slider is not the reason whatever its value — and at its default of zero it
 * cannot be. Pairs that clear the threshold but cannot be rendered (a score naming a submission the
 * response does not describe) are not the slider's doing either, so they fall to `noPairs` too.
 *
 * `null` when there is nothing to say: no pair, but groups, which are the result. The slider's
 * sentence is kept even then — hidden along with "no pairs", a threshold that filtered out every pair
 * left a heading of "0 pairs" above the groups and nothing saying the slider had done it.
 */
export function emptyReason(scored: number, aboveThreshold: number, groups = 0): EmptyReason | null {
  if (scored > 0 && aboveThreshold === 0) return 'allFiltered'
  return groups > 0 ? null : 'noPairs'
}

/**
 * Whether the scores are in a shape this page cannot read, so no emptiness sentence applies at all.
 *
 * An older core — which a deploy keeps answering for minutes after the new page is live — sends
 * `score_a`/`score_b` and no `max_similarity`. Every such score fails the threshold filter, so the
 * page reported that no pair clears a threshold of zero, and a teacher read "nothing similar" off a
 * response that had pairs in it.
 */
export function hasUnreadableScores(scores: readonly { max_similarity?: unknown }[]): boolean {
  return scores.some((s) => typeof s.max_similarity !== 'number')
}
