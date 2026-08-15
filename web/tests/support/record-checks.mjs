/**
 * Rewrites tests/expected-checks.json from what the last run actually reported.
 *
 *   npm run test:browser:record
 *
 * Chained behind the suite with `&&`, so it only ever runs on a green one — recording a broken run
 * would bake the breakage in as the new floor, which is the one way this mechanism can be made
 * worse than useless.
 *
 * Refuses a partial run. A filtered run (`npx playwright test grade-table`) reports one spec, and
 * writing that as the whole file would silently drop the ratchet for the other 26.
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { EXPECTED_CHECKS_PATH } from './expected-checks.mjs'
import { isAlwaysSkipped } from './spec-inventory.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const COUNTS_PATH = join(HERE, '../../test-results/check-counts.jsonl')
const SPEC_DIR = join(HERE, '../browser')

const counts = new Map()
for (const line of readFileSync(COUNTS_PATH, 'utf8').split('\n')) {
  if (!line.trim()) continue
  const { spec, count } = JSON.parse(line)
  counts.set(spec, count)
}

const onDisk = readdirSync(SPEC_DIR).filter((f) => f.endsWith('.spec.mjs')).sort()
const runnable = onDisk.filter((f) => !isAlwaysSkipped(join(SPEC_DIR, f)))
const missing = runnable.filter((f) => !counts.has(f))
if (missing.length) {
  console.error(
    `Refusing to record: ${missing.length} spec(s) did not report a count — ${missing.join(', ')}.\n` +
      `Run the whole suite (\`npx playwright test\`) rather than a filtered subset.`,
  )
  process.exit(1)
}

const previous = JSON.parse(readFileSync(EXPECTED_CHECKS_PATH, 'utf8'))
const doc = Object.fromEntries(Object.entries(previous).filter(([k]) => k.startsWith('_')))
const recorded = Object.fromEntries(runnable.map((f) => [f, counts.get(f)]))

writeFileSync(EXPECTED_CHECKS_PATH, `${JSON.stringify({ ...doc, ...recorded }, null, 2)}\n`)

const total = Object.values(recorded).reduce((a, b) => a + b, 0)
const changes = runnable
  .filter((f) => previous[f] !== recorded[f])
  .map((f) => `${f}: ${previous[f] ?? '—'} → ${recorded[f]}`)

console.log(
  `Recorded ${total} checks across ${runnable.length} specs` +
    `${onDisk.length > runnable.length ? ` (${onDisk.length - runnable.length} always-skipped, not ratcheted)` : ''}.`,
)
if (changes.length) console.log(`Changed:\n  ${changes.join('\n  ')}`)
