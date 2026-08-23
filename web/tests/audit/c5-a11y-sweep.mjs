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
import { withBrowser, fakeApi, a11y, canary, SHOTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
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

/**
 * A response that satisfies almost any `.then(r => r.something)` map.
 *
 * The alternative is stubbing every endpoint of twenty routes, which is a day's work and mostly
 * wasted: this unit is scanning *chrome and controls*, not asserting on data. Returning a superset
 * object means a hook that picks one list key gets an empty array rather than `undefined` — and
 * `undefined` is what makes react-query throw and the route render its error boundary instead of the
 * surface under audit.
 *
 * The honest cost, recorded in the log: routes scanned this way are scanned in their *empty* state.
 * Empty states are real surfaces and C2 wants them anyway, but a table with no rows cannot show a
 * contrast problem in a table cell. Any route whose findings matter gets a realistic pass later.
 */
const superset = () => ({
  courses: [],
  exercises: [],
  submissions: [],
  teacher_activities: [],
  comments: [],
  inline_comments: [],
  messages: [],
  articles: [],
  students: [],
  teachers: [],
  groups: [],
  invites: [],
  dirs: [],
  items: [],
  executors: [],
  images: [],
  versions: [],
  reports: [],
  count: 0,
  total: 0,
})

/** The surfaces. `thin` marks the ones scanned against `superset()` rather than real fixtures. */
const SURFACES = [
  {
    name: 'landing',
    path: '/landing',
    role: 'student',
    outsideShell: true,
    thin: true,
  },
  { name: 'not-found', path: '/no-such-page', role: 'student', thin: true },
  { name: 'courses-student', path: '/courses', role: 'student',
    handlers: [[/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })]] },
  { name: 'courses-teacher', path: '/courses', role: 'teacher',
    handlers: [[/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 12, last_submission_at: null, moodle_short_name: null }] })]] },
  { name: 'courses-admin', path: '/courses', role: 'admin',
    handlers: [[/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 12, last_submission_at: null, moodle_short_name: null }] })]] },
  {
    name: 'exercise-list-student',
    path: `/courses/${COURSE_ID}/exercises`,
    role: 'student',
    handlers: [
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise()] })],
    ],
  },
  {
    name: 'exercise-student',
    path: `/courses/${COURSE_ID}/exercises/${CE_ID}`,
    role: 'student',
    handlers: [
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise()] })],
      [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions: [submission()] })],
      [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [teacherActivity()] })],
      [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
    ],
  },
  { name: 'exercise-list-teacher', path: `/courses/${COURSE_ID}/exercises`, role: 'teacher', thin: true },
  { name: 'exercise-teacher', path: `/courses/${COURSE_ID}/exercises/${CE_ID}`, role: 'teacher', thin: true },
  { name: 'participants', path: `/courses/${COURSE_ID}/participants`, role: 'teacher', thin: true },
  { name: 'grades', path: `/courses/${COURSE_ID}/grades`, role: 'teacher', thin: true },
  { name: 'similarity', path: `/courses/${COURSE_ID}/similarity`, role: 'teacher', thin: true },
  { name: 'library-dir', path: '/library/dir/root', role: 'teacher', thin: true },
  { name: 'library-exercise', path: '/library/exercise/4242/x', role: 'teacher', thin: true },
  { name: 'join-by-link', path: '/link/ABC123', role: 'student', thin: true },
  { name: 'join-by-moodle-link', path: '/moodle/link/ABC123', role: 'student', thin: true },
  { name: 'articles-admin', path: '/articles', role: 'admin', thin: true },
  { name: 'article-public', path: '/a/juhend', role: 'student', thin: true },
  {
    name: 'about-teacher',
    path: '/about',
    role: 'teacher',
    handlers: [[/\/versions(\?|$)/, () => ({ core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' }, executors: [] })]],
  },
  {
    name: 'about-admin',
    path: '/about',
    role: 'admin',
    handlers: [[/\/versions(\?|$)/, () => ({ core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' }, executors: [] })]],
  },
  { name: 'account', path: '/account', role: 'student', thin: true },
  { name: 'admin-messages', path: '/admin/messages', role: 'admin', thin: true },
  { name: 'embed', path: `/embed/exercises/4242`, role: 'student', outsideShell: true, thin: true },
]

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
const reportPath = join(SHOTS, 'c5-a11y-sweep.json')
writeFileSync(reportPath, JSON.stringify(report, null, 2))
console.log(`\nreport written to ${reportPath}`)
console.log(`\nCANARY: ${JSON.stringify(canaryProof)}`)
const failed = perSurface.filter((p) => p.error)
if (failed.length) {
  console.log(`\n${failed.length} surface/theme combinations failed to render:`)
  for (const f of failed) console.log(`  ${f.surface} ${f.theme}: ${f.error}`)
}
