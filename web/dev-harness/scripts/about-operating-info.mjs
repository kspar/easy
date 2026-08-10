// EZ-1709: the operating-info panel on the About page — admin-only, and the request must not even
// be made by anyone else.
//
//   cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
//   cd web/dev-harness && node scripts/about-operating-info.mjs
import { launch, checker, fakeApi, waitUntil, BASE_URL } from '../harness.mjs'

const check = checker()

const INFO = {
  jvm: {
    started_at: '2026-08-07T09:12:00Z',
    uptime_sec: 273600, // 3d 4h
    heap_used_mb: 412,
    heap_max_mb: 2048,
    threads: 74,
    java_version: '25.0.1',
  },
  db_pool: { active: 2, idle: 8, waiting: 0, max: 90 },
  schema: {
    changeset: '2026-08-04-add-message',
    filename: 'db/changesets/v4.xml',
    applied_at: '2026-08-04T10:00:00Z',
    total_changesets: 142,
  },
  grading: [
    { executor: 'dev-executor', queued: 0, running: 1, reachable: true },
    // Down, and idle-looking if you only count its queue — the case this panel used to report as
    // "0 queued, 0 running", i.e. indistinguishable from healthy.
    { executor: 'sleepy-executor', queued: 0, running: 0, reachable: false },
  ],
  disk: { free_gb: 29, total_gb: 38 },
}

/**
 * Load /about as a given acting role and report what the operating panel shows, plus whether the
 * endpoint was called at all.
 *
 * launch() pins activeRole in an init script that re-runs on every navigation, so a second init
 * script is the only thing that actually changes it — the same trap documented in nav-idp-admin.mjs.
 */
async function aboutAs(activeRole) {
  const { browser, page, shot } = await launch({ shotPrefix: 'about-op-' })
  await page.addInitScript((r) => localStorage.setItem('activeRole', r), activeRole)

  const calls = []
  await fakeApi(
    page,
    [
      ['/account/checkin', () => ({})],
      ['/statistics/common', () => ({ in_auto_assessing: 0, total_submissions: 0, total_users: 0 })],
      ['/unauth/versions', () => ({ core: { version: '4.0', commit: 'abc1234', built_at: null }, executors: [] })],
      [
        '/admin/operating-info',
        ({ route }) => {
          calls.push(route.request().url())
          return INFO
        },
      ],
    ],
    { log: false },
  )

  await page.goto(`${BASE_URL}/about`)
  await page.locator('dl').first().waitFor()
  // The panel arrives with its own request, so give it a moment before concluding it is absent.
  await waitUntil(async () => (await page.locator('dl').count()) > 1, { timeout: 3000 })

  const text = (await page.locator('#root').innerText()).replace(/\s+/g, ' ')
  const result = { text, calls: calls.length, role: await page.evaluate(() => localStorage.getItem('activeRole')) }
  if (activeRole === 'admin') await shot('01-admin')
  await browser.close()
  return result
}

// --- as an admin ----------------------------------------------------------------------------------
const asAdmin = await aboutAs('admin')
check(`admin: acting role really is admin (${asAdmin.role})`, asAdmin.role === 'admin')
check('admin: the panel is on the page', asAdmin.text.includes('Operating info'))
check(`admin: uptime is humanised (${/uptime [^ ]+ [^ ]+/.exec(asAdmin.text)?.[0]})`, asAdmin.text.includes('3d 4h'))
check('admin: heap is shown against its maximum', asAdmin.text.includes('412 MB / 2048 MB'))
check('admin: db pool is spelled out', asAdmin.text.includes('2 active, 8 idle, 0 waiting'))
// The one thing actuator could not tell us, and the reason a half-applied deploy is diagnosable.
check('admin: the schema changeset is named', asAdmin.text.includes('2026-08-04-add-message'))
// The bare count was unlabelled and unguessable — "(142)" says nothing about what 142 is.
check('admin: the changeset count says what it counts', asAdmin.text.includes('142 changesets'))
check('admin: grading queue depth per executor', asAdmin.text.includes('0 queued, 1 running'))
// An executor that is down must not read as an idle healthy one.
check(
  'admin: a down executor says so instead of looking idle',
  asAdmin.text.includes('sleepy-executor') && /sleepy-executor[^0-9]*not responding/.test(asAdmin.text),
)
check('admin: disk is shown', asAdmin.text.includes('29 GB / 38 GB'))
check(`admin: the endpoint was called (${asAdmin.calls})`, asAdmin.calls === 1)

// --- as a teacher ---------------------------------------------------------------------------------
// Hiding the panel is not the security boundary — core's @Secured is — but a request that can only
// 403 should not be made at all.
const asTeacher = await aboutAs('teacher')
check('teacher: no panel', !asTeacher.text.includes('Operating info'))
check(`teacher: the endpoint is never called (${asTeacher.calls})`, asTeacher.calls === 0)
check('teacher: the rest of the About page is intact', asTeacher.text.includes('Lahendus'))
check('teacher: versions are still shown to them', asTeacher.text.includes('core'))

process.exit(check.summary() ? 0 : 1)
