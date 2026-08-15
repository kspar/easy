/**
 * Which specs the gate can hold to a check count.
 *
 * All but one of them. `library-exercise-tsl-live.spec.mjs` calls `test.skip()` unless a real core
 * is running, so it reports no checks in CI and ratcheting it would fail every run.
 *
 * Derived from the source rather than kept as a list, because a list is the thing this migration
 * removed: the old runner named its 27 scripts explicitly, and the 28th on disk simply never ran —
 * for months, unnoticed. A spec that opts out has to say so in itself, where the next reader is.
 */
import { readFileSync } from 'node:fs'

/** True if the spec begins with an unconditional-looking `test.skip(...)` guard. */
export function isAlwaysSkipped(path) {
  return /^\s*test\.skip\(/m.test(readFileSync(path, 'utf8'))
}
