/**
 * Reads a `--repeat-each` run and says which specs were *intermittent*.
 *
 *   npx playwright test --repeat-each=5 --reporter=json > repeat-report.json
 *   node tests/support/flake-report.mjs repeat-report.json
 *
 * The suite runs with `retries: 0`, on the argument that a retry turns an intermittent failure into
 * a green build plus a log line nobody reads. That argument only holds if something else goes
 * looking for intermittence — otherwise a spec that fails one run in twenty is just a spec that
 * fails for whoever happens to push. This is that something.
 *
 * **Neither 5/5 nor 0/5 is the signal.** A spec that fails every time is a real failure and is
 * already red in CI; reporting it here would make the nightly a duplicate. A spec that fails
 * *sometimes* is the class the main gate structurally cannot see.
 *
 * ### Why this is a file and not four lines of `node -e` in the workflow
 *
 * Because the four lines were wrong. `--repeat-each=N` does not put N results under one spec — it
 * emits **N separate spec entries with the same title** — and the first version keyed a Map by
 * title with `.set()`, so it kept only the last repeat and reported every spec as 1/1. A flake
 * hunter that cannot see a flake is the exact failure this whole programme keeps finding, and it
 * was invisible until a report with a known-intermittent spec was fed to it. In a file it can be
 * unit-tested; in YAML it could not.
 */
import { readFileSync } from 'node:fs'

/**
 * Aggregate a Playwright JSON report into `title -> { total, failed }`.
 *
 * Accumulates across spec entries rather than overwriting, which is the whole correction above.
 * Skipped runs count as neither: a spec that skips (a live-core spec without a core) is absent, not
 * flaky, and counting a skip as a pass would let a spec that stopped running look perfectly stable.
 */
export function tally(report) {
  const byTitle = new Map()

  const walk = (suite) => {
    for (const s of suite.suites ?? []) walk(s)
    for (const spec of suite.specs ?? []) {
      const runs = (spec.tests ?? []).flatMap((t) => t.results ?? [])
      const counted = runs.filter((r) => r.status !== 'skipped')
      if (!counted.length) continue

      const acc = byTitle.get(spec.title) ?? { total: 0, failed: 0 }
      acc.total += counted.length
      acc.failed += counted.filter((r) => r.status !== 'passed').length
      byTitle.set(spec.title, acc)
    }
  }
  for (const s of report.suites ?? []) walk(s)
  return byTitle
}

/** `{ intermittent, alwaysFailing, total }` — the three states worth distinguishing. */
export function classify(byTitle) {
  const entries = [...byTitle]
  return {
    total: entries.length,
    intermittent: entries.filter(([, v]) => v.failed > 0 && v.failed < v.total),
    alwaysFailing: entries.filter(([, v]) => v.failed === v.total),
  }
}

export function describe({ total, intermittent, alwaysFailing }) {
  const lines = [`${total} spec(s) run.`]

  if (alwaysFailing.length) {
    lines.push('', `${alwaysFailing.length} failed every time — real failures, not flakes:`)
    for (const [t, v] of alwaysFailing) lines.push(`  ${t}  ${v.failed}/${v.total}`)
  }
  if (intermittent.length) {
    lines.push('', `${intermittent.length} INTERMITTENT — neither always-pass nor always-fail:`)
    for (const [t, v] of intermittent) lines.push(`  ${t}  failed ${v.failed} of ${v.total}`)
    lines.push(
      '',
      'Each of these is a bug: usually a race in the app, occasionally one in the spec.',
      'The suite runs with retries: 0 so that these cannot hide. File them.',
    )
  }
  if (!intermittent.length && !alwaysFailing.length) {
    lines.push('', 'Nothing intermittent. Every spec passed every time.')
  }
  return lines.join('\n')
}

// Run directly: print the report and exit non-zero only on intermittence. A spec broken every time
// is already failing the main build, and failing here for it would make this a duplicate signal
// rather than a new one.
if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/^.*\//, ''))) {
  const path = process.argv[2] ?? 'repeat-report.json'
  const result = classify(tally(JSON.parse(readFileSync(path, 'utf8'))))
  console.log(describe(result))
  process.exit(result.intermittent.length ? 1 : 0)
}
