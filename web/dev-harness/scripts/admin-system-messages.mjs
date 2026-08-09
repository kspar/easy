// The admin page for system messages: listing with schedule state, creating, and the validation
// that stops a message nobody can see.
//
//   cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
//   cd web/dev-harness && node scripts/admin-system-messages.mjs
import { launch, checker, fakeApi, BASE_URL, waitUntil } from '../harness.mjs'

const check = checker()
const ADMIN_EP = '/management/notifications'

const hourFromNow = (h) => new Date(Date.now() + h * 3600_000).toISOString()

const EXISTING = [
  {
    id: '1',
    message: 'Maintenance tonight',
    severity: 'URGENT',
    link_url: 'https://example.org/n',
    link_label: 'Details',
    visible_from: null,
    visible_until: null,
    for_students: true,
    for_teachers: true,
    for_admins: true,
  },
  {
    id: '2',
    message: 'Teachers only: new grade export',
    severity: 'INFO',
    visible_from: hourFromNow(24), // not yet started
    visible_until: hourFromNow(48),
    for_students: false,
    for_teachers: true,
    for_admins: false,
  },
]

/** Open the admin page as an admin, with the given list and a recorder for writes. */
async function openPage(list) {
  const { browser, page, shot } = await launch({ shotPrefix: 'admin-msg-' })
  // launch() pins activeRole to 'teacher'; this init script is added after it, so it wins — and it
  // re-runs on every navigation, which setting localStorage after goto does not survive.
  await page.addInitScript(() => localStorage.setItem('activeRole', 'admin'))
  const posted = []
  await fakeApi(
    page,
    [
      [
        ADMIN_EP,
        ({ method, body }) => {
          if (method === 'POST') {
            posted.push(body)
            return {}
          }
          return { messages: list }
        },
      ],
      ['/management/common/notifications', () => ({ messages: [] })],
      ['/account/checkin', () => ({})],
      ['/courses', () => ({ courses: [] })],
    ],
    { log: false },
  )
  await page.goto(`${BASE_URL}/admin/messages`)
  await waitUntil(async () => (await page.locator('h5').count()) > 0)
  return { browser, page, shot, posted }
}

// --- the list tells you what is on screen right now -----------------------------------------------
{
  const { browser, page, shot } = await openPage(EXISTING)
  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ')

  check('both messages listed', body.includes('Maintenance tonight') && body.includes('grade export'))
  check('the live one is marked as showing now', body.includes('Showing now'))
  // The whole reason to open this page: a future message reads as scheduled rather than making you
  // compare two timestamps in your head.
  check('the future one is marked scheduled', body.includes('Scheduled'))
  check('a targeted message shows its audience', body.includes('Teacher'))
  check('the link is shown with its label', body.includes('Details'))
  await shot('01-list')
  await browser.close()
}

// --- creating one sends what was typed -------------------------------------------------------------
{
  const { browser, page, shot, posted } = await openPage([])
  check('empty state is stated, not blank', (await page.locator('body').innerText()).includes('No messages yet'))

  await page.getByRole('button', { name: 'New message' }).click()
  await waitUntil(async () => (await page.locator('[role=dialog]').count()) > 0)

  await page.locator('[role=dialog] textarea').first().fill('Planned outage at 21:00')
  await page.getByRole('button', { name: 'Save' }).click()
  await waitUntil(() => posted.length > 0)

  check(`create posted once (${posted.length})`, posted.length === 1)
  check(`message text sent (${posted[0]?.message})`, posted[0]?.message === 'Planned outage at 21:00')
  check(`severity defaults to INFO (${posted[0]?.severity})`, posted[0]?.severity === 'INFO')
  // Defaults matter: a new message with no audience ticked would be invisible to everyone, so the
  // form starts with all three on rather than none.
  check(
    'all three audiences default on',
    posted[0]?.for_students === true && posted[0]?.for_teachers === true && posted[0]?.for_admins === true,
  )
  check('no id is sent for a new message', !('id' in (posted[0] ?? {})))
  await shot('02-created')
  await browser.close()
}

// --- the form refuses what core would refuse --------------------------------------------------------
{
  const { browser, page, shot } = await openPage([])
  await page.getByRole('button', { name: 'New message' }).click()
  await waitUntil(async () => (await page.locator('[role=dialog]').count()) > 0)

  const save = page.getByRole('button', { name: 'Save' })
  check('save is disabled with an empty message', await save.isDisabled())

  await page.locator('[role=dialog] textarea').first().fill('Something')
  check('save enables once there is text', await save.isEnabled())

  // Half a link renders as a button with no text, or a promise the banner cannot keep.
  await page.getByLabel('Link URL').fill('https://example.org')
  check('half a link disables save', await save.isDisabled())
  await page.getByLabel('Link text').fill('Read more')
  check('a complete link re-enables it', await save.isEnabled())

  // Nobody selected means a message that will never be shown — worth stopping before it is stored.
  for (const role of ['Student', 'Teacher', 'Admin']) {
    await page.getByRole('checkbox', { name: role }).uncheck()
  }
  check('no audience disables save', await save.isDisabled())
  check(
    'and says why',
    (await page.locator('[role=dialog]').innerText()).includes('never be shown'),
  )
  await shot('03-validation')
  await browser.close()
}

process.exit(check.summary() ? 0 : 1)
