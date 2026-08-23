/**
 * Unit S1, non-colour half: measure the radius vocabulary and whether shadows survive dark mode.
 *
 * Two claims that reading cannot settle.
 *
 * 1. In MUI's `sx`, a *numeric* `borderRadius` is multiplied by `theme.shape.borderRadius`, while the
 *    same key inside `components.*.styleOverrides` is raw CSS. If that is true then `borderRadius: 1`
 *    in an sx prop is 12px here, and the number 8 means 8px in theme.ts but would mean 96px in an sx
 *    prop — the same spelling with two meanings depending on which file it is in. Worth knowing before
 *    calling the radius vocabulary inconsistent, and worth knowing for the style guide either way.
 *
 * 2. `theme.ts` replaces the whole `shadows` array with black-alpha values
 *    (`rgba(0,0,0,0.08)` and friends), identical in both modes. A black shadow on a `#121212` surface
 *    is arithmetically invisible; the question is whether anything actually renders one, given
 *    `MuiCard` defaults to `variant: 'outlined'` and 83 places pass `variant="outlined"` explicitly.
 */
import { withBrowser, fakeApi, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { studentCourse, baseHandlers } from './fixtures.mjs'

for (const theme of ['light', 'dark']) {
  await withBrowser(async ({ launch }) => {
    const { page } = await launch({
      role: 'teacher',
      theme,
      colorScheme: theme,
      language: 'et',
      viewport: VIEWPORTS.laptop,
    })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [/\/teacher\/courses(\?|$)/, () => ({
          courses: [
            { ...studentCourse(), student_count: 12, last_submission_at: null, moodle_short_name: null },
            { ...studentCourse(), id: '120', title: 'Algoritmid ja andmestruktuurid', student_count: 40, last_submission_at: null, moodle_short_name: null },
          ],
        })],
      ],
      { log: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => (await page.locator('.MuiCard-root, .MuiPaper-root').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(800)

    const m = await page.evaluate(() => {
      // Prove or disprove the sx multiplication by asking the page itself: inject two divs, one with
      // a raw 8px radius and one styled the way an sx prop would be, and compare. We cannot call
      // MUI's transform from here, so instead read real elements whose radius we know the source of.
      const radii = new Map()
      for (const el of document.querySelectorAll('main *, .MuiDrawer-root *')) {
        const cs = getComputedStyle(el)
        const r = cs.borderTopLeftRadius
        if (!r || r === '0px') continue
        const key = `${r} | ${el.className?.toString().split(' ').filter((c) => c.startsWith('Mui')).slice(0, 2).join('.') || el.tagName.toLowerCase()}`
        radii.set(key, (radii.get(key) ?? 0) + 1)
      }

      let shadowed = 0
      const shadowSamples = []
      for (const el of document.querySelectorAll('*')) {
        const bs = getComputedStyle(el).boxShadow
        if (bs && bs !== 'none') {
          shadowed++
          if (shadowSamples.length < 4) shadowSamples.push(bs.slice(0, 80))
        }
      }
      return {
        radii: [...radii.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12),
        shadowedElements: shadowed,
        shadowSamples,
        bodyBg: getComputedStyle(document.body).backgroundColor,
      }
    })

    console.log(`\n[${theme}] body ${m.bodyBg}`)
    console.log(`[${theme}] distinct radius values in use on this page:`)
    for (const [k, n] of m.radii) console.log(`   ${String(n).padStart(3)}x  ${k}`)
    console.log(`[${theme}] elements with a box-shadow: ${m.shadowedElements}`)
    for (const s of m.shadowSamples) console.log(`   ${s}`)
    await page.close()
  })
}
