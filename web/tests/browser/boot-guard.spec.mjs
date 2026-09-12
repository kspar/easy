// EZ-1908: a tab restored from a stale index.html asks for a chunk the deploy removed and comes
// back blank. public/boot-guard.js reloads it once — and, just as importantly, refuses to reload a
// tab that has already rendered, where somebody may be halfway through writing a solution.
//
// The unit test (tests/unit/boot-guard.test.mjs) owns the decision rules against fake globals.
// What only a browser can show is the wiring: that the guard is a real request sitting ahead of
// the module it guards, that it sees a genuine subresource `error` event — which does not bubble,
// so the capture phase is load-bearing — and that main.tsx actually tells it the app is up.
//
//   cd web && npx playwright test boot-guard
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const MARKER = 'easyAssetReloadAt'

test('boot-guard', async ({ launch, check }) => {
  const { page, close } = await launch({ shotPrefix: 'boot-guard-' })

  await fakeApi(
    page,
    [
      ['/account/checkin', () => ({})],
      ['/courses', () => ({ courses: [] })],
      ['/messages', () => ({ messages: [] })],
    ],
    { log: false },
  )

  // A URL under /assets/ that is gone, which is exactly what a stale document asks for. Faked
  // rather than relied upon, because the dev server answers an unknown path its own way and this
  // spec is about the guard's reaction, not about vite's 404.
  await page.route('**/assets/boot-guard-probe*.js', (route) => route.fulfill({ status: 404 }))

  /** Ask the page for an asset that will fail, the way the browser would. */
  const failOwnAsset = (name) =>
    page.evaluate((url) => {
      const script = document.createElement('script')
      script.src = url
      document.head.appendChild(script)
    }, `/assets/boot-guard-probe-${name}.js`)

  /** Somebody else's asset failing: index.html links a Google Fonts stylesheet. */
  const failForeignAsset = () =>
    page.evaluate(() => {
      const link = document.createElement('link')
      link.rel = 'stylesheet'
      // An unroutable host rather than a faked 404, so this stays a network failure with no
      // interception involved — the shape an ad blocker produces.
      link.href = 'https://fonts.example.invalid/css2?family=Outfit'
      document.head.appendChild(link)
    })

  await page.goto(`${BASE_URL}/courses`)
  await page.locator('[class*=MuiAppBar]').first().waitFor()

  // --- the wiring ----------------------------------------------------------------------------
  // Order in the document is the whole reason this is a separate file: the thing that fails is the
  // module, so the handler has to be registered before it.
  const wiring = await page.evaluate(() => {
    const scripts = [...document.querySelectorAll('script[src]')]
    const guard = scripts.findIndex((s) => s.getAttribute('src').includes('boot-guard.js'))
    // The app's own module, not vite's. The dev server injects `/@vite/client` ahead of everything
    // in the document, and it is not what this is about — in a built dist neither it nor
    // `/@react-refresh` exists at all.
    const entry = scripts.findIndex(
      (s) => s.type === 'module' && !s.getAttribute('src').startsWith('/@'),
    )
    return {
      guard,
      entry,
      installed: typeof window.__easyBootGuard?.booted === 'function',
      booted: window.__easyAppBooted === true,
      marker: sessionStorage.getItem('easyAssetReloadAt'),
    }
  })
  check('the guard is loaded as its own script', wiring.guard >= 0)
  check('it comes before the module it guards', wiring.guard < wiring.entry)
  check('it installed itself on window', wiring.installed)
  check('main.tsx reported that the app booted', wiring.booted)
  check('a clean boot leaves no spent reload behind', wiring.marker === null)

  // Counted from here, so every navigation below is one the guard caused.
  let navigations = 0
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) navigations++
  })

  // --- a live tab is never reloaded ------------------------------------------------------------
  // The check with teeth. This is a CodeMirror mode or KaTeX going missing under someone who is
  // typing, and EZ-1752 settled what happens then: nothing, until they ask for it.
  await failOwnAsset('live')
  await waitUntil(() => navigations > 0, { timeout: 1500 })
  check('a missing chunk in a tab that has rendered does not reload it', navigations === 0)

  // --- but it does say so ----------------------------------------------------------------------
  // Without this the failure is invisible: every one of those imports is behind a catch, so a
  // missing CodeMirror mode reads as "syntax colouring was never implemented".
  const banner = page.locator('text=could not be loaded').first()
  await banner.waitFor({ timeout: 5000 })
  check('the banner explains that the bundle is stale', await banner.isVisible())
  check(
    'it offers the reload rather than performing one',
    (await page.getByRole('button', { name: 'Reload' }).count()) > 0,
  )
  // A dismissal given for "there is a newer build" was not consent to hide a page that is already
  // failing to do what was asked of it.
  check(
    'and it cannot be dismissed',
    (await page.getByRole('button', { name: 'Not now' }).count()) === 0,
  )

  // --- somebody else's asset is not our problem ------------------------------------------------
  await page.evaluate(() => {
    window.__easyAppBooted = false
  })
  await failForeignAsset()
  await waitUntil(() => navigations > 0, { timeout: 1500 })
  check('a cross-origin stylesheet failing does not reload the page', navigations === 0)

  // --- a blank boot does get one reload --------------------------------------------------------
  // `__easyAppBooted` is put back the way it is on a fresh document, which is the state a restored
  // tab is in when its entry chunk 404s. Everything from here is the real path: a real error
  // event, the real capture-phase listener, real sessionStorage, a real reload.
  await failOwnAsset('preboot')
  await waitUntil(() => navigations > 0, { timeout: 5000 })
  check('a missing chunk before the app has booted reloads the page', navigations === 1)

  await page.locator('[class*=MuiAppBar]').first().waitFor()
  const afterReload = await page.evaluate(() => ({
    booted: window.__easyAppBooted === true,
    marker: sessionStorage.getItem('easyAssetReloadAt'),
  }))
  check('the reloaded tab boots normally', afterReload.booted)
  check('and clears the spent reload once it has', afterReload.marker === null)

  // --- and only one ----------------------------------------------------------------------------
  // A deploy that is genuinely broken is still broken after the reload. One more attempt would be
  // a tab that reloads forever, so the honest outcome is the blank page we started with.
  await page.evaluate(
    (marker) => {
      window.__easyAppBooted = false
      sessionStorage.setItem(marker, String(Date.now()))
    },
    MARKER,
  )
  await failOwnAsset('again')
  await waitUntil(() => navigations > 1, { timeout: 2000 })
  check('a tab that has already spent its reload does not reload again', navigations === 1)

  await close()
})
