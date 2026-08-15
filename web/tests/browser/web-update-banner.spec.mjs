// The "a new version is available" banner (EZ-1752): does a changed build stamp produce a banner,
// do its two buttons do what they say, and does a dismissal apply to that build and no other.
//
//   cd web && npx playwright test web-update-banner
//
// The exact comparison rules — same commit, rollbacks, `unknown`, malformed bodies — are pinned in
// tests/unit/web-version.test.mjs, which needs no browser. What can only be checked here is the wiring: that
// the fetch happens at all, that the banner reaches the DOM, and that clicking reloads rather than
// merely looking like it might.
import { test } from '../support/spec.mjs'
import { fakeApi, BASE_URL, waitUntil } from '../support/harness.mjs'

test('web-update-banner', async ({ launch, check }) => {
  const STAMP_URL = '**/version.json*'
  const DEPLOYED = { version: '4.1', commit: 'deadbee', builtAt: '2026-08-11T09:00:00.000Z' }

  /**
   * Boot the app with a stubbed build stamp.
   *
   * `stamp` is re-read on every request, so a test can change what "deployed" means without
   * re-launching — which is how the per-build dismissal is checked.
   */
  async function boot({ stamp, shotPrefix = 'webupdate-' } = {}) {
    const { page, shot, close } = await launch({ shotPrefix })
    const state = { stamp }

    await page.route(STAMP_URL, async (route) => {
      const body = state.stamp
      if (body === 'html') {
        // What a server with no version.json actually returns, thanks to the SPA fallback: index.html
        // with a 200, not a 404. The client has to treat this as "nothing to say".
        return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><html></html>' })
      }
      if (body === null) return route.fulfill({ status: 404, contentType: 'text/plain', body: 'nope' })
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    })

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
    await waitUntil(async () => (await page.locator('[class*=MuiAppBar]').count()) > 0)
    return { close, page, shot, state }
  }

  const alerts = (page) => page.locator('[class*=MuiAlert-root]')
  const bannerText = async (page) =>
    (await alerts(page).allInnerTexts()).map((s) => s.trim().replace(/\s+/g, ' ')).join(' | ')

  /**
   * Wait for the banner to finish opening.
   *
   * It arrives inside a `Collapse`, so it is in the DOM at full width and zero height for the length
   * of the animation. Every assertion here passes during that window, and the first screenshot taken
   * without this showed a page with no banner on it — a picture that would have been read later as
   * evidence the feature was broken.
   */
  const opened = (page) =>
    waitUntil(async () => ((await alerts(page).first().boundingBox())?.height ?? 0) > 40)

  // --- a changed stamp raises the banner ------------------------------------------------------------
  {
    const { close, page, shot } = await boot({ stamp: DEPLOYED })
    const appeared = await waitUntil(async () => (await alerts(page).count()) > 0)
    check('a different deployed build raises the banner', appeared)

    const text = await bannerText(page)
    check(`it says a new version is available (${text.slice(0, 40)})`, text.includes('new version'))

    // The warning about unsaved work is the reason this banner asks instead of reloading by itself.
    // If the copy ever loses it, the banner is inviting people to lose a half-written solution.
    check('it warns that unsaved work will be lost', text.toLowerCase().includes('unsaved work'))

    const reloadButton = page.getByRole('button', { name: 'Reload' })
    check('there is a Reload button', (await reloadButton.count()) === 1)
    check(
      'there is a dismiss button',
      (await page.getByRole('button', { name: 'Not now' }).count()) === 1,
    )
    check('the banner is fully open, not a zero-height stub', await opened(page))
    await shot('01-available')
    await close()
  }

  // --- Reload actually reloads ----------------------------------------------------------------------
  {
    const { close, page } = await boot({ stamp: DEPLOYED })
    await waitUntil(async () => (await alerts(page).count()) > 0)

    // Mark the document, then check the mark is gone: a fresh document is the only thing that
    // clears it, so this distinguishes a real reload from a re-render that merely looks like one.
    await page.evaluate(() => {
      window.__notReloaded = true
    })
    await page.getByRole('button', { name: 'Reload' }).click()
    const reloaded = await waitUntil(async () => !(await page.evaluate(() => window.__notReloaded === true)))
    check('Reload loads the document again', reloaded)
    await close()
  }

  // --- dismissing hides it, and remembers which build was dismissed -----------------------------------
  {
    const { close, page, shot, state } = await boot({ stamp: DEPLOYED })
    await waitUntil(async () => (await alerts(page).count()) > 0)

    await page.getByRole('button', { name: 'Not now' }).click()
    const hidden = await waitUntil(async () => (await alerts(page).count()) === 0)
    check('dismissing hides the banner', hidden)

    check(
      'the dismissed build is recorded, by commit',
      (await page.evaluate(() => localStorage.getItem('dismissedWebUpdate'))) === DEPLOYED.commit,
    )

    await page.reload()
    await waitUntil(async () => (await page.locator('[class*=MuiAppBar]').count()) > 0)
    await page.waitForTimeout(1000)
    check(`it stays dismissed across a reload (${await bannerText(page)})`, (await alerts(page).count()) === 0)
    await shot('02-dismissed')

    // The point of keying dismissal by commit rather than a boolean: the *next* deploy is a
    // different build, and someone who waved away Tuesday's release should still hear about
    // Thursday's. A boolean would have silenced this tab permanently.
    state.stamp = { ...DEPLOYED, commit: 'cafe123', version: '4.2' }
    await page.reload()
    const reappeared = await waitUntil(async () => (await alerts(page).count()) > 0)
    check('a later deploy is announced again', reappeared)
    await opened(page)
    await shot('03-next-deploy')
    await close()
  }

  // --- the quiet cases ----------------------------------------------------------------------------
  // No version.json on the server is the state of every environment until the next deploy, and of
  // `vite dev` permanently. Both answers a server can give must stay silent.
  {
    const { close, page } = await boot({ stamp: 'html', shotPrefix: 'webupdate-quiet-' })
    await page.waitForTimeout(1500)
    check(
      `index.html from the SPA fallback says nothing (${await bannerText(page)})`,
      (await alerts(page).count()) === 0,
    )
    await close()
  }

  {
    const { close, page } = await boot({ stamp: null, shotPrefix: 'webupdate-quiet-' })
    await page.waitForTimeout(1500)
    check('a 404 says nothing', (await alerts(page).count()) === 0)
    await close()
  }

  {
    // A build that cannot say which build it is must never be reported as an update — otherwise
    // every laptop build nags forever.
    const { close, page } = await boot({ stamp: { ...DEPLOYED, commit: 'unknown' }, shotPrefix: 'webupdate-quiet-' })
    await page.waitForTimeout(1500)
    check('an unknown deployed commit says nothing', (await alerts(page).count()) === 0)
    await close()
  }
})
