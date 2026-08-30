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
    (await page.getByText(/kept in your Lahendus ID/i).count()) > 0,
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
  // The return link Keycloak validates against the client's redirect URIs, exactly as it validates
  // a login's — so it must not carry a fragment either (EZ-1825). Read back out of the built URL
  // rather than trusted, because the default it replaced is now *wrong* rather than merely
  // different: `init()` pins `keycloak.redirectUri` to whichever page the bundle loaded on.
  const referrer = new URL(href ?? 'https://x.invalid').searchParams.get('referrer_uri')
  check(`the console gets a return link (got ${referrer})`, typeof referrer === 'string')
  check(
    `and it carries no fragment (got ${referrer})`,
    typeof referrer === 'string' && !referrer.includes('#'),
  )
  check(
    `and it points back at this page (got ${referrer})`,
    typeof referrer === 'string' && referrer.endsWith('/account'),
  )

  check('it opens in a new tab', (await securityLink.getAttribute('target')) === '_blank')
  check(
    'and does not hand the opener a window reference',
    ((await securityLink.getAttribute('rel')) ?? '').includes('noopener'),
  )

  // --- appearance ----------------------------------------------------------------------------------------
  // Three buttons, not a switch: the theme gained a "System" state (audit X-038), because the old
  // two-state control destroyed follow-the-OS the first time anyone touched it and offered no way
  // back. `System` stores nothing — the preference is the *absence* of an override.
  const themeButton = (name) => page.getByRole('button', { name, exact: true })
  const stored = () => page.evaluate(() => localStorage.getItem('themeMode'))

  await themeButton('Dark').click()
  check(
    'choosing Dark switches the theme and stores it',
    await waitUntil(async () => (await stored()) === 'dark'),
  )
  await themeButton('Light').click()
  check(
    'and choosing Light switches back',
    await waitUntil(async () => (await stored()) === 'light'),
  )
  await themeButton('System').click()
  check(
    'choosing System clears the override rather than storing a third value',
    await waitUntil(async () => (await stored()) === null),
  )
  check(
    'and the picker shows System as the one in force',
    (await themeButton('System').getAttribute('aria-pressed')) === 'true',
  )

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
