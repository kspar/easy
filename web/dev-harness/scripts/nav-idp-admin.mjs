// The IdP admin link in the profile menu: shown only while acting as an admin, and only where the
// environment says where to send them.
//
//   cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
//   cd web/dev-harness && node scripts/nav-idp-admin.mjs
import { launch, checker, fakeApi, BASE_URL, waitUntil } from '../harness.mjs'

const check = checker()
const KC = { url: 'http://localhost:9999/auth/', realm: 'stub', clientId: 'cfg' }
const GATE = 'https://idp.example/idp-admin/'

/**
 * Open the profile menu with a given active role and config, and report what is in it.
 *
 * `launch()` pins `activeRole` to 'teacher' for every non-student role, in an init script that
 * re-runs on every navigation — so setting localStorage after `goto` is silently undone by the next
 * one. Adding a second init script is what actually works, because they run in the order added.
 * Worth knowing before writing any other test of admin-only UI: without this, the admin case looks
 * like a missing feature rather than a harness default.
 */
async function menuFor({ activeRole, config }) {
  const { browser, page } = await launch({ shotPrefix: 'nav-idp-' })
  await page.addInitScript((r) => localStorage.setItem('activeRole', r), activeRole)
  await page.route('**/config.json', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(config) }),
  )
  await fakeApi(
    page,
    [['/account/checkin', () => ({})], ['/courses', () => ({ courses: [] })]],
    { log: false },
  )
  await page.goto(`${BASE_URL}/courses`)
  await waitUntil(async () => (await page.locator('[class*=MuiAppBar] button').count()) > 0)
  await page.locator('[class*=MuiAppBar] button').last().click()
  await waitUntil(async () => (await page.locator('[role=menuitem]').count()) > 0)

  const items = (await page.locator('[role=menuitem]').allInnerTexts()).map((s) =>
    s.trim().replace(/\s+/g, ' '),
  )
  // Named by its text, not by being the first anchor in the menu. It was the only one when this
  // was written; adding the "System messages" item — an internal RouterLink, which also renders an
  // anchor — made `.first()` silently start asserting against a different link, and the failure
  // read as this feature being broken.
  const anchor = page.locator('a[role=menuitem]').filter({ hasText: 'Keycloak admin' })
  const has = await anchor.count()
  const result = {
    items,
    href: has ? await anchor.first().getAttribute('href') : null,
    target: has ? await anchor.first().getAttribute('target') : null,
    rel: has ? await anchor.first().getAttribute('rel') : null,
    role: await page.evaluate(() => localStorage.getItem('activeRole')),
  }
  await browser.close()
  return result
}

// --- shown, when both conditions hold ------------------------------------------------------------
const asAdmin = await menuFor({ activeRole: 'admin', config: { emsRoot: '/v2', keycloak: KC, idpAdminUrl: GATE } })
check(`admin: acting role really is admin (${asAdmin.role})`, asAdmin.role === 'admin')
check(
  `admin: the link is in the menu (${asAdmin.items.join(' | ')})`,
  asAdmin.items.some((t) => t.includes('Keycloak admin')),
)
check(`admin: points at the configured URL (${asAdmin.href})`, asAdmin.href === GATE)
// A real anchor with target=_blank, so ctrl/cmd-click behaves like any other link and the app is
// not left behind in the same tab.
check(`admin: opens in a new tab (target=${asAdmin.target})`, asAdmin.target === '_blank')
check(`admin: carries rel=noopener (${asAdmin.rel})`, (asAdmin.rel ?? '').includes('noopener'))

// --- hidden, when acting as a teacher ------------------------------------------------------------
// An admin who has switched to the teacher role is doing teacher things; the same reasoning as
// isTeacherOrAdmin in AppLayout.
const asTeacher = await menuFor({ activeRole: 'teacher', config: { emsRoot: '/v2', keycloak: KC, idpAdminUrl: GATE } })
check(
  `teacher: no link, even though the URL is configured (${asTeacher.items.join(' | ')})`,
  !asTeacher.items.some((t) => t.includes('Keycloak admin')),
)

// --- hidden, where the environment has nowhere to send them ---------------------------------------
// Production's IdP has no gate page installed, so its config.json omits the key and the menu must
// not offer a link to a 404. This is the case that makes the key optional rather than derived.
const noUrl = await menuFor({ activeRole: 'admin', config: { emsRoot: '/v2', keycloak: KC } })
check(
  `admin, no idpAdminUrl: no link (${noUrl.items.join(' | ')})`,
  !noUrl.items.some((t) => t.includes('Keycloak admin')),
)
check('admin, no idpAdminUrl: the rest of the menu is intact', noUrl.items.length >= 3)

process.exit(check.summary() ? 0 : 1)
