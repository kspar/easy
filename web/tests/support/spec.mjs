/**
 * The `test` every browser spec imports: Playwright's, plus the two things it does not give you.
 *
 *     import { test } from '../support/spec.mjs'
 *
 *     test('grade table', async ({ launch, check }) => { … })
 *
 * **`check`** is the recorder this suite has always had — it records a result and keeps going, so
 * one run reports every problem rather than the first. It is `expect.soft` underneath, so the
 * framework owns pass/fail, and the labels are unchanged from before the migration.
 *
 * **`launch`** opens a context (see harness.mjs). It is a fixture rather than an import so that
 * contexts a spec forgets get closed, and so a failing spec is screenshotted before they go.
 *
 * Three gate mechanisms hang off `check`'s teardown, and all three exist because of the same
 * documented failure: **a browser test that stops early looks exactly like one that passed.** An
 * early return, a swallowed locator error, a `waitFor` that throws past the remaining assertions —
 * every one of those is green today.
 *
 * 1. **The check-count ratchet.** A spec must report at least as many checks as
 *    `tests/expected-checks.json` records. Deleting an assertion is then a build failure rather
 *    than a silent reduction in coverage.
 * 2. **The contract budget** (tests/contract-baseline.json), unchanged from before: fixture drift
 *    against `doc/core/api-shapes.json` may fall, never rise.
 * 3. **Quarantine** (tests/quarantine.json), which replaces `retries: 0`'s missing escape hatch.
 *
 * Failures from these are thrown from teardown rather than asserted softly, because a soft
 * assertion in teardown is easy to lose and these are the ones that must not be losable.
 */
import { test as base, expect } from '@playwright/test'
import { appendFileSync, rmSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { makeLaunch, takeContractIssues } from './harness.mjs'
import { loadQuarantine } from './quarantine.mjs'
import { expectedChecks } from './expected-checks.mjs'
import { neverIndex } from './never-index.mjs'
import { contractBudget } from './contract-budget.mjs'
import { fingerprint, loadBaseline, reconcile, scan } from './a11y.mjs'

export { expect }

const HERE = dirname(fileURLToPath(import.meta.url))

/**
 * Where each spec drops its check count, for `npm run test:browser:record` to collect.
 *
 * A file rather than a return value because Playwright workers are separate processes. Appended to
 * with one small line per spec, which POSIX guarantees is atomic — the alternative, a file per
 * spec, is more moving parts for the same result.
 */
const COUNTS_PATH = join(HERE, '../../test-results/check-counts.jsonl')

/** Accessibility findings from this run, for `record-a11y.mjs`. Same mechanism, same reasons. */
const A11Y_PATH = join(HERE, '../../test-results/a11y-found.jsonl')

/**
 * Traces are written per context and kept only for specs that failed.
 *
 * On by default in CI and off by default locally, which is the asymmetry that matters: on a laptop
 * a failure can be reproduced by re-running the one spec, and on a runner it cannot be reproduced
 * at all. Force either way with PW_TRACE=1 / PW_TRACE=0.
 */
const TRACE = process.env.PW_TRACE ? process.env.PW_TRACE !== '0' : !!process.env.CI

export const test = base.extend({
  launch: async ({ browser }, use, testInfo) => {
    const opened = []
    const traces = []
    const open = makeLaunch(browser, testInfo, (h) => opened.push(h))

    // Stops the trace but does *not* decide its fate: whether it is worth keeping depends on how
    // the spec ends, and a spec that closes its first context early has not ended yet.
    const stopTrace = async (h) => {
      if (!TRACE || h.traceStopped) return
      h.traceStopped = true
      try {
        const path = testInfo.outputPath(`trace-${opened.indexOf(h)}.zip`)
        await h.ctx.tracing.stop({ path })
        traces.push(path)
      } catch {
        // A trace is a convenience. Never fail a spec over one.
      }
    }

    const launch = async (opts) => {
      const h = await open(opts)
      if (TRACE) await h.ctx.tracing.start({ screenshots: true, snapshots: true })
      const closeCtx = h.close
      h.close = async () => {
        await stopTrace(h)
        await closeCtx()
      }
      return h
    }

    await use(launch)

    // A spec that failed mid-way has left the page in the state that failed. That state is the
    // single most useful artifact and it is about to be closed, so grab it first.
    const failed = testInfo.errors.length > 0
    for (const [i, h] of opened.entries()) {
      try {
        if (failed && !h.page.isClosed()) {
          await testInfo.attach(`final-state-${i}`, {
            body: await h.page.screenshot(),
            contentType: 'image/png',
          })
        }
        await stopTrace(h)
        await h.ctx.close()
      } catch {
        // Already closed by the spec, which is the normal case. Nothing here is worth failing over.
      }
    }

    for (const path of traces) {
      if (failed) await testInfo.attach(basename(path), { path, contentType: 'application/zip' })
      else rmSync(path, { force: true })
    }
  },

  /**
   * `await a11y(page, 'the state this is')` — scan wherever the spec has got to.
   *
   * A fixture rather than an import so that findings are collected per spec and reconciled once, in
   * teardown, against `tests/a11y-baseline.json`. Calls belong at states a spec already reaches: an
   * open dialog, an expanded row, a table after sorting. Those are where the interesting violations
   * live, and a separate a11y suite would only ever reach the states that are easy to reach.
   *
   * Never throws at the call site. One spec should report every state it visited rather than dying
   * at the first, for the same reason `check` is `expect.soft`.
   */
  a11y: async ({}, use, testInfo) => {
    const found = []
    let states = 0

    const a11y = async (page, state) => {
      states++
      const { gate, contrast } = await scan(page)
      for (const f of gate) found.push({ ...f, state, fingerprint: fingerprint(f.rule, f.selector) })
      if (contrast.length) {
        console.log(`  ℹ️  ${contrast.length} colour-contrast finding(s) at "${state}" — reported, not gated`)
      }
      return gate
    }

    await use(a11y)

    if (!states) return

    // Deduplicated by fingerprint: the same violation at three states is one thing to fix, and
    // three copies of it is a wall people learn to scroll past.
    const unique = [...new Map(found.map((f) => [f.fingerprint, f])).values()]

    // Recorded for the whole-run reconciliation, the same way check counts are. A spec cannot know
    // whether a baseline entry is stale — it only visits its own states — so "this entry no longer
    // fires anywhere" is a question only the full run can answer. `record-a11y.mjs` answers it.
    //
    // **Written even when nothing was found**, as a bare `{spec, states}` line. Otherwise a run
    // that scanned cleanly is byte-identical to one that never scanned, and the recorder cannot
    // tell "everything is fixed" from "the suite did not run" — which is exactly the state this
    // file reached the moment the last five findings were fixed.
    const lines = unique.length
      ? unique.map((f) => JSON.stringify({ spec: basename(testInfo.file), ...f }))
      : [JSON.stringify({ spec: basename(testInfo.file), states, fingerprint: null })]
    appendFileSync(A11Y_PATH, lines.join('\n') + '\n')

    if (!unique.length) return

    const problems = reconcile(unique, loadBaseline(), { seenStates: 1 })

    if (problems.length) {
      throw new Error(`${basename(testInfo.file)}: accessibility\n\n${problems.join('\n\n')}`)
    }
  },

  check: async ({}, use, testInfo) => {
    const spec = basename(testInfo.file)
    const quarantined = loadQuarantine().filter((q) => q.spec === spec)
    const hit = new Set()

    // A previous spec in this worker may have left issues behind if it died before its teardown.
    // They are not this spec's, so drop them rather than reporting them against the wrong file.
    takeContractIssues()

    let count = 0
    const check = (label, ok, detail = '') => {
      count++
      const q = quarantined.find((e) => e.label === label)
      if (q) {
        hit.add(q.label)
        console.log(`  ${ok ? '✅' : '🟡'} ${label} — quarantined until ${q.expires} (${q.issue})`)
        return ok
      }
      console.log(`  ${ok ? '✅' : '❌'} ${label}${detail ? ` — ${detail}` : ''}`)
      expect.soft(ok, detail ? `${label} — ${detail}` : label).toBe(true)
      return ok
    }

    await use(check)

    const fatal = []

    // --- contract findings, folded in so no spec has to know they exist -----------------------
    // Deduplicated: the same endpoint is often stubbed many times in one run, and thirty copies of
    // one message is a wall people learn to scroll past.
    const seen = new Set()
    const unique = takeContractIssues().filter((i) => !seen.has(i.message) && seen.add(i.message))
    const broken = unique.filter((i) => i.severity === 'broken')
    const fails = unique.filter((i) => i.severity === 'fail')
    // `broken` is excluded from the count on purpose: a crashed checker produces no warnings, and
    // letting that read as "warnings went down" is how the baseline would get ratcheted to zero.
    const warns = unique.filter((i) => i.severity === 'warn')

    for (const b of broken) {
      fatal.push(`contract: ${b.message}\n    the check did not run — this is not a fixture problem`)
    }
    for (const f of fails) fatal.push(`contract: ${f.message}`)
    if (warns.length) {
      console.log(`\n  ⚠️  ${warns.length} contract warning(s) — absent fields (normal for a partial stub) or fields core does not send:`)
      for (const w of warns.slice(0, 15)) console.log(`     ${w.message}`)
      if (warns.length > 15) console.log(`     … and ${warns.length - 15} more`)
    }

    // Ratchet: the count may fall, never rise. Without this the warnings are a list nobody reads
    // and new fixture drift joins it silently. Skipped when the checker itself broke: the count is
    // meaningless then, and "below budget" would be an invitation to lower it.
    const budget = broken.length ? null : contractBudget(spec)
    if (budget !== null && warns.length > budget) {
      fatal.push(
        `contract warnings above budget (${warns.length} > ${budget}) — new fixture drift. Fix it, ` +
          `or if it is deliberate raise the entry for this spec in tests/contract-baseline.json ` +
          `and say why in the commit`,
      )
    }
    if (budget !== null && warns.length < budget) {
      console.log(
        `  ↓ contract warnings are below budget (${warns.length} < ${budget}) — lower this spec's ` +
          `entry in tests/contract-baseline.json to ${warns.length} so the ground gained is kept`,
      )
    }

    // Both of the checks below only make sense for a spec that actually ran its body to the end.
    // A spec that failed early ran fewer checks *because* it failed, and a skipped one ran none at
    // all — reporting either as "coverage went missing" is a second error on top of the real one,
    // or a permanently red build with nothing to fix. The ratchets exist for the **silent** case,
    // which by definition leaves no error to see.
    const ranToCompletion = testInfo.errors.length === 0 && !skipped(testInfo)

    // --- a quarantine that no longer matches anything is not coverage, it just looks like it ---
    if (ranToCompletion) {
      for (const q of quarantined) {
        if (!hit.has(q.label)) {
          fatal.push(
            `quarantine.json mutes "${q.label}" in ${spec}, but no check with that label ran. ` +
              `Renamed or deleted? Remove the entry (${q.issue}).`,
          )
        }
      }
    }

    // --- the check-count ratchet ---------------------------------------------------------------
    appendFileSync(COUNTS_PATH, `${JSON.stringify({ spec, count })}\n`)
    const expected = expectedChecks()[spec]
    if (!ranToCompletion) {
      console.log(`  (ratchets skipped: this spec did not run to completion)`)
    } else if (expected === undefined) {
      console.log(
        `  ℹ️  ${spec} has no entry in tests/expected-checks.json (${count} checks). New specs are ` +
          `not ratcheted until \`npm run test:browser:record\` writes one.`,
      )
    } else if (count < expected) {
      fatal.push(
        `only ${count} checks ran, ${expected} expected (tests/expected-checks.json). Either an ` +
          `assertion was removed — put it back, or lower the entry in the same commit and say why — ` +
          `or the spec returned early without failing, which is the thing this number exists to catch.`,
      )
    }

    console.log(`\n${count} checks reported`)
    if (fatal.length) throw new Error(`${spec}:\n  - ${fatal.join('\n  - ')}`)
  },
})

/**
 * Whether this test skipped itself, from inside the teardown that has to know.
 *
 * `testInfo.status` is not yet final while fixtures are tearing down, but a `test.skip()` in the
 * body has already set it — which is the only case here that matters. `expectedStatus` covers a
 * spec skipped by annotation instead.
 */
function skipped(testInfo) {
  return testInfo.status === 'skipped' || testInfo.expectedStatus === 'skipped'
}

// Per worker, and after Playwright has cleaned the output directory — which is why this is here
// rather than in globalSetup, where the marker would be deleted again before the first spec ran.
neverIndex(dirname(COUNTS_PATH))
