/**
 * Reconciles tests/a11y-baseline.json against what a **full** run actually found.
 *
 *   npm run test:a11y:record     # rewrite the baseline from this run
 *   npm run test:a11y:check      # fail if any entry no longer fires
 *
 * A spec cannot answer "is this baseline entry stale?" — it only visits its own states, so an entry
 * belonging to a page it never opens looks absent to it and present to the file. Only the whole run
 * can tell, which is why this is a script over `test-results/a11y-found.jsonl` rather than another
 * assertion in the fixture. Exactly the shape `record-checks.mjs` already uses, for the same reason.
 *
 * **Refuses a partial run.** A filtered run visits a handful of states, and treating its findings as
 * the whole truth would delete every entry belonging to a spec that did not run — silently turning
 * a list of known problems into a list of the ones that happen to be on screen today.
 */
import { readFileSync, existsSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { baselinePath, loadBaseline } from './a11y.mjs'
import { specsThatScan } from './spec-inventory.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const FOUND_PATH = join(HERE, '../../test-results/a11y-found.jsonl')

const mode = process.argv[2] === '--check' ? 'check' : 'record'

if (!existsSync(FOUND_PATH)) {
  console.error(
    `No ${FOUND_PATH}. Run the browser suite first — this reads what it found, it does not scan.`,
  )
  process.exit(2)
}

const found = readFileSync(FOUND_PATH, 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((l) => JSON.parse(l))

const scanned = new Set(found.map((f) => f.spec))
const expected = specsThatScan(join(HERE, '../browser'))
const missing = expected.filter((s) => !scanned.has(s))

if (missing.length) {
  console.error(
    `This looks like a partial run: ${missing.length} spec(s) that call a11y() reported nothing.\n` +
      missing.map((s) => `  ${s}`).join('\n') +
      `\n\nRun the whole suite before recording. A filtered run would delete every entry belonging ` +
      `to a spec that did not run.`,
  )
  process.exit(2)
}

// A line with a null fingerprint means "this spec scanned and found nothing" — it exists so that a
// clean run is distinguishable from a run that never happened. Only real findings go in the map.
const byFingerprint = new Map(found.filter((f) => f.fingerprint).map((f) => [f.fingerprint, f]))
const baseline = loadBaseline()
const stale = Object.keys(baseline).filter((k) => !byFingerprint.has(k))
const fresh = [...byFingerprint.keys()].filter((k) => !(k in baseline))

if (mode === 'check') {
  const problems = []
  if (fresh.length) problems.push(`${fresh.length} new: ${fresh.join(', ')}`)
  if (stale.length) {
    problems.push(
      `${stale.length} baseline entr(ies) no longer fire and must be deleted:\n` +
        stale.map((k) => `  ${k}`).join('\n') +
        `\n\nThe baseline is a list of what is still wrong. An entry that has been fixed makes it a ` +
        `worse list, and the file is meant to only ever shrink.`,
    )
  }
  if (problems.length) {
    console.error(problems.join('\n\n'))
    process.exit(1)
  }
  console.log(`a11y baseline is in step: ${Object.keys(baseline).length} known finding(s), none stale.`)
  process.exit(0)
}

const existing = JSON.parse(readFileSync(baselinePath, 'utf8'))
const known = {}
for (const [fp, f] of byFingerprint) {
  known[fp] = existing.known?.[fp] ?? {
    note: `TODO — say what this costs a user: ${f.help}`,
    issue: 'TODO',
    impact: f.impact,
    firstSeenAt: f.state,
  }
}
writeFileSync(baselinePath, JSON.stringify({ ...existing, known }, null, 2) + '\n')

console.log(`Wrote ${Object.keys(known).length} finding(s) to ${baselinePath}.`)
if (fresh.length) console.log(`  ${fresh.length} new — replace the TODO note and issue before committing.`)
if (stale.length) console.log(`  ${stale.length} removed: ${stale.join(', ')}`)
