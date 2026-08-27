/**
 * Units S3/S4/S5 — every surface at phone, laptop and monitor widths, measured.
 *
 * Same surfaces and fixtures as the C5 sweep (surfaces.mjs), light theme, Estonian. Per load:
 *
 *  - horizontal document overflow, and the widest element sticking past the viewport (S3's question:
 *    the tables without a TableContainer are predicted to overflow at 390);
 *  - elements clipping their own content (scrollWidth > clientWidth);
 *  - at monitor width: how much of the viewport the shell actually uses (S5's question — the
 *    maxWidth="lg" cap is known, but per-surface content width tells us who *wants* the room).
 *
 *   HARNESS_PORT=5299 node tests/audit/s345-viewport-sweep.mjs
 */
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { withBrowser, fakeApi, shoot, REPORTS, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { baseHandlers } from './fixtures.mjs'
import { superset, SURFACES } from './surfaces.mjs'

const results = []

for (const [vpName, viewport] of [
  ['phone', VIEWPORTS.phone],
  ['laptop', VIEWPORTS.laptop],
  ['monitor', VIEWPORTS.monitor],
]) {
  for (const s of SURFACES) {
    await withBrowser(async ({ launch }) => {
      const { page } = await launch({ role: s.role, language: 'et', viewport })
      await fakeApi(
        page,
        [...baseHandlers(), ...(s.handlers ?? []), [/\/v2\//, () => superset()]],
        { log: false, contract: false },
      )
      try {
        await page.goto(`${BASE_URL}${s.path}`, { timeout: 20000 })
        await waitUntil(async () => (await page.locator('body *').count()) > 5, { timeout: 12000 })
        await page.waitForTimeout(1000)

        const m = await page.evaluate(() => {
          const de = document.documentElement
          const overflow = de.scrollWidth - de.clientWidth
          let worst = null
          for (const el of document.querySelectorAll('body *')) {
            const r = el.getBoundingClientRect()
            if (r.width === 0) continue
            const over = Math.round(r.right - de.clientWidth)
            if (over > 2 && (!worst || over > worst.over)) {
              worst = {
                over,
                tag: el.tagName.toLowerCase(),
                cls: (el.className?.toString() ?? '').split(' ').filter((c) => c.startsWith('Mui') || c.startsWith('cm-')).slice(0, 2).join('.'),
                text: (el.textContent ?? '').trim().replace(/\s+/g, ' ').slice(0, 50),
              }
            }
          }
          const main = document.querySelector('main')
          const mainW = main ? Math.round(main.getBoundingClientRect().width) : null
          const clipped = [...document.querySelectorAll('main *')].filter((el) => el.scrollWidth > el.clientWidth + 2).length
          return { overflow, worst, mainW, viewportW: de.clientWidth, clipped }
        })

        results.push({ surface: s.name, viewport: vpName, ...m, thin: !!s.thin })
        const flag = m.overflow > 2 ? ' ◀ OVERFLOW' : ''
        console.log(
          `${vpName.padEnd(8)} ${s.name.padEnd(24)} overflow ${String(m.overflow).padStart(4)}px  main ${String(m.mainW ?? '-').padStart(5)}/${m.viewportW}  clipped ${String(m.clipped).padStart(3)}${flag}`,
        )
        if (m.overflow > 2) await shoot(page, `s345-${vpName}-${s.name}`)
      } catch (e) {
        console.log(`${vpName.padEnd(8)} ${s.name.padEnd(24)} FAILED: ${e.message.split('\n')[0].slice(0, 80)}`)
        results.push({ surface: s.name, viewport: vpName, error: e.message.split('\n')[0] })
      }
      await page.close()
    })
  }
}

console.log('\n=== overflowing surfaces ===')
for (const r of results.filter((r) => (r.overflow ?? 0) > 2)) {
  console.log(`${r.viewport} ${r.surface}: +${r.overflow}px — worst: ${JSON.stringify(r.worst)}`)
}
const path = join(REPORTS, 's345-viewport-sweep.json')
writeFileSync(path, JSON.stringify({ sha: process.env.AUDIT_SHA ?? 'unknown', results }, null, 2))
console.log(`\nreport written to ${path}`)
