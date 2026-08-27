/**
 * Unit C5 — accessibility coverage across every route, in both themes.
 *
 * Promoted ahead of the rest of the programme on J1's evidence: the a11y fixture is wired into 2 of
 * 40 browser specs, and the first unwired route this programme scanned returned two gate-level
 * violations and ten contrast findings. ~20 routes had never been scanned once.
 *
 * `scan()` returns `{ gate, contrast }`. `gate` is what would fail CI today if the route were wired;
 * `contrast` is run and deliberately never gated ("a design call rather than a deploy blocker"),
 * which is the call this programme exists to make.
 *
 * Findings are deduplicated by the harness's own fingerprint — rule id plus a selector normalised to
 * drop nth-child indices and emotion's hashed class names — so one decision appearing on fifteen
 * routes is one line with a route count, not fifteen findings.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/c5-a11y-sweep.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, a11y, canary, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  submission,
  teacherActivity,
  baseHandlers,
} from './fixtures.mjs'
import { superset, SURFACES } from './surfaces.mjs'



const seen = new Map() // fingerprint -> { rule, selector, summary, routes:Set, themes:Set, kind }
const perSurface = []
let canaryProof = null

function record(kind, findings, surfaceName, theme) {
  for (const f of findings) {
    const fp = a11y.fingerprint(f.rule, f.selector)
    if (!seen.has(fp)) {
      seen.set(fp, {
        rule: f.rule,
        selector: a11y.normaliseSelector(f.selector),
        summary: f.summary,
        routes: new Set(),
        themes: new Set(),
        kind,
      })
    }
    const e = seen.get(fp)
    e.routes.add(surfaceName)
    e.themes.add(theme)
  }
}

for (const s of SURFACES) {
  for (const theme of ['light', 'dark']) {
    await withBrowser(async ({ launch }) => {
      const { page } = await launch({
        role: s.role,
        theme,
        colorScheme: theme,
        language: 'et',
        viewport: VIEWPORTS.laptop,
      })
      await fakeApi(
        page,
        [
          ...baseHandlers(),
          ...(s.handlers ?? []),
          [/\/v2\//, () => superset()], // catch-all, last
        ],
        { log: false, contract: false },
      )

      try {
        await page.goto(`${BASE_URL}${s.path}`, { timeout: 20000 })
        // Something rendered inside the shell, or the shell itself for the outside-shell routes.
        await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 12000 })
        await page.waitForTimeout(1200)

        const { gate, contrast } = await a11y.scan(page)
        record('gate', gate, s.name, theme)
        record('contrast', contrast, s.name, theme)
        perSurface.push({ surface: s.name, theme, gate: gate.length, contrast: contrast.length, thin: !!s.thin })
        console.log(
          `${s.name.padEnd(24)} ${theme.padEnd(5)} gate ${String(gate.length).padStart(2)}  contrast ${String(contrast.length).padStart(3)}${s.thin ? '  (thin data)' : ''}`,
        )

        if (!canaryProof && s.name === 'exercise-student' && theme === 'light') {
          canaryProof = await canary(page, a11y)
        }
      } catch (e) {
        console.log(`${s.name.padEnd(24)} ${theme.padEnd(5)} FAILED TO RENDER: ${e.message.split('\n')[0]}`)
        perSurface.push({ surface: s.name, theme, error: e.message.split('\n')[0] })
      }
      await page.close()
    })
  }
}

console.log('\n================ deduplicated by fingerprint ================')
const entries = [...seen.values()].sort(
  (a, b) => (a.kind === b.kind ? b.routes.size - a.routes.size : a.kind === 'gate' ? -1 : 1),
)
for (const kind of ['gate', 'contrast']) {
  const list = entries.filter((e) => e.kind === kind)
  console.log(`\n--- ${kind.toUpperCase()} (${list.length} distinct) ---`)
  for (const e of list) {
    console.log(
      `[${String(e.routes.size).padStart(2)} routes | ${[...e.themes].join('+')}] ${e.rule}  ${e.selector}`,
    )
    if (e.summary) console.log(`      ${e.summary.slice(0, 150)}`)
    console.log(`      routes: ${[...e.routes].join(', ')}`)
  }
}
// Write the whole thing to disk as well. A sweep of 44 page loads is too expensive to re-run because
// the interesting half scrolled off a terminal, and the log's findings need to cite something stable.
const report = {
  sha: process.env.AUDIT_SHA ?? 'unknown',
  canary: canaryProof,
  perSurface,
  findings: entries.map((e) => ({
    kind: e.kind,
    rule: e.rule,
    selector: e.selector,
    summary: e.summary,
    themes: [...e.themes],
    routes: [...e.routes],
  })),
}
const reportPath = join(REPORTS, 'c5-a11y-sweep.json')
writeFileSync(reportPath, JSON.stringify(report, null, 2))
console.log(`\nreport written to ${reportPath}`)
console.log(`\nCANARY: ${JSON.stringify(canaryProof)}`)
const failed = perSurface.filter((p) => p.error)
if (failed.length) {
  console.log(`\n${failed.length} surface/theme combinations failed to render:`)
  for (const f of failed) console.log(`  ${f.surface} ${f.theme}: ${f.error}`)
}
