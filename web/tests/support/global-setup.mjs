/**
 * Empties the file specs append their check counts to, so a run's counts are that run's.
 *
 * Without this, `npm run test:browser:record` would happily record a spec that was deleted three
 * runs ago, and a filtered run's counts would be merged with a full run's — both of which turn the
 * ratchet into a number nobody can reason about.
 */
import { rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { neverIndex } from './never-index.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))

export default function globalSetup() {
  rmSync(join(HERE, '../../test-results/check-counts.jsonl'), { force: true })

  // The HTML report is written at the very end, so marking its directory here survives — unlike
  // test-results, which Playwright empties after this runs. See support/never-index.mjs.
  neverIndex(join(HERE, '../../playwright-report'))
}
