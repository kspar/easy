/**
 * Marking the people you just shared something with, in the library share dialog.
 *
 * The list is ordered by access level and then by name, and everyone is added at PR — the lowest
 * level — so a new person lands alphabetically inside the largest block. With ten people already
 * there that is somewhere in the middle, looking exactly like everyone who was already there, and
 * the only sign the add worked is the row count changing. This fixture reproduces that: the person
 * added sorts sixth of eleven, neither first nor last.
 *
 * Covers the mark appearing, surviving a reload, and expiring.
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const ID = '4242'
const DIR = '77'
const NEW_EMAIL = 'mari.mets@ut.ee'

/** Nine people, spread across the alphabet so the newcomer cannot land at either end. */
const existing = [
  ['Anna', 'Aas'], ['Bruno', 'Beck'], ['Carl', 'Cruz'], ['Diana', 'Duus'], ['Enno', 'Eller'],
  ['Peeter', 'Pilv'], ['Riina', 'Roos'], ['Siim', 'Saar'], ['Tiit', 'Tamm'],
].map(([given, family], i) => ({
  username: `user${i}`,
  given_name: given,
  family_name: family,
  email: `${given.toLowerCase()}.${family.toLowerCase()}@ut.ee`,
  group_id: `g${i}`,
  access: 'PR',
}))

let directAccounts = [...existing]

const exercise = {
  dir_id: DIR,
  effective_access: 'PRAWM',
  created_at: '2026-01-01T10:00:00.000Z',
  is_public: false,
  is_anonymous_autoassess_enabled: false,
  owner_id: 'kspar',
  last_modified: '2026-07-30T12:00:00.000Z',
  last_modified_by_id: 'kspar',
  grader_type: 'AUTO',
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  title: 'Sum of two numbers',
  text_html: '<p>Read two numbers and print their sum.</p>',
  text_md: 'Read two numbers and print their sum.',
  // Empty string, not null: core has sent this non-nullable since changeset 020826-1, which
  // gave "no template" a single spelling. The contract check against doc/core/api-shapes.json
  // caught this fixture still describing a response core cannot produce.
  anonymous_autoassess_template: '',
  grading_script: 'x',
  container_image: 'pygrader',
  max_time_sec: 7,
  max_mem_mb: 30,
  assets: [],
  executors: [],
  on_courses: [],
  on_courses_no_access: 0,
}

test('library-share-recent', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'lib-share-' })

  const puts = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    ['/teacher/courses', () => ({ courses: [] })],
    [`/lib/dirs/${DIR}/parents`, () => ({ parents: [{ id: DIR, name: 'Algoritmid' }] })],
    [
      `/lib/dirs/${DIR}/access`,
      ({ method, body }) => {
        if (method === 'PUT') {
          puts.push(body)
          // What the server would do: the person now holds PR on this directory.
          const [given, family] = body.email.split('@')[0].split('.')
          directAccounts = [
            ...directAccounts,
            {
              username: 'mmets',
              given_name: given[0].toUpperCase() + given.slice(1),
              family_name: family[0].toUpperCase() + family.slice(1),
              email: body.email,
              group_id: 'g-new',
              access: 'PR',
            },
          ]
          return {}
        }
        return {
          direct_any: null,
          direct_accounts: directAccounts,
          direct_groups: [],
          inherited_any: null,
          inherited_accounts: [],
          inherited_groups: [],
        }
      },
    ],
    [`/exercises/${ID}`, () => exercise],
  ], { log: false })

  async function openShareDialog() {
    await page.goto(`${BASE_URL}/library/exercise/${ID}/sum-of-two-numbers`)
    await page.getByRole('tab', { name: 'Exercise' }).waitFor()
    await page.getByRole('button', { name: 'More options' }).click()
    await page.getByRole('menuitem', { name: 'Share' }).click()
    await page.getByRole('dialog').waitFor()
  }

  const dialog = () => page.getByRole('dialog')
  const rows = () => dialog().locator('.MuiListItem-root')
  const markedRows = () => dialog().locator('.MuiListItem-root').filter({ hasText: 'Just added' })

  await openShareDialog()
  check('the dialog lists everyone already shared with', (await rows().count()) === existing.length)
  check('and nobody is marked to begin with', (await markedRows().count()) === 0)

  // --- adding someone ------------------------------------------------------------------------------

  await dialog().getByPlaceholder(/email/i).fill(NEW_EMAIL)
  await dialog().getByRole('button', { name: 'Share' }).click()

  check('the share request was sent', await waitUntil(() => puts.length === 1), JSON.stringify(puts[0] ?? {}))
  check(
    'the new person is marked',
    await waitUntil(async () => (await markedRows().count()) === 1),
  )
  check('and only them', (await markedRows().count()) === 1)
  check(
    'the mark is on the right row',
    (await markedRows().first().innerText()).includes(NEW_EMAIL),
    await markedRows().first().innerText(),
  )

  // The whole reason a mark is needed: they are not at either end of the list.
  const allText = await rows().allInnerTexts()
  const position = allText.findIndex((r) => r.includes(NEW_EMAIL))
  check(
    'and they landed in the middle, where a mark is the only way to find them',
    position > 0 && position < allText.length - 1,
    `row ${position + 1} of ${allText.length}`,
  )
  await shot('01-just-added')

  // --- it survives a reload -------------------------------------------------------------------------

  const stored = await page.evaluate(() => localStorage.getItem('library.recentShares'))
  check('the mark is recorded in localStorage', !!stored && stored.includes(NEW_EMAIL), stored ?? '')

  await openShareDialog()
  check(
    'and is still there after a reload',
    await waitUntil(async () => (await markedRows().count()) === 1),
  )

  // --- and expires ----------------------------------------------------------------------------------

  // Eleven minutes ago, one past the window. Rewritten rather than waited for, obviously.
  await page.evaluate(() => {
    const store = JSON.parse(localStorage.getItem('library.recentShares') ?? '{}')
    for (const subjects of Object.values(store)) {
      for (const k of Object.keys(subjects)) subjects[k] = Date.now() - 11 * 60 * 1000
    }
    localStorage.setItem('library.recentShares', JSON.stringify(store))
  })

  await openShareDialog()
  check('an old mark has expired', (await markedRows().count()) === 0)
  check('but the person is still listed', (await rows().count()) === existing.length + 1)
  check(
    'and the expired entry is pruned from storage',
    await page.evaluate(() => !(localStorage.getItem('library.recentShares') ?? '').includes('mari.mets')),
  )
  await shot('02-expired')

  await close()
})
