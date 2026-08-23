/**
 * C5, Stage 2: refute or confirm `main-landmark-missing` on the four routes that sit *inside*
 * AppLayout.
 *
 * The sweep reported no `<main>` on landing, embed, participants, library-dir, library-exercise and
 * about-admin. Landing and embed render outside AppLayout, so those two are expected. The other four
 * are inside it, and AppLayout wraps every routed page in `<Container component="main">` — so either
 * the sweep found something real, or those pages did not render at all and something else replaced
 * the layout. A finding built on a crashed page is not a finding.
 *
 * Distinguishing evidence: count `<main>`, count the app's own CrashScreen, look for React Router's
 * default error boundary text, and photograph what is actually on screen.
 */
import { withBrowser, fakeApi, shoot, collectProblems, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE_ID, baseHandlers } from './fixtures.mjs'

const superset = () => ({
  courses: [], exercises: [], submissions: [], teacher_activities: [], comments: [],
  inline_comments: [], messages: [], articles: [], students: [], teachers: [], groups: [],
  invites: [], dirs: [], items: [], executors: [], images: [], versions: [], reports: [],
  count: 0, total: 0,
})

const CASES = [
  { name: 'participants', path: `/courses/${COURSE_ID}/participants`, role: 'teacher' },
  { name: 'library-dir', path: '/library/dir/root', role: 'teacher' },
  { name: 'library-exercise', path: '/library/exercise/4242/x', role: 'teacher' },
  { name: 'about-admin', path: '/about', role: 'admin' },
  // Controls: one route the sweep said DOES have main, and one that legitimately has none.
  { name: 'courses-teacher (control, has main)', path: '/courses', role: 'teacher' },
  { name: 'landing (control, outside shell)', path: '/landing', role: 'teacher' },
]

for (const c of CASES) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({ role: c.role, language: 'et', viewport: VIEWPORTS.laptop })
    const problems = collectProblems(page)
    await fakeApi(page, [...baseHandlers(), [/\/v2\//, () => superset()]], { log: false, contract: false })

    await page.goto(`${BASE_URL}${c.path}`, { timeout: 20000 })
    await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 12000 })
    await page.waitForTimeout(1500)

    const state = await page.evaluate(() => {
      const txt = document.body.innerText
      return {
        mainCount: document.querySelectorAll('main, [role=main]').length,
        // The app's own boundary renders CrashScreen, which offers a bug report.
        looksLikeCrashScreen: /Midagi läks valesti|Something went wrong|Teata veast|Report a bug/i.test(txt),
        // React Router's default boundary renders "Unexpected Application Error!" plus a stack.
        looksLikeRouterBoundary: /Unexpected Application Error/i.test(txt),
        hasSidebar: !!document.querySelector('nav, .MuiDrawer-root'),
        firstText: txt.trim().slice(0, 140).replace(/\s+/g, ' '),
      }
    })

    console.log(`\n[${c.name}]`)
    console.log(`  main elements: ${state.mainCount}`)
    console.log(`  sidebar present: ${state.hasSidebar}`)
    console.log(`  app CrashScreen: ${state.looksLikeCrashScreen} · router default boundary: ${state.looksLikeRouterBoundary}`)
    console.log(`  text: ${JSON.stringify(state.firstText)}`)
    const errs = problems.filter((p) => !p.includes('unique "key"'))
    if (errs.length) console.log(`  errors: ${errs.slice(0, 2).join(' | ').slice(0, 300)}`)
    await shoot(page, `c5-mainlandmark-${c.name.split(' ')[0]}`)
    await page.close()
  })
}
