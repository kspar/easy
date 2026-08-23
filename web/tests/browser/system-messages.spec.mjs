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

  /**
   * The same message with its nullable link fields spelled out.
   *
   * Used only by the legacy-dismissal block below, and the reason is the contract-warning budget:
   * core omits `link_url`/`link_label` when they are null (`@JsonInclude(NON_NULL)`), so a fixture
   * that omits them is faithful — but each such response costs two "missing nullable field"
   * warnings, and one more page load would have put this spec over its entry in
   * `contract-baseline.json`. Spelling them out here keeps the budget where it was while the blocks
   * above go on exercising the absent-field path, which is the one core actually produces.
   */
  const INFO_FULL = { ...INFO, link_url: null, link_label: null }

  /**
   * Boot the app with a mutable list of messages the stubbed endpoint returns.
   *
   * `dismissed` seeds localStorage before the app loads, for the EZ-1790 case where the browser
   * arrives already carrying dismissals from a previous life.
   */
  async function boot(initial, { dismissed = null } = {}) {
    const { page, shot, close } = await launch({ shotPrefix: 'sysmsg-' })
    if (dismissed !== null) {
      await page.addInitScript(
        (d) => localStorage.setItem('dismissedSystemMessages', d),
        dismissed,
      )
    }
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
    const stored = await page.evaluate(
      () => localStorage.getItem('dismissedSystemMessages') ?? '',
    )
    check(`the dismissal is recorded in localStorage (${stored})`, stored.length > 0)
    // And recorded as a content key, not as the row id. This asserted `"2"` until EZ-1790: keying on
    // a bigserial meant a dismissal of a since-deleted "message 2" silently suppressed a new one, so
    // the id appearing here would be the bug coming back.
    check('as a content key rather than the row id', stored.includes('sm1_') && !stored.includes('"2"'))
    await shot('02-dismissed')
    await close()
  }

  // --- a legacy dismissal does not suppress anything (EZ-1790) --------------------------------------
  {
    // The bug, exactly as it presented on dev: a correctly configured message that never appeared,
    // because this browser had once dismissed a *different* message that happened to hold this row
    // number. Row ids come from a bigserial and dev's database is periodically restored from an
    // anonymised dump, so a row number identifies nothing across time.
    const { close, page } = await boot([INFO_FULL], { dismissed: '["1","2","3"]' })
    await waitUntil(async () => (await alerts(page).count()) >= 1)

    check(
      `an old numeric dismissal no longer hides a message (${(await alertTexts(page)).join(' | ')})`,
      (await alertTexts(page)).some((t) => t.includes('exercise packs')),
    )
    // And the dead entries are gone rather than sitting there forever.
    const stored = await page.evaluate(() => localStorage.getItem('dismissedSystemMessages') ?? '')
    check(`the legacy entries are still on disk until the next write (${stored})`, stored.includes('"2"'))
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
