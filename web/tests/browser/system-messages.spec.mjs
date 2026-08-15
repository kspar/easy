// System message banners: rendering, severity behaviour, links, dismissal, and the polling that
// makes one appear without a reload.
//
//   cd web && npx playwright test system-messages
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

test('system-messages', async ({ launch, check }) => {
  const ENDPOINT = '/management/common/notifications'

  const URGENT = {
    id: '1',
    message: 'Maintenance today 21:00-22:00',
    severity: 'URGENT',
    link_url: 'https://example.org/notice',
    link_label: 'What changes',
  }
  const INFO = { id: '2', message: 'New: exercise packs', severity: 'INFO' }

  /** Boot the app with a mutable list of messages the stubbed endpoint returns. */
  async function boot(initial) {
    const { page, shot, close } = await launch({ shotPrefix: 'sysmsg-' })
    const state = { messages: initial }
    await fakeApi(
      page,
      [
        [ENDPOINT, () => ({ messages: state.messages })],
        ['/account/checkin', () => ({})],
        ['/courses', () => ({ courses: [] })],
      ],
      { log: false },
    )
    await page.goto(`${BASE_URL}/courses`)
    await waitUntil(async () => (await page.locator('[class*=MuiAppBar]').count()) > 0)
    return { close, page, shot, state }
  }

  const alerts = (page) => page.locator('[class*=MuiAlert-root]')
  const alertTexts = async (page) =>
    (await alerts(page).allInnerTexts()).map((s) => s.trim().replace(/\s+/g, ' '))

  // --- both severities render, with the right affordances ------------------------------------------
  {
    const { close, page, shot } = await boot([URGENT, INFO])
    await waitUntil(async () => (await alerts(page).count()) >= 2)
    const texts = await alertTexts(page)

    check(`both messages render (${texts.length})`, texts.length === 2)
    check('urgent text is shown', texts.some((t) => t.includes('Maintenance today')))
    check('info text is shown', texts.some((t) => t.includes('exercise packs')))

    // Urgent first — a maintenance notice must not sit underneath a feature tip. The server orders
    // them; this asserts the client does not reorder or reverse that on the way to the DOM.
    check(`urgent is rendered first (${texts[0]?.slice(0, 24)})`, texts[0].includes('Maintenance'))

    // The link is an anchor with target=_blank, so ctrl/cmd-click behaves normally.
    const link = page.locator('[class*=MuiAlert-root] a')
    check('the link renders as an anchor', (await link.count()) === 1)
    check(`link href is the configured one`, (await link.first().getAttribute('href')) === URGENT.link_url)
    check('link opens in a new tab', (await link.first().getAttribute('target')) === '_blank')

    // Exactly one close button: INFO can be dismissed, URGENT deliberately cannot.
    const closes = page.locator('[class*=MuiAlert-root] button[aria-label=Close], [class*=MuiAlert-action] button:not(a)')
    check(`only one message is dismissible (${await closes.count()})`, (await closes.count()) === 1)
    await shot('01-both')
    await close()
  }

  // --- dismissing INFO hides it, and it stays hidden across a reload --------------------------------
  {
    const { close, page, shot } = await boot([URGENT, INFO])
    await waitUntil(async () => (await alerts(page).count()) >= 2)

    await page.locator('[class*=MuiAlert-root] button[aria-label=Close]').first().click()
    await waitUntil(async () => (await alerts(page).count()) === 1)
    let texts = await alertTexts(page)
    check(`dismissing INFO leaves only URGENT (${texts.join(' | ')})`, texts.length === 1 && texts[0].includes('Maintenance'))

    await page.reload()
    await waitUntil(async () => (await alerts(page).count()) >= 1)
    texts = await alertTexts(page)
    check(
      `it stays dismissed across a reload (${texts.join(' | ')})`,
      texts.length === 1 && !texts.some((t) => t.includes('exercise packs')),
    )
    check(
      'the dismissal is recorded in localStorage',
      (await page.evaluate(() => localStorage.getItem('dismissedSystemMessages') ?? '')).includes('"2"'),
    )
    await shot('02-dismissed')
    await close()
  }

  // --- nothing to say means nothing rendered --------------------------------------------------------
  {
    const { close, page } = await boot([])
    await page.waitForTimeout(1500)
    check(`no messages means no banner (${await alerts(page).count()})`, (await alerts(page).count()) === 0)
    await close()
  }

  // --- a message appears without a reload -----------------------------------------------------------
  // The substantive claim of the whole feature. The poll interval is 60s, far too long for a test, so
  // this leans on the other half of the mechanism: React Query refetches on window focus, which is
  // also the case that matters most in practice (a tab left open in the background).
  {
    const { close, page, shot, state } = await boot([])
    await page.waitForTimeout(1000)
    check('starts with nothing', (await alerts(page).count()) === 0)

    state.messages = [URGENT] // as if an admin had just posted it

    // Waits out the real 60s refetchInterval rather than simulating a tab regaining focus.
    //
    // Three attempts went into faking that focus — window `focus`, then `visibilitychange` on
    // document, then on window with the visibilityState property driven alongside — and each reported
    // this working feature as broken. Testing the production mechanism directly costs a minute of
    // wall clock and asserts the thing users actually rely on; guessing at a library's internal
    // listener registration asserts the guess.
    //
    // This is why the script is the slow one in the suite. Deliberate.
    const appeared = await waitUntil(async () => (await alerts(page).count()) > 0, {
      timeout: 75_000,
      interval: 2000,
    })
    check('a new message appears with no reload', appeared)
    if (appeared) {
      check(
        `and it is the right one (${(await alertTexts(page))[0]?.slice(0, 30)})`,
        (await alertTexts(page))[0].includes('Maintenance'),
      )
    }
    await shot('03-appeared')
    await close()
  }
})
