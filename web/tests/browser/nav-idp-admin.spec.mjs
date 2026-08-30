// The IdP admin link in the sidebar's Administration section: shown only while acting as an admin,
// and only where the environment says where to send them.
//
//   cd web && npx playwright test nav-idp-admin
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

test('nav-idp-admin', async ({ launch, check }) => {
  const KC = { url: 'http://localhost:9999/auth/', realm: 'stub', clientId: 'cfg' }
  const GATE = 'https://idp.example/idp-admin/'

  /**
   * Render the sidebar with a given active role and config, and report what is in it.
   *
   * `launch()` pins `activeRole` to 'teacher' for every non-student role, in an init script that
   * re-runs on every navigation — so setting localStorage after `goto` is silently undone by the next
   * one. Adding a second init script is what actually works, because they run in the order added.
   * Worth knowing before writing any other test of admin-only UI: without this, the admin case looks
   * like a missing feature rather than a harness default.
   */
  async function menuFor({ activeRole, config }) {
    const { page, close } = await launch({ shotPrefix: 'nav-idp-' })
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
    // The sidebar, not the account menu. This link lived in the account menu until the
    // Administration section was added; the menu now holds only things about *you*, so a spec
    // looking for it there would report a missing feature rather than a moved one.
    await waitUntil(async () => (await page.locator('nav [class*=MuiListItemButton]').count()) > 0)

    const items = (await page.locator('nav [class*=MuiListItemButton]').allInnerTexts()).map((s) =>
      s.trim().replace(/\s+/g, ' '),
    )
    // Named by its text, not by being the first anchor in the nav. It was the only one when this was
    // written; the section it now sits in has three siblings that are also anchors, so `.first()`
    // would silently start asserting against a different link and the failure would read as this
    // feature being broken.
    const anchor = page.locator('nav a').filter({ hasText: 'Lahendus ID admin' })
    const has = await anchor.count()
    const result = {
      items,
      href: has ? await anchor.first().getAttribute('href') : null,
      target: has ? await anchor.first().getAttribute('target') : null,
      rel: has ? await anchor.first().getAttribute('rel') : null,
      role: await page.evaluate(() => localStorage.getItem('activeRole')),
    }
    await close()
    return result
  }

  // --- shown, when both conditions hold ------------------------------------------------------------
  const asAdmin = await menuFor({ activeRole: 'admin', config: { emsRoot: '/v2', keycloak: KC, idpAdminUrl: GATE } })
  check(`admin: acting role really is admin (${asAdmin.role})`, asAdmin.role === 'admin')
  check(
    `admin: the link is in the sidebar (${asAdmin.items.join(' | ')})`,
    asAdmin.items.some((t) => t.includes('Lahendus ID admin')),
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
    !asTeacher.items.some((t) => t.includes('Lahendus ID admin')),
  )

  // --- hidden, where the environment has nowhere to send them ---------------------------------------
  // Production's IdP has no gate page installed, so its config.json omits the key and the sidebar
  // must not offer a link to a 404. This is the case that makes the key optional rather than derived.
  const noUrl = await menuFor({ activeRole: 'admin', config: { emsRoot: '/v2', keycloak: KC } })
  check(
    `admin, no idpAdminUrl: no link (${noUrl.items.join(' | ')})`,
    !noUrl.items.some((t) => t.includes('Lahendus ID admin')),
  )
  check('admin, no idpAdminUrl: the rest of the sidebar is intact', noUrl.items.length >= 3)
})
