// EZ-1825: signing in used to be able to run away with itself.
//
// keycloak-js defaults `redirectUri` to `location.href`, Keycloak answers a fragment-mode request
// by *appending* its `state&session_state&iss&code` to whatever fragment the redirect URI already
// carries, and the only place those params are ever stripped back out is a `replaceState` that
// does not run when `init()` rejects. Put together: a failed init left the params in the URL, the
// retry handed them back as part of the redirect URI, and each bounce made the URL one group
// longer until Keycloak refused it outright with `invalid_redirect_uri`.
//
// Three claims here, and each one fails on the code as it stood:
//   1. the redirect URI never carries a fragment, whatever is in the address bar;
//   2. one arrival produces one redirect, not one per render;
//   3. a failed init shows a way out instead of bouncing to the IdP that just failed.
//
//   cd web && npx playwright test auth-redirect-loop
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

test('auth-redirect-loop', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ shotPrefix: 'auth-loop-' })

  await fakeApi(
    page,
    [
      ['/account/checkin', () => ({})],
      ['/courses', () => ({ courses: [] })],
      // `open()` passes through `/` on its way to seeding localStorage, and `/` sends a signed-out
      // visitor to the landing page. Faked so that detour cannot answer 401 and set off the very
      // session recovery this spec is counting.
      ['/statistics/common', () => ({ total_submissions: 1, total_users: 1, in_auto_assessing: 0 })],
    ],
    { log: false },
  )

  /** Boot a path with the stub seeded for a given session state. English, so the copy is legible. */
  async function open(path, { auth = 'none', init = 'ok' } = {}) {
    await page.goto(BASE_URL)
    await page.evaluate(
      ([a, i]) => {
        localStorage.setItem('stubAuth', a)
        localStorage.setItem('language', 'en')
        if (i === 'fail') localStorage.setItem('stubAuthInit', 'fail')
        else localStorage.removeItem('stubAuthInit')
      },
      [auth, init],
    )
    await page.goto(`${BASE_URL}${path}`)
  }

  const loginCalls = () => page.evaluate(() => globalThis.__stubLoginCalls ?? [])

  // ---------------------------------------------------------------------------
  // 1. Arriving signed out on a protected page, with a callback fragment still in the URL — which
  //    is exactly the state a half-finished login leaves behind.
  // ---------------------------------------------------------------------------

  const fragment = '#state=aaaa-bbbb&session_state=cccc&iss=https%3A%2F%2Fidp.example&code=dddd'
  await open(`/courses${fragment}`)

  // Poll rather than wait on a render: the redirect is fired from an effect, so it lands after
  // first paint, and a locator wait here would race the very thing being counted.
  await waitUntil(async () => (await loginCalls()).length > 0)

  const calls = await loginCalls()
  check(`one arrival, one redirect (saw ${calls.length})`, calls.length === 1)

  const uri = calls[0]?.redirectUri
  const named = typeof uri === 'string' && uri !== ''
  check(`login() names its own redirect URI (got ${uri})`, named)
  // The claim that matters. `location.href` would have carried the fragment above straight back to
  // Keycloak, which appends to it rather than replacing it.
  //
  // `named &&`, not just the fragment test: an absent redirectUri contains no '#' either, so on its
  // own this check passes loudest exactly when the fix has been removed.
  check(`redirect URI carries no fragment (got ${uri})`, named && !uri.includes('#'))
  check(`redirect URI is still the page asked for (got ${uri})`, named && uri.endsWith('/courses'))

  await shot('01-signed-out-redirect')

  // ---------------------------------------------------------------------------
  // 2. The adapter never came up. Before EZ-1825 this was reported as "initialised, no session"
  //    and answered with a redirect to the IdP — i.e. to the thing that had just failed.
  // ---------------------------------------------------------------------------

  await open('/courses', { init: 'fail' })

  const alert = page.locator('[role="alert"]')
  await alert.first().waitFor({ timeout: 10000 }).catch(() => {})

  const text = (await alert.first().textContent().catch(() => '')) ?? ''
  check(`a failed init explains itself (got: ${text.slice(0, 80)})`, /login service/i.test(text))
  check('and offers a retry', /refresh/i.test(text))

  // The one that would have caught the loop: no redirect at all.
  const afterFailure = await loginCalls()
  check(
    `a failed init does not redirect to the IdP (saw ${afterFailure.length} login calls)`,
    afterFailure.length === 0,
  )

  // Still true a moment later — a loop that took one extra tick to start would pass the check above.
  await page.waitForTimeout(1500)
  check(
    `and still has not, a moment later (saw ${(await loginCalls()).length})`,
    (await loginCalls()).length === 0,
  )
  check('the URL stayed put', new URL(page.url()).pathname === '/courses')

  await shot('02-init-failed')

  // ---------------------------------------------------------------------------
  // 3. The ordinary case still works — a guard that also blocks signed-in users is not a fix.
  // ---------------------------------------------------------------------------

  await open('/courses', { auth: 'yes' })
  await page.locator('header, [class*="MuiAppBar"]').first().waitFor({ timeout: 10000 })
  check('a signed-in user still gets the app', (await loginCalls()).length === 0)

  await close()
})
