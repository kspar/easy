/**
 * Step 0.5 / Verification item 2 of doc/web/ux-audit-plan.md — prove the instrument.
 *
 * "A detector that reports nothing may be unable to report anything." Before any S- or V-track
 * finding is trustworthy, the auditor has to demonstrate it can tell a correct render from a broken
 * one by looking at the PNG.
 *
 * The positive case is chosen, not invented: AboutPage.tsx:54,57,60 paint three sponsor-logo boxes
 * with `bgcolor: 'white'` — a literal, not a theme token. In light mode that is invisible against a
 * white paper. In dark mode it must appear as three white blocks on a #121212 page. If the driver
 * cannot show that, it cannot show anything.
 *
 *   node prove-instrument.mjs
 */
import { withBrowser, fakeApi, shoot, collectProblems, VIEWPORTS, BASE_URL } from './audit.mjs'

const STATS = { in_auto_assessing: 0, total_submissions: 12345, total_users: 678 }

/** Enough to let the page render its real chrome; unmatched calls fall through to {}. */
const handlers = [
  ['/account/checkin', () => ({})],
  ['/statistics', () => STATS],
  // `core` is NON-nullable: `val core: ComponentResp` in core/ems/service/versions.kt:106 and
  // `core: ComponentVersion` in web/src/api/versions.ts:66. A first attempt at this fixture used
  // `core: null` and crashed the route — which was the fixture being invalid, not a finding. Read
  // the contract before asserting a shape; five fixtures written from memory during EZ-1766 were
  // all wrong, and this was the sixth.
  [
    /\/versions(\?|$)/,
    () => ({
      core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' },
      executors: [],
    }),
  ],
]

await withBrowser(async ({ launch }) => {
  for (const theme of ['light', 'dark']) {
    const { page } = await launch({
      role: 'teacher',
      theme,
      colorScheme: theme,
      viewport: VIEWPORTS.laptop,
      language: 'en',
    })
    const problems = collectProblems(page)
    await fakeApi(page, handlers, { log: false })

    await page.goto(`${BASE_URL}/about`)
    await page.getByRole('heading', { level: 4 }).first().waitFor({ timeout: 15000 })
    await page.waitForTimeout(600) // let fonts and the sponsor row settle before the shot

    await shoot(page, `prove-about-${theme}`)

    // The hard number beside the picture: what those three boxes actually compute to, and what the
    // page behind them computes to. A finding wants both.
    const measured = await page.evaluate(() => {
      const boxes = [...document.querySelectorAll('div')].filter((el) => {
        const bg = getComputedStyle(el).backgroundColor
        return bg === 'rgb(255, 255, 255)' && el.querySelector('img')
      })
      return {
        whiteBoxesWithImages: boxes.length,
        bodyBg: getComputedStyle(document.body).backgroundColor,
        firstBoxBg: boxes[0] ? getComputedStyle(boxes[0]).backgroundColor : null,
      }
    })

    console.log(`[${theme}] ${JSON.stringify(measured)}`)
    if (problems.length) console.log(`[${theme}] problems: ${problems.join(' | ')}`)
  }
})
