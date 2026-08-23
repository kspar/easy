/**
 * Unit T3 — where the UI's model and the compiler's accepted input diverge.
 *
 * `library-exercise-tsl-live.spec.mjs` already proves the forward direction: what the visual editor
 * produces decodes on the Kotlin side. This asks the reverse, which nothing asks: **what can the
 * compiler do that a teacher cannot reach from any form?** A capability that exists, works, and is
 * unreachable is a different finding from one that does not exist — and the difference is only
 * settleable by compiling.
 *
 * Each case sends a minimal spec through the real compiler and reports two things: whether it compiled,
 * and whether the feature left a trace in the generated Python. A field that compiles but leaves no
 * trace is accepted-and-ignored, which is worth knowing before anyone builds a form for it.
 *
 * Needs a core on :8080 with auth-enabled false. Read-only: compiling writes nothing.
 *
 *   node tests/audit/t3-model-vs-compiler.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { REPORTS } from './audit.mjs'

const CORE = process.env.AUDIT_CORE ?? 'http://localhost:8080'
const HEADERS = {
  'Content-Type': 'application/json',
  oidc_claim_preferred_username: 'kspar',
  oidc_claim_email: 'kspar@ut.ee',
  oidc_claim_given_name: 'Test',
  oidc_claim_family_name: 'Teacher',
  oidc_claim_easy_role: 'teacher',
}

async function compile(spec) {
  const res = await fetch(`${CORE}/v2/tsl/compile`, {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify({ tsl_spec: JSON.stringify(spec), format: 'JSON' }),
  })
  if (!res.ok) return { httpError: res.status }
  return res.json()
}

/** A GenericCheck as the UI's `emptyGenericCheck()` builds it, plus whatever the case is testing. */
const check = (over = {}) => ({
  id: 900,
  checkType: 'ALL_OF_THESE',
  expectedValue: ['5'],
  elementsOrdered: false,
  dataCategory: 'CONTAINS_STRINGS',
  beforeMessage: '',
  passedMessage: 'Hea',
  failedMessage: 'Halb',
  ...over,
})

const test = (over = {}) => ({
  type: 'program_execution_test',
  id: 111,
  name: null,
  standardInputData: [],
  inputFiles: [],
  genericChecks: [check()],
  outputFileChecks: [],
  exceptionCheck: null,
  ...over,
})

const spec = (tests) => ({
  language: 'python3',
  validateFiles: true,
  requiredFiles: ['lahendus.py'],
  tslVersion: '1.0',
  tests,
})

/**
 * Each case: what the UI can produce today, and what we are asking the compiler about.
 * `trace` is a substring whose presence in the generated Python proves the value was carried through
 * rather than silently dropped.
 */
const CASES = [
  {
    name: 'baseline — exactly what the UI produces',
    reachableFromUi: true,
    spec: spec([test()]),
    trace: 'standard_output_checks',
  },
  {
    name: 'outputCategory: LAST_OUTPUT (13 enum values, 0 reachable from any form)',
    reachableFromUi: false,
    spec: spec([test({ genericChecks: [check({ outputCategory: 'LAST_OUTPUT' })] })]),
    trace: 'LAST_OUTPUT',
  },
  {
    name: 'outputCategory: OUTPUT_NUMBER_3',
    reachableFromUi: false,
    spec: spec([test({ genericChecks: [check({ outputCategory: 'OUTPUT_NUMBER_3' })] })]),
    trace: 'OUTPUT_NUMBER_3',
  },
  {
    name: 'ignoreCase: true (no form anywhere writes it)',
    reachableFromUi: false,
    spec: spec([test({ genericChecks: [check({ ignoreCase: true })] })]),
    // NOTE the spelling. Check-level flags are emitted as Python *dict* entries —
    // `'ignore_case':True` — while test-level ones are keyword arguments — `passed_next=222`. The
    // first version of this driver traced `ignore_case=True` for both, found nothing, and reported
    // the feature as accepted-and-ignored. It is not; it works. The baseline case gave false
    // confidence because it traced a function name that is always present rather than a value.
    trace: "'ignore_case':True",
  },
  {
    name: 'dataCategory: EQUALS (the dropdown offers 3 of 4)',
    reachableFromUi: false,
    spec: spec([test({ genericChecks: [check({ dataCategory: 'EQUALS' })] })]),
    trace: 'EQUALS',
  },
  {
    name: 'nothingElse on an execution check (exposed only on static tests)',
    reachableFromUi: false,
    spec: spec([test({ genericChecks: [check({ nothingElse: true })] })]),
    trace: "'nothing_else':True",
  },
  {
    name: 'passedNext / failedNext — a branching-flow feature with no UI at all',
    reachableFromUi: false,
    spec: spec([test({ passedNext: 222, failedNext: 333 }), test({ id: 222 }), test({ id: 333 })]),
    trace: 'passed_next=222',
  },
  {
    name: 'duplicate test ids — validateParseTree() is not called from compileTSL',
    reachableFromUi: 'not via the visual editor; 174 of 721 production specs have them',
    spec: spec([test({ id: 111 }), test({ id: 111 })]),
    trace: 'id=111',
  },
  {
    name: 'beforeMessage with real text (the UI always writes empty string)',
    reachableFromUi: false,
    spec: spec([test({ genericChecks: [check({ beforeMessage: 'Kontrollin väljundit' })] })]),
    trace: 'Kontrollin',
  },
]

const results = []
for (const c of CASES) {
  const r = await compile(c.spec)
  const py = r.scripts?.[0]?.value ?? ''
  const compiled = !!r.scripts && !r.feedback
  const traced = c.trace ? py.includes(c.trace) : null
  results.push({
    name: c.name,
    reachableFromUi: c.reachableFromUi,
    compiled,
    traceFound: traced,
    feedback: r.feedback ? r.feedback.split('\n')[0].slice(0, 140) : null,
  })
  const verdict = !compiled
    ? `REJECTED — ${r.feedback?.split('\n')[0]?.slice(0, 90)}`
    : traced === false
      ? 'compiled, but the value left NO trace in the generated Python (accepted and ignored)'
      : 'compiled, and the value reached the generated script'
  console.log(`\n${c.name}`)
  console.log(`  reachable from the UI: ${c.reachableFromUi}`)
  console.log(`  → ${verdict}`)
}

const reportPath = join(REPORTS, 't3-model-vs-compiler.json')
writeFileSync(reportPath, JSON.stringify({ core: CORE, results }, null, 2))
console.log(`\nreport written to ${reportPath}`)
