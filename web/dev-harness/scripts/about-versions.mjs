// EZ-1709: the About page says what is deployed — web's version from the bundle, core's and the
// executors' from the server, and something honest when an executor is not answering.
//
//   cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
//   cd web/dev-harness && node scripts/about-versions.mjs
import { launch, checker, fakeApi, BASE_URL } from '../harness.mjs'

const check = checker()
const { browser, page, shot } = await launch({ shotPrefix: 'about-versions-' })

const VERSIONS = {
  core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' },
  executors: [
    { name: 'executor-1', version: '4.0', commit: 'abc1234', reachable: true },
    // Registered but silent. Rendering it is the point: an executor that is down is exactly what
    // someone reading this page needs to see.
    { name: 'executor-2', version: null, commit: null, reachable: false },
  ],
}

let versionsStatus = 200
const versionsCalls = []

await fakeApi(
  page,
  [
    ['/account/checkin', () => ({})],
    ['/statistics/common', () => ({ in_auto_assessing: 1, total_submissions: 2, total_users: 3 })],
    [
      '/unauth/versions',
      ({ route }) => {
        versionsCalls.push(route.request().headers()['authorization'] ?? null)
        if (versionsStatus !== 200) {
          route.fulfill({ status: versionsStatus, contentType: 'application/json', body: '{}' })
          return
        }
        return VERSIONS
      },
    ],
  ],
  { log: false },
)

/**
 * The versions block, as `{ label: value }` — it renders as a <dl>.
 *
 * Waits on the <dl> rather than on the text "Versions": the failure caption contains the word too,
 * so a text locator matches two elements and dies on strict mode in exactly the case being tested.
 */
async function readVersions() {
  await page.locator('dl').first().waitFor()
  return await page.evaluate(() => {
    const dl = document.querySelector('dl')
    if (!dl) return {}
    const out = {}
    const terms = [...dl.querySelectorAll('dt')]
    for (const dt of terms) {
      const dd = dt.nextElementSibling
      out[dt.textContent.trim()] = dd ? dd.textContent.trim() : null
    }
    return out
  })
}

await page.goto(`${BASE_URL}/about`)
const rows = await readVersions()

// web's version is compiled in by Vite's define, so this is also the check that the define
// survived the build rather than leaving the literal `__APP_VERSION__` in the bundle.
check(`web reports its own version (${rows.web})`, /^v\d/.test(rows.web ?? ''))
check(
  'web version carries a commit',
  /^v[\w.]+ \([0-9a-f]{7}\)$/.test(rows.web ?? '') || /unknown/.test(rows.web ?? ''),
)
check(`core comes from the server (${rows.core})`, rows.core === 'v4.0 (abc1234)')
check(`a reachable executor shows its version (${rows['executor-1']})`, rows['executor-1'] === 'v4.0 (abc1234)')
check(
  `an unreachable executor says so rather than vanishing (${rows['executor-2']})`,
  (rows['executor-2'] ?? '').includes('not responding'),
)
// No bearer token on the wire. The About page is reachable signed out and the endpoint is
// permitAll in SecurityConf precisely so a reporter who cannot log in can still read it; sending a
// stale token from an unrelated session would be worse than sending none.
check(
  `called with no Authorization header (${versionsCalls.length} call(s))`,
  versionsCalls.length > 0 && versionsCalls.every((h) => h === null),
)
await shot('01-versions')
const authHeaders = await page.evaluate(async () => {
  const res = await fetch('/v2/unauth/versions')
  return res.status
})
check(`fetching versions with no session works (HTTP ${authHeaders})`, authHeaders === 200)

// --- core unreachable: web's own line must survive ------------------------------------------------
// The two halves fail independently. Losing the server half must not take away the one version the
// page knows for certain.
versionsStatus = 500
await page.goto(`${BASE_URL}/about?again=1`)
const degraded = await readVersions()
check(`web still reported when the server 500s (${degraded.web})`, /^v\d/.test(degraded.web ?? ''))
check('no core row is invented', degraded.core === undefined)
check(
  'and the page says why',
  (await page.getByText('Could not reach the server').count()) > 0,
)
await shot('02-server-unreachable')

await browser.close()
process.exit(check.summary() ? 0 : 1)
