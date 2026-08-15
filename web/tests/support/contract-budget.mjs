/**
 * Each spec's allowed number of contract warnings, or null if it has no entry.
 *
 * Keyed by spec filename, read fresh each run. A spec with no entry is not ratcheted — new specs
 * should not have to think about this on the day they are written, and the entry gets added once
 * its number settles. See tests/contract-baseline.json and support/contract.mjs.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const BASELINE_PATH = join(HERE, '../contract-baseline.json')

export function contractBudget(spec) {
  // Deliberately NOT wrapped in a try. A bare catch here could not tell "this spec has no entry"
  // — the intended case — from "the baseline file is unreadable", and the second silently turns the
  // ratchet into a no-op for all 27 specs while the suite stays green. That is precisely the
  // failure the ratchet exists to prevent, so a trailing comma or a bad merge must be loud.
  const budgets = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'))
  return Object.prototype.hasOwnProperty.call(budgets, spec) ? budgets[spec] : null
}
