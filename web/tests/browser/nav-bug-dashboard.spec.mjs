// The bug-report dashboard link in the sidebar: admin-only, and only where the environment says
// which dashboard to open.
//
//   cd web && npx playwright test nav-bug-dashboard
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

const DASHBOARD = 'https://easy.example/youtrack/dashboard?id=540-8'

test('nav-bug-dashboard', async ({ launch, check }) => {
  const KC = { url: 'http://localhost:9999/auth/', realm: 'stub', clientId: 'cfg' }

  /**
   * Render the sidebar with a given role and config, and report what the dashboard entry looks like.
   *
   * `launch()` pins `activeRole` to 'teacher' in an init script that re-runs on every navigation, so
   * setting localStorage after `goto` is undone by the next one. A second init script is what works,
   * because they run in the order added — the same note `nav-idp-admin.spec.mjs` carries, and worth
   * repeating because without it the admin case reads as a missing feature.
   */
  async function sidebarFor({ activeRole, config, stubRole = 'teacher,admin' }) {
    const { page, close } = await launch({ shotPrefix: 'nav-dash-' })
    // Both, and `stubRole` is the one that is easy to miss: it is what the Keycloak stub puts in the
    // `easy_role` claim, i.e. which roles the account *has*. `activeRole` only chooses among those,
    // so asking for an active role the account does not hold gets silently replaced with one it
    // does. The student case below failed exactly that way first — it was an admin all along.
    await page.addInitScript(
      ([active, stub]) => {
        localStorage.setItem('activeRole', active)
        localStorage.setItem('stubRole', stub)
      },
      [activeRole, stubRole],
    )
    await page.route('**/config.json', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(config) }),
    )
    await fakeApi(
      page,
      [
        ['/account/checkin', () => ({})],
        ['/courses', () => ({ courses: [] })],
        ['/management/common/notifications', () => ({ messages: [] })],
      ],
      { log: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => (await page.locator('nav [class*=MuiListItemButton]').count()) > 0)

    // By href, not by position: this sits next to Articles today and the next admin-only item added
    // beside it should not silently redirect these assertions at something else.
    const link = page.locator(`nav a[href="${DASHBOARD}"]`)
    const found = await link.count()
    return {
      close,
      found,
      target: found > 0 ? await link.getAttribute('target') : null,
      rel: found > 0 ? await link.getAttribute('rel') : null,
      text: found > 0 ? (await link.innerText()).trim() : null,
      // The Administration section this now sits in, and what else is in it.
      heading: await page.locator('nav').getByText('Administration', { exact: true }).count(),
      navItems: (await page.locator('nav [class*=MuiListItemButton]').allInnerTexts()).map((s) =>
        s.trim().replace(/\s+/g, ' '),
      ),
    }
  }

  const base = { emsRoot: '/v2', keycloak: KC }

  // --- shown to an admin when configured ------------------------------------------------------------
  {
    const r = await sidebarFor({
      activeRole: 'admin',
      // `idpAdminUrl` too, so the section renders at its full four items. Without it the Keycloak
      // entry is legitimately absent — which is what the first run of the ordering check below
      // caught, reporting a missing item when the fixture simply had not configured one.
      config: { ...base, bugReportDashboardUrl: DASHBOARD, idpAdminUrl: 'https://idp.example/idp-admin/' },
    })
    check(`an admin gets the link (${r.found})`, r.found === 1)
    check(`it is labelled (${r.text})`, r.text === 'Reported bugs')
    // It leaves the app, so it opens in a new tab — and rel is not optional with target=_blank.
    check(`opens in a new tab (${r.target})`, r.target === '_blank')
    check(`and is not an open redirect vector (${r.rel})`, (r.rel ?? '').includes('noopener'))

    // The section it lives in, and its three companions. Asserted here because the whole point of
    // the section is that these four are together — an item quietly rendering somewhere else still
    // works and is still wrong.
    check(`there is an Administration heading (${r.heading})`, r.heading === 1)
    for (const item of ['System messages', 'Reported bugs', 'Keycloak admin', 'Operating info']) {
      check(`${item} is in the nav`, r.navItems.some((t) => t.includes(item)))
    }
    // Last, so it never pushes the course someone is working in further down the page.
    const lastFour = r.navItems.slice(-4).join(' | ')
    check(
      `and the section is at the bottom (${lastFour})`,
      ['System messages', 'Reported bugs', 'Keycloak admin', 'Operating info'].every((i) =>
        lastFour.includes(i),
      ),
    )
    await r.close()
  }

  // --- hidden without the config value --------------------------------------------------------------
  {
    // The key omitted entirely: an environment with no dashboard shows nothing rather than a link to
    // somebody else's tracker.
    const r = await sidebarFor({ activeRole: 'admin', config: base })
    check('no config value, no link', r.found === 0)
    await r.close()
  }

  {
    // Empty string behaves the same as absent — config.ts normalises it to undefined so the
    // condition stays a plain truthiness check.
    const r = await sidebarFor({
      activeRole: 'admin',
      config: { ...base, bugReportDashboardUrl: '' },
    })
    check('an empty value is the same as no value', r.found === 0)
    await r.close()
  }

  // --- hidden from everyone else --------------------------------------------------------------------
  {
    const r = await sidebarFor({
      activeRole: 'teacher',
      config: { ...base, bugReportDashboardUrl: DASHBOARD },
    })
    // Gated on the role being *acted in*, not on what the account could switch to — an admin working
    // as a teacher is doing teacher things, which is how every other admin-only item here behaves.
    check('a teacher does not get it even when configured', r.found === 0)
    // Nor the section around it — a teacher should not see an empty Administration heading.
    check(`nor the Administration heading (${r.heading})`, r.heading === 0)
    await r.close()
  }

  {
    const r = await sidebarFor({
      activeRole: 'student',
      stubRole: 'student',
      config: { ...base, bugReportDashboardUrl: DASHBOARD },
    })
    check('nor does a student', r.found === 0)
    await r.close()
  }
})
