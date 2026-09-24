/**
 * List prices for the cost estimate in the AI settings dialog (EZ-1712).
 *
 * USD per million tokens, input and output, from Anthropic's published pricing as of 2026-09-24.
 * An estimate, not an invoice: prices change, prompt caching and batch discounts are not modelled,
 * and the split between input and output is assumed rather than measured until the course has
 * usage of its own. Keyed by model-id prefix so that a dated snapshot id (`claude-opus-5-2026…`)
 * still matches. An unknown model gets no estimate rather than a wrong one.
 */
const PRICES_USD_PER_MTOK: { prefix: string; input: number; output: number }[] = [
  { prefix: 'claude-fable-5', input: 10, output: 50 },
  { prefix: 'claude-opus-5', input: 5, output: 25 },
  { prefix: 'claude-opus-4', input: 5, output: 25 },
  { prefix: 'claude-sonnet-5', input: 2, output: 10 },
  { prefix: 'claude-sonnet-4', input: 3, output: 15 },
  { prefix: 'claude-haiku-4', input: 1, output: 5 },
]

/**
 * Share of a course's tokens that are input, when nothing has been measured yet. The prompt
 * carries the exercise, the solution and the whole test log; the answer is five sentences.
 */
const DEFAULT_INPUT_SHARE = 0.9

export function priceFor(model: string): { input: number; output: number } | null {
  const id = model.trim().toLowerCase()
  return PRICES_USD_PER_MTOK.find((p) => id.startsWith(p.prefix)) ?? null
}

/**
 * Estimated cost in USD of `tokens` on `model`, or null when the model is not priced here.
 * `inputShare` is the fraction of those tokens that are input; pass a measured value when the
 * course has one.
 */
export function estimateUsd(model: string, tokens: number, inputShare = DEFAULT_INPUT_SHARE): number | null {
  const price = priceFor(model)
  if (!price || !Number.isFinite(tokens) || tokens <= 0) return null
  const share = Math.min(1, Math.max(0, inputShare))
  return (tokens * share * price.input + tokens * (1 - share) * price.output) / 1_000_000
}

/** `$0.42`, `$12`, `$1,250` — two decimals under ten dollars, whole dollars above. */
export function formatUsd(usd: number): string {
  const digits = usd < 10 ? 2 : 0
  return '$' + usd.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}
