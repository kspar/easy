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

  /**
   * Boot a path with the stub seeded for a given session state. English, so the copy is legible.
   *
   * `delayMs` holds `init()` open for that long before it settles, which is the only way to model
   * the ordering that actually obtains in a browser. Left at 0 the stub resolves in a microtask,
   * so the adapter is initialised before any network answer can possibly arrive — the opposite of
   * a real page, where `init()` is a round trip to Keycloak and a 401 from core can easily beat it.
   */
  async function open(path, { auth = 'none', init = 'ok', delayMs = 0 } = {}) {
    await page.goto(BASE_URL)
    await page.evaluate(
      ([a, i, d]) => {
        localStorage.setItem('stubAuth', a)
        localStorage.setItem('language', 'en')
        if (i === 'fail') localStorage.setItem('stubAuthInit', 'fail')
        else localStorage.removeItem('stubAuthInit')
        if (d) localStorage.setItem('stubAuthDelayMs', String(d))
        else localStorage.removeItem('stubAuthDelayMs')
      },
      [auth, init, delayMs],
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

  // ---------------------------------------------------------------------------
  // 4. EZ-1828: opening a *course* URL directly, signed in. The loop that survived EZ-1825, and it
  //    is not a race — see the fix in AuthContext and AppLayout.
  //
  //    AppLayout sits outside RequireAuth and asks for the sidebar's course data as soon as it
  //    renders. That happens in a mount effect, and effects run child-first, so it fires *before*
  //    QueryProvider has registered a token provider and before `keycloak.init()` has settled.
  //    The request therefore goes out with no `Authorization` header at all, core answers 401, and
  //    the 401 handler concludes the session is gone and returns to the IdP — which hands back a
  //    perfectly good token, to a page that repeats the whole thing.
  //
  //    Modelled by answering 401 to any request with no bearer token, which is what core's proxy
  //    does. Without that, the stub answers 200 to an unauthenticated request and the bug is
  //    invisible from a test.
  // ---------------------------------------------------------------------------

  // Invented, like every other id in this suite. The report that prompted this named a real course,
  // and a fixture is a bad place to write one down.
  const COURSE = '9107'

  const unauthorized = (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'stub-401', code: null, attrs: {}, log_msg: 'no token' }),
    })

  /** A handler that first insists on a bearer token, exactly as the deployment does. */
  const authed = (fn) => async (ctx) => {
    if (!ctx.route.request().headers()['authorization']) return unauthorized(ctx.route)
    return fn(ctx)
  }

  /**
   * Whether the sidebar heading turns up — the visible end of the chain this whole file is about:
   * init settles, checkin answers, the gated queries are released, `/courses/:id/basic` is asked
   * and returns.
   *
   * A `waitFor` and not a `waitForTimeout` followed by `isVisible`, which is what this was and
   * which failed in CI while passing on a laptop every time. The chain is four round trips deep,
   * the fixed sleeps below were measured against a local dev server, and CI runs the suite about
   * three times slower — so the sleep expired mid-chain and a *slow* page was reported as a
   * *missing* one. The sleeps that remain are all waits for something to **not** happen, which is
   * the one thing a locator cannot express.
   */
  async function headingArrives() {
    const heading = page.getByText('Programming 2026').first()
    await heading.waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
    return heading.isVisible().catch(() => false)
  }

  // A second registration rather than an edit to the first: Playwright runs the most recently
  // added route handler first, so these win for the rest of the spec and sections 1-3 keep the
  // unauthenticated fixtures they were written against.
  await fakeApi(
    page,
    [
      ['/account/checkin', authed(() => ({}))],
      [`/courses/${COURSE}/basic`, authed(() => ({
        title: 'Programming 2026',
        alias: null,
        archived: false,
        color: '#1976d2',
        course_code: 'LTAT.03.001',
      }))],
      [`/student/courses/${COURSE}/exercises`, authed(() => ({ exercises: [] }))],
      // The page itself. Empty, because this section is about where the browser goes rather than
      // what it draws — but named rather than left to the catch-all below, whose bare `{}` makes
      // the hooks that unwrap a field log a React Query error and muddy the console.
      [`/teacher/courses/${COURSE}/exercises`, authed(() => ({ exercises: [] }))],
      [`/courses/${COURSE}/groups`, authed(() => ({ groups: [] }))],
      // Everything else this page asks for, answered but still made to show a token — the point of
      // the section is that nothing goes out without one.
      [/\/v2\//, authed(() => ({}))],
    ],
    { log: false, contract: false },
  )

  await open(`/courses/${COURSE}/exercises`, { auth: 'yes' })

  // The sidebar heading comes from `/courses/:id/basic` — the request that used to 401. Waiting for
  // it proves the page settled rather than merely failing to redirect yet.
  await page
    .locator('header, [class*="MuiAppBar"]')
    .first()
    .waitFor({ timeout: 10000 })
    .catch(() => {})

  // Long enough for a bounce to have started. The loop is driven by a network round trip, so a
  // check that runs on the same tick as the load would pass on the broken code too.
  await page.waitForTimeout(2000)

  const deepLinkCalls = await loginCalls()
  check(
    `a course URL opened directly does not return to the IdP (saw ${deepLinkCalls.length} login calls)`,
    deepLinkCalls.length === 0,
  )
  // Deliberately not checking the URL here. `login()` in the stub only takes notes — it does not
  // navigate unless a spec asks it to, which section 5 does and this one does not — so `page.url()`
  // is unchanged whether the loop is present or not, and a check on it passes just as loudly on the
  // broken code. The login count above is the whole of the claim.

  // The sidebar is the thing the ungated queries feed, so its heading is the proof they recovered
  // rather than being left failed and silent.
  check('the sidebar course heading still loads', await headingArrives())

  await shot('04-course-deep-link')

  // ---------------------------------------------------------------------------
  // 4b. The same arrival, with `init()` slow — which is the ordering a real browser produces and
  //     the one the stub otherwise cannot. `init()` is a round trip to Keycloak; a 401 from core
  //     is a round trip to a reverse proxy that rejects the request without a token before it
  //     reaches an application at all. The 401 winning is the normal case, not the exotic one.
  //
  //     Two separate guards can carry this, on purpose: AppLayout asks for nothing until
  //     `checkedIn`, and `recoverSession` ignores a 401 that arrives before the adapter has
  //     settled. The first is structural and the second is the net under it — remove either and
  //     this still passes, remove both and it does not. The check is deliberately written as the
  //     user-visible claim rather than naming a mechanism, so it keeps its meaning if the
  //     mechanisms move.
  // ---------------------------------------------------------------------------

  await open(`/courses/${COURSE}/exercises`, { auth: 'yes', delayMs: 600 })

  // Past the delay, so the assertion is about a settled page rather than one still holding its
  // breath — and long enough after it for a bounce to have been fired and recorded.
  await page.waitForTimeout(3000)

  const slowInitCalls = await loginCalls()
  check(
    `a slow identity provider does not turn a deep link into a redirect (saw ${slowInitCalls.length} login calls)`,
    slowInitCalls.length === 0,
  )
  check('and the page still arrives once it settles', await headingArrives())

  await shot('04b-slow-init-deep-link')

  // ---------------------------------------------------------------------------
  // 5. The second loop, and the one the sections above cannot see: **core rejects a token the IdP
  //    is perfectly happy to keep issuing.**
  //
  //    Core has several routes to this and they are all 401s rather than 403s —
  //    `EasyUserJwtConverter` raises `InvalidBearerTokenException` for an `easy_role` claim that is
  //    missing, empty or names a role it cannot map, and an issuer or JWKS mismatch behaves the
  //    same. Every one describes an account Keycloak signs in without complaint and this
  //    application cannot use.
  //
  //    `recoveringRef` does not help: it is one attempt *per page load*, and the redirect ends the
  //    page load. One bounce per load, forever, is still forever — and each load looked identical
  //    and innocent to a spec counting within it. Hence `stubLoginNavigates`, which makes the stub
  //    follow the redirect the way a live Keycloak with a healthy SSO session does, and a count
  //    that outlives the navigation. Both are what make a loop a thing this file can observe at
  //    all.
  //
  //    One bounce is right — the session might genuinely have died, and going to ask is how you
  //    find out. Two means nothing was learned from the first.
  // ---------------------------------------------------------------------------

  await fakeApi(page, [[/\/v2\//, ({ route }) => unauthorized(route)]], {
    log: false,
    contract: false,
  })

  const loginTotal = () =>
    page.evaluate(() => Number(sessionStorage.getItem('easyStubLoginTotal') ?? 0))

  await page.goto(BASE_URL)
  await page.evaluate(() => {
    localStorage.setItem('stubAuth', 'yes')
    localStorage.setItem('language', 'en')
    localStorage.removeItem('stubAuthInit')
    // From here the stub follows its own redirects, so the app is free to loop if it is going to.
    localStorage.setItem('stubLoginNavigates', 'yes')
    sessionStorage.removeItem('easyStubLoginTotal')
    sessionStorage.removeItem('easyAuthRecoveryAttempted')
  })
  await page.goto(`${BASE_URL}/courses/${COURSE}/exercises`)

  // 20s rather than the 8s default, because `waitUntil` *returns* on timeout instead of throwing:
  // give up too early here and the count is read as 0, which is reported as "the app never went to
  // the IdP" — the opposite of what happened, and a red build on CI where every step is slower.
  await waitUntil(async () => (await loginTotal()) > 0, { timeout: 20000 })
  // Generous, and deliberately so: this is the window in which the broken version racks up bounces.
  // Each one is a full page load, so a couple of seconds is many, and the check below reports the
  // number it actually saw rather than just failing.
  await page.waitForTimeout(4000)

  const bounces = await loginTotal()
  check(
    `core rejecting a freshly minted token costs exactly one trip to the IdP, not a loop (saw ${bounces})`,
    bounces === 1,
  )

  // The other half of the claim, and the reason stopping is not the same as giving up: stopping
  // silently would leave a spinner nobody can get past. `authFailed` puts ErrorAlert on screen.
  const failedAlert = page.locator('[role="alert"]')
  await failedAlert.first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
  check(
    'and says so, instead of leaving a page that never finishes loading',
    await failedAlert.first().isVisible().catch(() => false),
  )

  await shot('05-core-rejects-token')

  await close()
})
