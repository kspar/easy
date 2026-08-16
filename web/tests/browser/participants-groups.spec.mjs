/**
 * Groups on `ParticipantsPage` — the last uncovered third of the biggest file in the repo.
 *
 * Three behaviours here are invisible when wrong, and all three are about *counting or addressing
 * the right people*:
 *
 * 1. **A group's student count is derived on the client** from the participants list, and it counts
 *    Moodle-pending students as well as active ones. A count that quietly dropped the pending half
 *    would still look like a plausible number next to a plausible group.
 * 2. **Deleting several groups issues one DELETE per group**, fanned out with `Promise.all`. A
 *    shared id, or one request short, deletes the wrong thing and the list still refreshes to
 *    something believable.
 * 3. **The confirmation before deleting a group with people in it** names the students who will be
 *    pulled out of it, and that is the only warning before the fact.
 *
 * **Not covered here:** adding and removing students *from* a group, which partitions the selection
 * into `active_students` (by id) and `moodle_pending_students` (by username). The equivalent
 * partition on the way out of a *course* is pinned in `participants-roster.spec.mjs`; this one is
 * a separate code path and is still owed.
 *
 *   cd web && npx playwright test participants-groups
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '88'

const GROUPS = [
  { id: 'g1', name: 'Rühm A' },
  { id: 'g2', name: 'Rühm B' },
  { id: 'g3', name: 'Empty group' },
]

const active = (id, given, family, groups = []) => ({
  id,
  email: `${id}@example.com`,
  given_name: given,
  family_name: family,
  created_at: '2026-08-01T09:00:00.000Z',
  moodle_username: null,
  groups,
})

const pending = (username, groups = []) => ({
  moodle_username: username,
  email: `${username}@moodle.example.com`,
  invite_id: `inv-${username}`,
  groups,
})

const participants = {
  students: [
    active('s1', 'Mari', 'Maasikas', [GROUPS[0]]),
    active('s2', 'Jaan', 'Tamm', [GROUPS[1]]),
    active('s3', 'Peeter', 'Kask'),
  ],
  teachers: [],
  // In Rühm A, so that group holds one active student and one pending one. A count that only
  // counted active students would say 1 where the truth is 2, and look entirely reasonable.
  students_moodle_pending: [pending('kati', [GROUPS[0]])],
  moodle_linked: true,
}

test('participants-groups', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'participants-groups-' })

  const deletedGroups = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/basic`, () => ({
      title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
    })],
    // Students in/out of a group, which carries a body; and deleting a group, which does not.
    [/\/courses\/\d+\/groups\/[^/]+\/students$/, ({ method }) =>
      // `deleted_count` on the DELETE, per RemoveStudentsFromCourseGroupController.Resp.
      (method === 'DELETE' ? { deleted_count: 0 } : {})],
    [/\/courses\/\d+\/groups\/[^/]+$/, ({ method, url }) => {
      if (method === 'DELETE') deletedGroups.push(new URL(url).pathname.split('/').pop())
      return {}
    }],
    [new RegExp(`/courses/${COURSE}/groups(\\?|$)`), () => ({ groups: GROUPS })],
    [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => participants],
    [new RegExp(`/courses/${COURSE}/invite(\\?|$)`), ({ route }) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '' })],
    [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await page.getByRole('tab', { name: /Students/ }).waitFor()
  await page.getByRole('tab', { name: /^Groups/ }).click()

  const rows = async () =>
    (await page.locator('tbody tr').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim())

  check(
    'every group is listed',
    await waitUntil(async () => (await rows()).length === GROUPS.length),
    JSON.stringify(await rows()),
  )

  const rowFor = (name) => page.locator('tbody tr').filter({ hasText: name })
  check(
    'a group counts its Moodle-pending students as well as its active ones',
    // Rühm A holds Mari (active) and kati (pending). Counting only the active half gives 1, which
    // is exactly as believable as 2 unless something checks.
    await waitUntil(async () => /\b2\b/.test(await rowFor('Rühm A').innerText())),
    (await rowFor('Rühm A').innerText()).replace(/\s+/g, ' '),
  )
  check(
    'a group with one student says one',
    /\b1\b/.test(await rowFor('Rühm B').innerText()),
    (await rowFor('Rühm B').innerText()).replace(/\s+/g, ' '),
  )
  check(
    'and an empty group says zero rather than nothing',
    /\b0\b/.test(await rowFor('Empty group').innerText()),
    (await rowFor('Empty group').innerText()).replace(/\s+/g, ' '),
  )
  await shot('01-groups')

  // --- deleting a group that still has people in it ------------------------------------------------
  // The confirmation is the only warning before students are pulled out of a group, so it has to
  // say *which* students rather than asking a generic "are you sure".
  await rowFor('Rühm A').getByRole('button').last().click()
  const menuDelete = page.getByRole('menuitem', { name: /delete/i })
  if (await menuDelete.count()) await menuDelete.click()

  check(
    'deleting a group with students warns that they will be removed',
    await waitUntil(async () => (await page.getByText(/will be removed from the group/i).count()) > 0),
  )
  check(
    'and names them, so the warning is checkable rather than generic',
    (await page.getByRole('dialog').innerText()).includes('Mari'),
    (await page.getByRole('dialog').innerText()).replace(/\s+/g, ' ').slice(0, 160),
  )
  check('and nothing has been deleted yet', deletedGroups.length === 0, JSON.stringify(deletedGroups))
  await shot('02-delete-warning')

  await page.getByRole('dialog').getByRole('button', { name: /delete/i }).last().click()
  check(
    'confirming deletes exactly that group',
    await waitUntil(() => deletedGroups.length === 1) && deletedGroups[0] === 'g1',
    JSON.stringify(deletedGroups),
  )

  // --- deleting several at once --------------------------------------------------------------------
  // `useDeleteGroups` fans out with Promise.all — one request per group. One request short, or two
  // requests carrying the same id, deletes the wrong thing and the refreshed list still looks fine.
  const before = deletedGroups.length
  const checkboxes = page.locator('tbody tr input[type="checkbox"]')
  const names = await rows()
  for (const [i, text] of names.entries()) {
    if (text.includes('Rühm B') || text.includes('Empty group')) await checkboxes.nth(i).check()
  }
  await page.getByRole('button', { name: /delete/i }).first().click()
  const bulkConfirm = page.getByRole('dialog').getByRole('button', { name: /delete/i }).last()
  await bulkConfirm.click()

  check(
    'deleting two groups sends two requests, not one',
    await waitUntil(() => deletedGroups.length === before + 2),
    JSON.stringify(deletedGroups),
  )
  check(
    'each addressed to its own group',
    new Set(deletedGroups.slice(before)).size === 2 &&
      deletedGroups.slice(before).every((id) => ['g2', 'g3'].includes(id)),
    JSON.stringify(deletedGroups.slice(before)),
  )
  await shot('03-bulk-deleted')

  await close()
})
