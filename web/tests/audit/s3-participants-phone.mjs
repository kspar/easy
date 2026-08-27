/**
 * S3, targeted: ParticipantsPage at 390px with REAL fixtures.
 *
 * The viewport sweep's `superset()` crashes this page (R-005), so its row in the sweep measured the
 * router error page, not the roster. This drives it with the committed participants fixtures — the
 * page uses a bare `<Table>` with no `TableContainer`, which is the S3 lead about overflow.
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import { COURSE, participants, baseStubs } from '../support/participants-groups-fixtures.mjs'

const P = { ...participants, moodle_linked: false, students_moodle_pending: [] }

await withBrowser(async ({ launch }) => {
  const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.phone })
  await fakeApi(
    page,
    [
      ...baseStubs(),
      [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => P],
      [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
    ],
    { log: false, contract: false },
  )
  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await waitUntil(async () => (await page.getByText('Maasikas').count()) > 0, { timeout: 15000 })
  await page.waitForTimeout(1000)

  const m = await page.evaluate(() => {
    const de = document.documentElement
    const table = document.querySelector('main table')
    const r = table?.getBoundingClientRect()
    return {
      docOverflow: de.scrollWidth - de.clientWidth,
      tableWidth: r ? Math.round(r.width) : null,
      tableRight: r ? Math.round(r.right) : null,
      viewport: de.clientWidth,
      tableScrollable: table ? !!table.closest('[style*="overflow"], .MuiTableContainer-root') : null,
      bodyScrollX: getComputedStyle(document.body).overflowX,
    }
  })
  console.log(JSON.stringify(m, null, 1))
  await shoot(page, 's3-participants-phone')
  await page.close()
})
