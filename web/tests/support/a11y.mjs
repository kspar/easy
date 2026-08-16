import AxeBuilder from '@axe-core/playwright'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const BASELINE_PATH = join(HERE, '../a11y-baseline.json')

/**
 * Accessibility checks, run **inside existing specs at states they have already reached**.
 *
 * That placement is the whole design. The interesting violations are in states — a dialog open, an
 * accordion expanded, a table sorted — and only the spec that drove the app there knows how to get
 * there. A separate a11y suite would re-navigate to the states that are easy to reach, which are
 * the ones least likely to be broken.
 *
 * ### Fingerprints, not messages
 *
 * A finding is identified by rule id plus a *normalised* selector. The raw selector is unusable as
 * an identity: it carries `nth-child` indices that shift when a list gains a row, and emotion's
 * generated class names (`css-1a2b3c`) which change whenever a style does. Both would make the
 * baseline churn on changes that have nothing to do with accessibility, and a baseline that churns
 * is one people regenerate without reading.
 *
 * ### The baseline can only shrink
 *
 * - a **new** fingerprint fails the run — that is a regression
 * - a fingerprint that **no longer fires** also fails — the entry is stale and must be deleted, so
 *   the file stays an honest list of what is still wrong rather than a growing archive
 * - an entry with no `note` or no `issue` is **rejected at load**, so nothing gets silenced without
 *   somebody writing down why and where it is tracked
 *
 * ### What is deliberately not in the gate
 *
 * `color-contrast`. It is theme-wide and font-stack dependent, so one palette decision produces
 * hundreds of findings that are all the same decision; and it is a design question rather than a
 * deploy blocker. It is still *run* — see `contrastFindings` — so it can be reported without
 * failing a build.
 */
const GATE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

/** Rules excluded from the gate, with the reason. Still run and still reported. */
const NOT_IN_GATE = {
  'color-contrast':
    'Theme-wide and font-stack dependent: one palette decision becomes hundreds of findings, and it ' +
    'is a design call rather than a deploy blocker. Reported, never fatal.',
}

/**
 * Make a selector stable across unrelated change.
 *
 * `nth-child(4)` becomes `nth-child(n)` because a list gaining a row is not an accessibility
 * change, and emotion's hashed class names go entirely because they change with any style edit.
 */
export function normaliseSelector(selector) {
  return (
    String(selector)
      .replace(/:nth-child\(\d+\)/g, ':nth-child(n)')
      .replace(/:nth-of-type\(\d+\)/g, ':nth-of-type(n)')
      // MUI labels its emotion classes with the component they style, so one class is
      // `css-1wxaqej-MuiButtonBase-root-MuiIconButton-root`. Drop the hash and keep the label: the
      // hash changes with any style edit, the label is what tells a reader which control this is.
      .replace(/\.css-[a-z0-9]+-/gi, '.')
      .replace(/\.css-[a-z0-9]+/gi, '')
      // styled-components' own generated names, which carry no label worth keeping.
      .replace(/\.e[a-z0-9]{8,}/gi, '')
      .replace(/\s+/g, ' ')
      .trim()
  )
}

/** `rule@selector` — the identity a baseline entry is keyed by. */
export function fingerprint(ruleId, selector) {
  return `${ruleId}@${normaliseSelector(selector)}`
}

/**
 * Load and validate the baseline.
 *
 * Rejecting an entry without `note` or `issue` at load rather than at use is deliberate: a silenced
 * finding with no reason is indistinguishable from one nobody has looked at, and the moment to
 * demand the reason is when it is being added.
 */
export function loadBaseline(raw = readFileSync(BASELINE_PATH, 'utf8')) {
  const parsed = JSON.parse(raw)
  const entries = parsed.known ?? {}

  const bad = Object.entries(entries)
    .filter(([, v]) => !v || !String(v.note ?? '').trim() || !String(v.issue ?? '').trim())
    .map(([k]) => k)

  if (bad.length) {
    throw new Error(
      `a11y-baseline.json entries must carry both a "note" and an "issue":\n` +
        bad.map((k) => `  ${k}`).join('\n') +
        `\n\nAn entry without them is a finding silenced for no recorded reason, which is the same ` +
        `as not having looked at it.`,
    )
  }
  return entries
}

/**
 * Run axe against the current page state.
 *
 * Returns `{ gate, contrast }` — findings that count, and the ones that are reported only. The
 * caller records them; nothing here throws, so one spec reports every state it visited rather than
 * dying at the first.
 */
export async function scan(page, { include } = {}) {
  let builder = new AxeBuilder({ page }).withTags(GATE_TAGS)
  if (include) builder = builder.include(include)

  const { violations } = await builder.analyze()

  const flat = violations.flatMap((v) =>
    v.nodes.map((n) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      selector: Array.isArray(n.target) ? n.target.join(' ') : String(n.target),
      summary: (n.failureSummary || '').split('\n').slice(0, 2).join(' ').trim(),
    })),
  )

  return {
    gate: flat.filter((f) => !(f.rule in NOT_IN_GATE)),
    contrast: flat.filter((f) => f.rule in NOT_IN_GATE),
  }
}

/**
 * Compare a run's findings against the baseline and describe what is wrong with it.
 *
 * Pure, so it is unit-testable without a browser — `tests/unit/a11y.test.mjs` does exactly that,
 * which matters because this function decides whether the gate is honest.
 */
export function reconcile(found, baseline, { seenStates } = { seenStates: 1 }) {
  const problems = []
  const foundIds = new Set(found.map((f) => f.fingerprint))

  const introduced = found.filter((f) => !(f.fingerprint in baseline))
  if (introduced.length) {
    problems.push(
      `${introduced.length} new accessibility violation(s):\n` +
        introduced
          .map((f) => `  ${f.fingerprint}\n      ${f.impact ?? '?'} — ${f.help}\n      at ${f.state}`)
          .join('\n') +
        `\n\nFix it, or add it to tests/a11y-baseline.json with a note and an issue.`,
    )
  }

  // Only meaningful when every state that can produce a finding has been visited. A filtered run
  // (`npx playwright test grade-table`) visits a fraction of them, so this half is skipped there —
  // otherwise the common local command would demand the deletion of entries that are still true.
  if (seenStates === 'all') {
    const stale = Object.keys(baseline).filter((k) => !foundIds.has(k))
    if (stale.length) {
      problems.push(
        `${stale.length} baseline entr(ies) no longer fire:\n` +
          stale.map((k) => `  ${k}`).join('\n') +
          `\n\nDelete them. The baseline is a list of what is still wrong, and an entry that has ` +
          `been fixed makes it a worse one.`,
      )
    }
  }

  return problems
}

export const baselinePath = BASELINE_PATH
export const notInGate = NOT_IN_GATE

/** Rewrite the baseline from a run. `npm run test:a11y:record`; entries still need notes. */
export function writeBaseline(found) {
  const existing = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'))
  const known = {}
  for (const f of found) {
    known[f.fingerprint] = existing.known?.[f.fingerprint] ?? {
      note: `TODO: describe the impact — ${f.help}`,
      issue: 'TODO',
      impact: f.impact,
      firstSeenAt: f.state,
    }
  }
  writeFileSync(BASELINE_PATH, JSON.stringify({ ...existing, known }, null, 2) + '\n')
}
