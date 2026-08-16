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
import { readFileSync, readdirSync } from 'node:fs'
import { basename, join } from 'node:path'

/** True if the spec begins with an unconditional-looking `test.skip(...)` guard. */
export function isAlwaysSkipped(path) {
  return /^\s*test\.skip\(/m.test(readFileSync(path, 'utf8'))
}

/**
 * Specs that call `a11y()`, read from the source rather than kept in a list.
 *
 * `record-a11y.mjs` uses it to tell a full run from a filtered one. A hand-maintained list would
 * drift the first time somebody adds a scan and forgets, and the failure would be the recorder
 * accepting a partial run as the whole truth — which is exactly the mistake the list this module
 * replaced used to make.
 */
export function specsThatScan(browserDir) {
  return readdirSync(browserDir)
    .filter((f) => f.endsWith('.spec.mjs'))
    .map((f) => join(browserDir, f))
    .filter((p) => readFileSync(p, 'utf8').includes('a11y(') && !isAlwaysSkipped(p))
    .map((p) => basename(p))
    .sort()
}
