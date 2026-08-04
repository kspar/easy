/**
 * `/tos` redirects to the terms of service document (EZ-1692).
 *
 * Worth a script because the footer on every page links here, the route did not exist, and so that
 * link went to Not Found — a broken link nobody would notice from a screenshot. And because Keycloak
 * points at this URL too, which makes it an external contract rather than an internal convenience.
 *
 * The outbound navigation is intercepted rather than followed: the point is *where* it goes and
 * whether Back is survivable, not what Google serves.
 */
import { launch, fakeApi, checker, waitUntil, BASE_URL } from '../harness.mjs'

const DOC = 'https://docs.google.com/document/d/1dk1Pp3hXJEX7HllQFdMFo5AXhgzy4zhZv3Qt6-xI_CI/edit?usp=sharing'

const { browser, page, shot } = await launch({ shotPrefix: 'tos-' })
const check = checker()

let target = null
await page.route('https://docs.google.com/**', (route) => {
  target = route.request().url()
  // 204, not abort. Both keep the test out of Google, but aborting a *top-level* navigation leaves
  // the tab on an opaque error document — every assertion after it then measures that instead of the
  // app, which showed up as "Access is denied" reading localStorage. A 204 makes the browser stay
  // exactly where it is, so the page under test survives and can still be inspected.
  return route.fulfill({ status: 204, body: '' })
})

await fakeApi(page, [
  ['/account/checkin', () => ({})],
  ['/teacher/courses', () => ({ courses: [] })],
], { log: false })

// --- the footer link that has been going nowhere ---------------------------------------------------
await page.goto(`${BASE_URL}/courses`)
const footerLink = page.getByRole('link', { name: 'Terms' })
check(
  'the footer links to /tos',
  await waitUntil(async () => (await footerLink.getAttribute('href')) === '/tos'),
)

// --- and where /tos goes ---------------------------------------------------------------------------
await page.goto(`${BASE_URL}/tos`)

check('visiting /tos redirects to the terms document', await waitUntil(() => target === DOC))
check(
  'it is not the Not Found page, which is what it was before this existed',
  (await page.getByText(/not found/i).count()) === 0,
)

// Not asserted here: that the redirect uses `replace` rather than `href`. It matters — with a
// history entry, Back returns to /tos and bounces straight out again — but it cannot be measured
// while the outbound navigation is being stubbed, since nothing ever commits. Testing it would mean
// letting the browser actually leave for Google.

// Visible only when the redirect does not carry the visitor away — as here, and as for anyone whose
// browser or extension blocks it.
check(
  'a blocked redirect still says where it was going, with a link',
  await waitUntil(async () =>
    (await page.getByText(/Opening the terms of service/i).count()) > 0 &&
    (await page.getByRole('link', { name: /Open them directly/i }).getAttribute('href')) === DOC),
)
await shot('01-blocked-fallback')

await browser.close()
process.exit(check.summary() ? 0 : 1)
