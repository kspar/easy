/**
 * Keeps macOS Spotlight out of the directories the browser suite churns.
 *
 * A full run writes something like a hundred screenshots, a trace per failing spec, and a
 * `test-results/` tree, and then the next run overwrites the lot. On macOS that is a standing
 * invitation to `mds_stores`, which indexes every one of them.
 *
 * **This is not a micro-optimisation; it was measured.** On 2026-08-16 the suite went from 4
 * minutes to 36 and failed six specs, including a four-check one that normally takes four seconds.
 * Load average was 52 with Spotlight at 93% CPU. Every one of those failures was a timing
 * assertion, which is exactly what a saturated machine breaks first — and the suite is deliberately
 * `retries: 0`, so a contention flake costs a red run rather than a quiet re-try. Indexing
 * disposable output made the tests that guard the product unreliable.
 *
 * An empty `.metadata_never_index` file at the top of a directory tells Spotlight to skip that
 * directory and everything under it. No root, no System Settings, no per-machine setup — which is
 * the point: a fix that lives in someone's Spotlight Privacy pane is a fix the next clone does not
 * get.
 *
 * Written from code rather than committed, because every directory it applies to is gitignored: a
 * marker file checked in beside them would be deleted the first time the directory was cleaned.
 * Harmless everywhere else — on Linux and in CI it is an unread empty file.
 */
import { mkdirSync, writeFileSync } from 'node:fs'

/** Create `dir` if needed and mark it as not worth indexing. Never throws. */
export function neverIndex(dir) {
  try {
    mkdirSync(dir, { recursive: true })
    // `flag: 'wx'` would fail once it exists; plain write is idempotent and cheaper than a stat.
    writeFileSync(`${dir}/.metadata_never_index`, '')
  } catch {
    // A read-only checkout, an exotic filesystem, a race with another worker — none of it is worth
    // failing a test run over. The cost of losing this is a slower machine, not a wrong result.
  }
}
