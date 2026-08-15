/**
 * Account settings (EZ-1701) — the page the profile menu item pointed at nothing for.
 *
 * The menu item is half the bug, so it is half the test: a page nobody can reach is not fixed.
 *
 * The export is checked by intercepting the request rather than downloading a zip. What matters is
 * that it goes to the right endpoint *with an Authorization header* — it cannot go through the usual
 * client, which parses every response as JSON, so it is hand-rolled and hand-rolled auth is exactly
 * the kind of thing that silently ends up missing.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

test('account-settings', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'account-' })

  let exportRequest = null
  const interceptExport = () => page.route('**/account/export', async (route) => {
    exportRequest = {
      url: route.request().url(),
      auth: route.request().headers()['authorization'] ?? null,
    }
    return route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/zip' },
      body: 'PK not really a zip',
    })
  })

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/teacher/courses', () => ({ courses: [] })],
    ['/student/courses', () => ({ courses: [] })],
  ], { log: false })

  // Installed *after* fakeApi, and that ordering is the whole trick: Playwright tries the most recently
  // added route first, and fakeApi ends in a catch-all that answers everything with {}. Installed before
  // it, the handler never ran — the download then appeared to succeed, the recorded request stayed
  // null, and the failure read as "the button does nothing".
  await interceptExport()

  // --- reachable from the menu, which is the actual complaint -------------------------------------------
  await page.goto(`${BASE_URL}/courses`)
  // No .catch() here: a swallowed failure to open the menu is how the first run of this reported a
  // missing menu item when the real problem was that the button had no accessible name to click by.
  await page.getByRole('button', { name: 'Account menu' }).click()
  const menuItem = page.getByRole('menuitem', { name: 'Account settings' })
  check('the profile menu offers account settings', await waitUntil(() => menuItem.isVisible()))
  await menuItem.click()
  check(
    'and it now goes somewhere',
    await waitUntil(() => page.url().endsWith('/account')),
  )
  check(
    'which is not the Not Found page',
    (await page.getByText(/not found/i).count()) === 0,
  )

  // --- what the page shows ------------------------------------------------------------------------------
  check(
    'the identity from the token is shown',
    await waitUntil(async () =>
      (await page.getByText('Test User').count()) > 0 || (await page.getByText(/@/).count()) > 0),
  )
  check(
    'roles are listed',
    (await page.getByText('Teacher', { exact: true }).count()) > 0,
  )
  check(
    'and it says where name and email are kept, without claiming they came from the university —' +
      ' plenty of accounts are created through the register link instead',
    (await page.getByText(/kept in your Lahendus account/i).count()) > 0,
  )
  check(
    'the profile section offers a way to edit them, at the identity provider',
    (await page.getByRole('link', { name: 'Edit' }).getAttribute('href'))?.includes('/account'),
  )

  // --- the Keycloak console link ------------------------------------------------------------------------
  // By exact name: there are two links to the account console now, and /Open/i would also match "Edit"
  // if that ever gained a longer label.
  const securityLink = page.getByRole('link', { name: 'Open', exact: true })
  const href = await securityLink.getAttribute('href')
  check(
    'security settings link at the identity provider, built by keycloak-js',
    typeof href === 'string' && href.includes('/account') && href.startsWith('http'),
  )
  check('it opens in a new tab', (await securityLink.getAttribute('target')) === '_blank')
  check(
    'and does not hand the opener a window reference',
    ((await securityLink.getAttribute('rel')) ?? '').includes('noopener'),
  )

  // --- appearance ----------------------------------------------------------------------------------------
  const darkSwitch = page.getByRole('checkbox', { name: 'Dark mode' })
  const wasDark = await darkSwitch.isChecked()
  await darkSwitch.click()
  check(
    'the dark mode switch actually switches the theme',
    await waitUntil(async () => (await darkSwitch.isChecked()) !== wasDark),
  )
  check(
    'and persists it, so a reload does not undo it',
    await waitUntil(async () =>
      (await page.evaluate(() => localStorage.getItem('themeMode'))) === (wasDark ? 'light' : 'dark')),
  )
  await darkSwitch.click()

  await shot('01-page')

  // --- the data export -------------------------------------------------------------------------------------
  await page.getByRole('button', { name: 'Download' }).click()
  check('downloading data calls the export endpoint', await waitUntil(() => exportRequest !== null))
  check(
    'and carries a bearer token, which a plain link could not',
    (exportRequest?.auth ?? '').startsWith('Bearer '),
  )

  await close()
})
