// The landing page has to answer "are you already signed in?" before it can label its own button,
// and for a while it answered before it knew. This covers the three states that produces.
//
//   cd web && npx playwright test landing-page
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

test('landing-page', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ shotPrefix: 'landing-' })

  await fakeApi(
    page,
    [
      // The endpoint is `/statistics/common`; naming it `/statistics` answered a URL one segment
      // deeper than the needle described.
      ['/statistics/common', () => ({ total_submissions: 123456, total_users: 7890, in_auto_assessing: 3 })],
      ['/account/checkin', () => ({})],
      ['/courses', () => ({ courses: [] })],
    ],
    { log: false },
  )

  /** Boot /landing with the stub seeded for a given session state. */
  async function open({ auth = 'yes', delayMs = 0 } = {}) {
    await page.goto(BASE_URL)
    await page.evaluate(
      ([a, d]) => {
        localStorage.setItem('stubAuth', a)
        if (d) localStorage.setItem('stubAuthDelayMs', String(d))
        else localStorage.removeItem('stubAuthDelayMs')
      },
      [auth, delayMs],
    )
    await page.goto(`${BASE_URL}/landing`)
  }

  // The navbar is a plain fixed Box, not a <header> or <nav>, and src/ deliberately carries no
  // data-testid attributes — so target by text. Not by DOM order: the language toggle added in
  // EZ-1820 sits to the left of the CTA, so the CTA is no longer the first <button> on the page.
  const ctaByText = (text) => page.getByRole('button', { name: text })

  /**
   * Every button's label and disabled state, read in one pass.
   *
   * Deliberately not a Playwright locator: locators auto-wait, and while the page is still deciding
   * whether there is a session, asking for an element that is not there yet blocks for the default
   * 30s — long enough for the very state under test to resolve and disappear. That is not a
   * hypothetical; it is how the first version of this script failed.
   */
  const buttonStates = () =>
    page.evaluate(() =>
      [...document.querySelectorAll('button')].map((b) => ({
        text: b.textContent?.trim() ?? '',
        disabled: b.disabled,
      })),
    )

  // ---------------------------------------------------------------------------
  // 1. Signed out: the page offers a way in, and it is not disabled.
  // ---------------------------------------------------------------------------
  await open({ auth: 'none' })
  await waitUntil(async () => (await ctaByText('Log in').count()) > 0)

  check('signed out: navbar offers "Log in"', (await ctaByText('Log in').count()) > 0)
  check('signed out: no "Open Lahendus"', (await ctaByText('Open Lahendus').count()) === 0)
  check('signed out: hero says "Get started"', (await ctaByText('Get started').count()) > 0)
  check('signed out: log in is enabled', await ctaByText('Log in').first().isEnabled())
  await shot('01-signed-out')

  // The substantive claim of this whole change: logging in from here lands in the app, not back
  // on the marketing page. The stub records the options instead of navigating away.
  await ctaByText('Log in').first().click()
  const loginCalls = await page.evaluate(() => globalThis.__stubLoginCalls ?? [])
  check('login() was called', loginCalls.length > 0)
  check(
    `login() targets /courses, not /landing (got ${loginCalls[0]?.redirectUri ?? 'nothing'})`,
    typeof loginCalls[0]?.redirectUri === 'string' &&
      loginCalls[0].redirectUri.endsWith('/courses'),
  )

  // ---------------------------------------------------------------------------
  // 2. Signed in: the page says who you are, and offers the way onward.
  // ---------------------------------------------------------------------------
  await open({ auth: 'yes' })
  await waitUntil(async () => (await ctaByText('Open Lahendus').count()) > 0)

  check('signed in: navbar says "Open Lahendus"', (await ctaByText('Open Lahendus').count()) > 0)
  check('signed in: never says "Log in"', (await ctaByText('Log in').count()) === 0)
  check('signed in: hero says "Go to courses"', (await ctaByText('Go to courses').count()) > 0)
  check('signed in: shows the first name', (await page.getByText('Test', { exact: true }).count()) > 0)
  await shot('02-signed-in')

  // And it goes there, in one click, without a trip through the IdP.
  await ctaByText('Open Lahendus').first().click()
  await waitUntil(async () => new URL(page.url()).pathname === '/courses')
  check(`"Open Lahendus" navigates to /courses (${new URL(page.url()).pathname})`,
    new URL(page.url()).pathname === '/courses')

  // ---------------------------------------------------------------------------
  // 3. Still checking: the button must not claim anything it does not yet know.
  //
  //    This is the regression that started the change — the page rendered "Log in" to a signed-in
  //    visitor for as long as check-sso took, and clicking it in that window sent them through the
  //    IdP for no reason.
  // ---------------------------------------------------------------------------
  // 4s, generously more than the assertions below need. The window has to outlast a loaded CI
  // runner, and the cost of it being too long is nothing — the script waits for it to close anyway.
  await open({ auth: 'yes', delayMs: 4000 })

  // As soon as React has painted, but while init() is still pending. `page.goto` resolves on the
  // load event, which is before the app's first render — reading straight afterwards saw no buttons
  // at all and failed for a reason that had nothing to do with the state under test.
  await waitUntil(async () => (await buttonStates()).length > 0)
  const during = await buttonStates()
  const duringLabels = during.map((b) => b.text)

  check(
    `while checking: nothing says "Log in" (buttons: ${JSON.stringify(duringLabels)})`,
    !duringLabels.some((t) => t.includes('Log in')),
  )
  // The navbar CTA is the one that renders a spinner rather than a word while the answer is
  // pending, so it is the empty-labelled button — not `during[0]`, which was the CTA only until
  // the language toggle moved in to its left (EZ-1820).
  check(
    `while checking: navbar CTA is disabled (buttons: ${JSON.stringify(duringLabels)})`,
    during.some((b) => b.text === '' && b.disabled),
  )
  check(
    'while checking: hero CTA is disabled',
    during.some((b) => b.text.includes('Get started') && b.disabled),
  )
  await shot('03-still-checking')

  // ...and resolves to the signed-in state once the answer arrives.
  await waitUntil(async () => (await ctaByText('Open Lahendus').count()) > 0, { timeout: 10000 })
  check('after checking: settles on "Open Lahendus"', (await ctaByText('Open Lahendus').count()) > 0)
  check('after checking: CTA is enabled again', await ctaByText('Open Lahendus').first().isEnabled())
  await shot('04-settled')

  await close()
})
