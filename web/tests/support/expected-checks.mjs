/**
 * How many checks each spec is expected to report — the ratchet that makes a silent early return
 * fail.
 *
 * This is the single highest-leverage guard in the browser suite, and it is here because of a
 * documented failure of this repo's own tests: a script that returns early, swallows a locator
 * error, or throws past its remaining assertions is *green*. The framework cannot tell the
 * difference; only the count can.
 *
 * A spec with no entry is not ratcheted, so writing the 29th spec needs no bookkeeping on day one.
 * Record it with:
 *
 *     npm run test:browser:record
 *
 * which runs the whole suite and rewrites the file from what actually ran. **Only ever run that on
 * a green suite** — recording a broken run bakes the breakage in as the new floor.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const EXPECTED_CHECKS_PATH = join(HERE, '../expected-checks.json')

let cached = null

/** `{ "grade-table.spec.mjs": 6, … }`. Keys starting with `_` are documentation, not specs. */
export function expectedChecks() {
  if (cached) return cached
  // Not wrapped in a try. "This spec has no entry" — the intended case — and "the file is
  // unreadable" are indistinguishable from a catch, and the second silently switches the ratchet
  // off for all 27 specs while the suite stays green. That is exactly what it exists to prevent.
  const raw = JSON.parse(readFileSync(EXPECTED_CHECKS_PATH, 'utf8'))
  cached = Object.fromEntries(Object.entries(raw).filter(([k]) => !k.startsWith('_')))
  return cached
}
