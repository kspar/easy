/**
 * The student roster on `ParticipantsPage`: who is listed, what the filters do, and — the part
 * worth the effort — what a bulk action actually sends.
 *
 * The roster mixes two kinds of row that look nearly identical on screen and are completely
 * different on the wire. An **active** student has an account and an id; a **Moodle-pending** one
 * has only a Moodle username and exists as a synthetic `moodle:<username>` row the page invents.
 * Every bulk action has to partition the selection back into those two groups, and every one of
 * them does it slightly differently:
 *
 *   remove from course   only the active ones, as `{ active_students: [{ id }] }` — pending
 *                        students are Moodle's to manage and core has no id to delete
 *   add to group         both, in two separate arrays, one keyed by id and one by username
 *
 * Send the synthetic `moodle:kati` id where core expects a real one and it is a 400 at best; send
 * the wrong half and a student silently stays where they were. Neither is visible on the page, and
 * the only place the difference exists is the request body — which is what this spec reads.
 *
 *   cd web && npx playwright test participants-roster
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '77'

const GROUPS = [
  { id: 'g1', name: 'Rühm A' },
  { id: 'g2', name: 'Rühm B' },
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
  teachers: [
    {
      id: 't1',
      email: 't1@example.com',
      given_name: 'Tiiu',
      family_name: 'Tamm',
      created_at: '2026-07-01T09:00:00.000Z',
    },
  ],
  students_moodle_pending: [pending('kati', [GROUPS[0]])],
  moodle_linked: true,
}

test('participants-roster', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'participants-roster-' })

  const deletes = []
  const groupAdds = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/basic`, () => ({
      title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
      moodle_course_url: null,
    })],
    [`/courses/${COURSE}/groups/`, ({ method, body, url }) => {
      if (method === 'POST') groupAdds.push({ body, path: new URL(url).pathname })
      return {}
    }],
    [new RegExp(`/courses/${COURSE}/groups(\\?|$)`), () => ({ groups: GROUPS })],
    [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => participants],
    [new RegExp(`/courses/${COURSE}/students(\\?|$)`), ({ method, body }) => {
      if (method === 'DELETE') deletes.push(body)
      return { removed_active_count: 1 }
    }],
    // A course with no invite link yet. Core's ReadCourseInviteDetails.Resp has no nullable
    // fields, so "no invite" cannot be an object full of nulls — the app reads `r ?? null`, i.e.
    // an empty body. Answering with nulls would have been a response core cannot produce, and the
    // contract check treats a null in a non-nullable field as a failure rather than a warning.
    [new RegExp(`/courses/${COURSE}/invite(\\?|$)`), ({ route }) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '' })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
    // AppLayout fires this during boot, before activeRole settles. Unstubbed it falls through to
    // the catch-all `{}` and react-query logs "Query data cannot be undefined" on every run — and
    // an unexplained console error is where a real one hides.
    [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await page.getByRole('tab', { name: /Students/ }).waitFor()

  const rows = async () => (await page.locator('tbody tr').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim())

  check(
    'the roster lists active students and Moodle-pending ones together',
    await waitUntil(async () => (await rows()).length === 4),
    JSON.stringify(await rows()),
  )
  check(
    'an active student is shown by name',
    (await rows()).some((r) => r.includes('Mari Maasikas')),
    JSON.stringify(await rows()),
  )
  check(
    'a pending one is shown by its Moodle username, since there is no name yet',
    (await rows()).some((r) => r.includes('kati')),
    JSON.stringify(await rows()),
  )
  check('and the tab counts them all', (await page.getByRole('tab', { name: 'Students (4)' }).count()) === 1)
  await shot('01-roster')

  // --- filters ---------------------------------------------------------------------------------------
  const chip = (label) => page.locator('[class*=MuiChip-root]').filter({ hasText: label }).first()

  await chip('Status').click()
  await page.getByRole('menuitem', { name: 'Pending' }).click()
  check(
    'filtering to pending leaves only the Moodle rows',
    await waitUntil(async () => (await rows()).length === 1 && (await rows())[0].includes('kati')),
    JSON.stringify(await rows()),
  )

  await chip('Pending').click()
  await page.getByRole('menuitem', { name: 'Active' }).click()
  check(
    'and filtering to active leaves only the real accounts',
    await waitUntil(async () => (await rows()).length === 3),
    JSON.stringify(await rows()),
  )
  check('with no pending row among them', !(await rows()).some((r) => r.includes('kati')))

  // Back to everything, then narrow by group instead — the two filters are independent and the
  // group one is applied client-side rather than by refetching.
  await chip('Active').click()
  await page.getByRole('menuitem', { name: /^all/i }).click()
  await waitUntil(async () => (await rows()).length === 4)

  await chip('Groups').click()
  await page.getByRole('menuitem', { name: GROUPS[0].name }).click()
  check(
    'the group filter keeps only that group, across both kinds of row',
    // Mari is in Rühm A and so is pending kati — so this also proves the filter reads the pending
    // rows' groups, which arrive from a different field of a different shape.
    await waitUntil(async () => (await rows()).length === 2),
    JSON.stringify(await rows()),
  )
  await shot('02-filtered-by-group')

  await chip(GROUPS[0].name).click()
  await page.getByRole('menuitem', { name: /^all/i }).click()
  await waitUntil(async () => (await rows()).length === 4)

  // --- the partition, on the way out -------------------------------------------------------------
  // Select one active student and one pending one, then remove. Only the active id may be sent:
  // core has nothing to delete for a Moodle-pending student, and the synthetic `moodle:kati` id
  // is this page's invention.
  const checkboxes = page.locator('tbody tr input[type="checkbox"]')
  const rowText = await rows()
  const mariIdx = rowText.findIndex((r) => r.includes('Mari Maasikas'))
  const katiIdx = rowText.findIndex((r) => r.includes('kati'))
  await checkboxes.nth(mariIdx).check()
  await checkboxes.nth(katiIdx).check()

  page.on('dialog', (d) => d.accept())
  await page.getByRole('button', { name: 'Remove from course' }).first().click()
  // `click()` rather than `if (await count())`. `count()` does not auto-wait, so a dialog that has
  // not mounted yet makes the click silently vanish — and the spec then fails four lines later on
  // "0 request(s)", accusing the app of not sending what was never asked for.
  await page.getByRole('dialog').getByRole('button', { name: /remove/i }).last().click()

  check(
    'removing a mixed selection sends one request',
    await waitUntil(() => deletes.length === 1),
    `${deletes.length} request(s)`,
  )
  check(
    'carrying only the active student, wrapped as core expects',
    JSON.stringify(deletes[0]) === JSON.stringify({ active_students: [{ id: 's1' }] }),
    JSON.stringify(deletes[0] ?? null),
  )
  check(
    'and no synthetic moodle: id anywhere in it',
    !JSON.stringify(deletes[0] ?? {}).includes('moodle:'),
    JSON.stringify(deletes[0] ?? null),
  )
  await shot('03-removed')

  await close()
})
