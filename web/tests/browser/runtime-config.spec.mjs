// Verifies EZ-1726: environment config comes from /config.json at runtime, and a bad
// config.json fails visibly instead of white-screening.
//
//   cd web && npx playwright test runtime-config
import { test } from '../support/spec.mjs'
import { BASE_URL } from '../support/harness.mjs'

test('runtime-config', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ shotPrefix: 'runtime-config-' })

  const goodKeycloak = { url: 'http://localhost:9999/auth/', realm: 'from-config', clientId: 'cfg' }

  /** Serve a chosen /config.json body (string) for the next navigation. */
  async function serveConfig(body, status = 200) {
    await page.unroute('**/config.json').catch(() => {})
    await page.route('**/config.json', (route) =>
      route.fulfill({ status, contentType: 'application/json', body }),
    )
  }

  // ---------------------------------------------------------------------------
  // 1. emsRoot from config.json actually drives where API requests go. This is the
  //    substantive claim: if it were still baked in at build time, requests would go to /v2.
  // ---------------------------------------------------------------------------

  await serveConfig(
    JSON.stringify({ emsRoot: '/api-from-config', keycloak: goodKeycloak }),
  )

  // fakeApi() routes '**/v2/**' specifically, so it deliberately isn't used here — the whole
  // point is that requests should NOT be going to /v2. Route both prefixes by hand instead.
  const fromConfig = []
  const fromBuiltIn = []
  const stub = (list) => (route) => {
    list.push(new URL(route.request().url()).pathname)
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  }
  await page.route('**/api-from-config/**', stub(fromConfig))
  await page.route('**/v2/**', stub(fromBuiltIn))

  await page.goto(BASE_URL)

  // Poll the recorded routes rather than waiting on render or on page.waitForRequest — the
  // checkin call fires well after first paint, and both of those raced it during development.
  for (let i = 0; i < 80 && fromConfig.length === 0; i++) {
    await page.waitForTimeout(250)
  }

  check(
    `API calls use emsRoot from config.json (${fromConfig.length} to /api-from-config)`,
    fromConfig.length > 0,
  )
  check(`no calls to the baked-in /v2 (saw ${fromBuiltIn.length})`, fromBuiltIn.length === 0)
  check('app rendered its shell', (await page.locator('header, [class*="MuiAppBar"]').count()) > 0)
  await shot('01-booted-from-config')

  // ---------------------------------------------------------------------------
  // 2. Failure modes must show the error screen, not a blank page.
  // ---------------------------------------------------------------------------

  async function expectConfigError(label, body, status = 200) {
    await serveConfig(body, status)
    await page.goto(`${BASE_URL}/?cachebust=${label}`)
    await page.waitForSelector('text=Configuration error', { timeout: 10000 }).catch(() => {})
    const shown = await page.locator('text=Configuration error').count()
    const detail = await page.locator('#root p').first().textContent().catch(() => '')
    check(`${label} -> error screen shown`, shown > 0)
    return detail ?? ''
  }

  const missingDetail = await expectConfigError('missing', 'not found', 404)
  check('missing config names the HTTP status', /404/.test(missingDetail))

  const malformedDetail = await expectConfigError('malformed', '{ this is not json')
  check('malformed config says so', /not valid JSON/i.test(malformedDetail))

  const incompleteDetail = await expectConfigError(
    'incomplete',
    JSON.stringify({ emsRoot: '/v2', keycloak: { url: 'x', clientId: 'y' } }),
  )
  check(
    `incomplete config names the missing key (got: ${incompleteDetail})`,
    /keycloak\.realm/.test(incompleteDetail),
  )
  await shot('02-config-error')

  await close()
})
