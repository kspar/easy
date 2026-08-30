/**
 * Shared driver for the EZ-1791 UI/UX audit programme. See `doc/web/ux-audit-plan.md`.
 *
 * ## Why this is not in `tests/browser/`
 *
 * That directory is ratcheted three ways and every one of them would object. `expected-checks.json`
 * holds a check count per spec; `spec-inventory.mjs` derives the ratcheted set from the source, so a
 * new `.spec.mjs` there must report checks or fail; `suite-integrity.test.mjs` enforces the
 * bookkeeping. These drivers report *findings to a human*, not checks to a gate — they have no
 * assertions, and a run that "fails" by finding something is a run that worked.
 *
 * Nothing here is reachable from the suite: playwright's `testMatch` is `**​/*.spec.mjs` under
 * `testDir: './tests/browser'`, vitest collects `*.test.mjs`, and `eslint .` only matches
 * `**​/*.{ts,tsx}`. So this directory is invisible to all three, by construction rather than by
 * an ignore rule somebody has to remember.
 *
 * ## Why it reuses the harness instead of copying it
 *
 * `makeLaunch()` already knows how to seed `stubRole` / `activeRole` / `language` / `themeMode` into
 * localStorage *before the app boots*, which is the one genuinely fiddly part of driving this app.
 * `a11y.mjs` already wraps axe with the three checks axe does not have. Copying either would create
 * the asymmetric duplication the review programme keeps finding bugs in.
 *
 * ## Running
 *
 * A stub server has to be up first, on a port that is not the suite's 5199 — `reuseExistingServer`
 * is false there, and two runs on one port tear each other down in confusing ways:
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/c5-a11y-sweep.mjs
 */
import { chromium } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export { makeLaunch, fakeApi, json, waitUntil, BASE_URL } from '../support/harness.mjs'
export * as a11y from '../support/a11y.mjs'

import { makeLaunch } from '../support/harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))

/**
 * Evidence lands in `tests/screenshots/audit/` — already gitignored, alongside the suite's own
 * shots. Override with AUDIT_SHOTS to put it somewhere that outlives the checkout; a finding whose
 * screenshot has evaporated gets downgraded to UNCERTAIN by the next session, so for anything being
 * written into the log it is worth pointing this outside the repo.
 */
export const SHOTS = process.env.AUDIT_SHOTS ?? join(HERE, '../screenshots/audit')
mkdirSync(SHOTS, { recursive: true })

/**
 * Machine-readable sweep output, **tracked** — unlike SHOTS.
 *
 * A finding in the log cites the report that produced it, and `tests/screenshots/` is gitignored, so
 * a citation pointing there rots the moment the checkout is fresh. These files are small, they are
 * the evidence for a decision someone will revisit, and a diff between two runs is exactly how a
 * later session tells "we fixed it" from "we stopped looking".
 */
export const REPORTS = join(HERE, 'reports')
mkdirSync(REPORTS, { recursive: true })

/**
 * The viewports the plan fixes, so findings from different sessions are comparable.
 * `stress` is a stress case and deliberately not in the default set.
 */
export const VIEWPORTS = {
  phone: { width: 390, height: 844 },
  tablet: { width: 768, height: 1024 },
  laptop: { width: 1440, height: 900 },
  monitor: { width: 2560, height: 1440 },
  stress: { width: 320, height: 800 },
}

/**
 * The TSL builder's inner tabs, as accessible names.
 *
 * Every driver that touches them runs in Estonian, per the plan's "judge layout in Estonian" rule,
 * so these follow `et.json` rather than `en.json`. Two reasons they are anchored constants and not
 * inline regexes:
 *
 *  - EZ-1820 renamed the panes and the name `TSL` *survived the rename onto a different tab* —
 *    it used to be the raw JSON editor and is now the visual builder. A substring matcher finds
 *    the wrong pane and tests the wrong thing, silently, which is worse than failing.
 *  - the spec pane's Estonian name is being changed again (`Spec` → `Spetsifikatsioon`) while
 *    English keeps `Spec`, so the matcher accepts either. One place to correct when it settles.
 */
export const SPEC_TAB = /^(Spec|Spetsifikatsioon)$/i
export const BUILDER_TAB = /^TSL$/i

/**
 * The exercise page's auto-assessment tab, the grader-type select beside it, and the try-it-out tab.
 * EZ-1820's Estonian pass retired "Automaatkontroll", "Automaatkontrolli tüüp" and "Testimine".
 *
 * Anchored, and that matters more than it did: the old names were long and distinctive, the new ones
 * are prefixes of the vocabulary around them — "Testidega", "Testide tulemus", "Testidega hinnatud".
 * An unanchored `/Testid/` matches all of them. (The old matcher was loose in the same way —
 * `/Automaatkontroll/` also matched "Automaatkontrolli tüüp" — it just got away with it.)
 *
 * **Read these off `src/i18n/et.json`, never off a description of it.** Every value here changed at
 * least once between being agreed and being applied — `autoAssessType` went Testiraamistik →
 * Kontrollija and `tabTesting` went Testimine → Proovi järele → Proovi → Katseta, both during
 * review. The file is the truth; anything written down elsewhere is a snapshot of a moving target.
 */
export const AUTO_ASSESS_TAB = /^Testid$/
export const AUTO_ASSESS_TYPE = /^Kontrollija$/i
export const TESTING_TAB = /^Katseta$/i

/**
 * ...and here is the file being the truth, rather than a comment asking you to remember that it is.
 *
 * Each constant is checked against the `et.json` value it is supposed to match, at import, before
 * any driver does anything. A rename now fails on the first line with the old and new strings side
 * by side, instead of thirty seconds later as "tab not found" or — the case that actually cost us —
 * not at all, because the stale matcher happened to still match something else.
 *
 * Only the label constants are covered. That is the whole point: they are the ones that live in two
 * files at once.
 */
const I18N_ET = JSON.parse(readFileSync(join(HERE, '../../src/i18n/et.json'), 'utf8'))
const LABEL_SOURCES = [
  ['SPEC_TAB', SPEC_TAB, ['tsl', 'tabSpec']],
  ['BUILDER_TAB', BUILDER_TAB, ['tsl', 'tabTests']],
  ['AUTO_ASSESS_TAB', AUTO_ASSESS_TAB, ['library', 'tabAutoAssess']],
  ['AUTO_ASSESS_TYPE', AUTO_ASSESS_TYPE, ['library', 'autoAssessType']],
  ['TESTING_TAB', TESTING_TAB, ['library', 'tabTesting']],
]
const staleLabels = LABEL_SOURCES.flatMap(([name, re, path]) => {
  const value = path.reduce((o, k) => o?.[k], I18N_ET)
  if (typeof value !== 'string') return [`${name}: no such key in et.json — ${path.join('.')}`]
  return re.test(value) ? [] : [`${name} (${re}) no longer matches ${path.join('.')} = ${JSON.stringify(value)}`]
})
if (staleLabels.length > 0) {
  throw new Error(
    `audit.mjs label constants are out of date with src/i18n/et.json:\n  ${staleLabels.join('\n  ')}\n` +
    'Read the current values out of et.json and update the constants above.',
  )
}

/**
 * Run `fn` with a browser and a `launch` built the way `spec.mjs` builds it.
 *
 * `testInfo` is stubbed because `makeLaunch` only calls `attach`, and these drivers screenshot
 * themselves; `register` collects handles so a driver that forgets to close one still cleans up.
 */
export async function withBrowser(fn) {
  const browser = await chromium.launch({ channel: process.env.AUDIT_CHANNEL ?? 'chrome' })
  const opened = []
  const launch = makeLaunch(browser, { attach: async () => {} }, (h) => opened.push(h))
  try {
    return await fn({ launch, browser })
  } finally {
    for (const h of opened) {
      try {
        await h.close()
      } catch {
        /* already gone */
      }
    }
    await browser.close()
  }
}

/**
 * Screenshot and log the path, so the transcript records where the evidence is.
 *
 * `fullPage` defaults to true, unlike the harness's `shot`. An audit usually wants the whole surface
 * — but note it cost this programme a wrong reading once: a fullPage shot of a page that fits looks
 * identical to a viewport shot, and "half the screen is empty" turned out to be 90% full when
 * measured. Photograph for the finding, measure for the claim.
 */
export async function shoot(page, name, { fullPage = true } = {}) {
  const path = join(SHOTS, `${name}.png`)
  await page.screenshot({ path, fullPage })
  console.log(`  shot ${path}`)
  return path
}

/**
 * Collect console errors and page exceptions. `launch` prints them; a sweep needs them *collected*,
 * because an unhandled render error is a finding and one that scrolls past in a 22-route run is a
 * finding nobody saw. This is how X-005 (a misplaced React `key` in the shell) was noticed.
 */
export function collectProblems(page) {
  const problems = []
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`console: ${m.text()}`)
  })
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`))
  return problems
}

/**
 * Scan a reached state and return `{ gate, contrast }` findings.
 *
 * Wrapped rather than called directly because the shape is a trap: `a11y.scan()` returns
 * `{ gate, contrast }`, and the first version of the J1 driver read `.found` / `.contrastFindings`,
 * got `undefined`, printed zero, and would have recorded "this page is accessible" in the log. The
 * canary below exists for the same reason.
 */
export async function scanState(page, a11yMod) {
  const { gate, contrast } = await a11yMod.scan(page)
  return { gate, contrast }
}

/**
 * Prove the detector can fire, on this page, in this run.
 *
 * Injects a `<button>` with no accessible name and an `<img>` with no `alt`, rescans, and reports
 * whether axe caught them. The plan requires this before a clean result is believed —
 * `a11y-baseline.json` is empty, so "no findings" is ambiguous between a clean app and an inert
 * detector. Call it on the LAST state of a page: it mutates the DOM.
 */
export async function canary(page, a11yMod) {
  await page.evaluate(() => {
    const b = document.createElement('button')
    b.id = 'audit-canary'
    document.querySelector('main')?.appendChild(b)
    const img = document.createElement('img')
    img.src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
    document.querySelector('main')?.appendChild(img)
  })
  const { gate } = await a11yMod.scan(page)
  const caught = gate.filter((f) => f.rule === 'button-name' || f.rule === 'image-alt')
  return { fires: caught.length > 0, caught: caught.map((f) => f.rule) }
}
