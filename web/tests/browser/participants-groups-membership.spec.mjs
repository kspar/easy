import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'
import { COURSE, participants, baseStubs, participantsStub } from '../support/participants-groups-fixtures.mjs'

/**
 * The partition itself, in the one state that can produce both halves: unlinked from Moodle, with
 * pending invitations left behind.
 *
 * The assertion that matters is the **exact body**, not that a request happened. The page addresses
 * pending students by a synthetic `moodle:<username>` row id of its own invention, and core has no
 * idea what that is — `MoodlePendingStudentReq.moodleUsername` is `@NotBlank`, so a prefix left on
 * would be stored as a username that matches no Moodle account, and a prefix stripped from the wrong
 * half would send `moodle:kati` as an *account id*. Both produce a request core accepts.
 *
 * Note the contrast with `participants-roster.spec.mjs`, where removing from a *course* drops the
 * pending half entirely. Same-looking selection, deliberately different body: core has nothing to
 * delete for a pending student's course access, but it does hold their group membership
 * (`StudentMoodlePendingCourseGroup`). Getting these two the same way round is the mistake this pair
 * of tests exists to catch.
 */
test('participants-groups-membership', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'participants-groups-membership-' })

  const membershipCalls = []
  await fakeApi(page, [...baseStubs(membershipCalls), ...participantsStub({ ...participants, moodle_linked: false })], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await page.getByRole('tab', { name: /Students/ }).waitFor()

  const rows = async () =>
    (await page.locator('tbody tr').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim())
  await waitUntil(async () => (await rows()).length === 4)

  const boxes = page.locator('tbody tr input[type="checkbox"]')
  const pick = async (needle) => {
    const i = (await rows()).findIndex((r) => r.includes(needle))
    check(`the roster still lists ${needle} after unlinking`, i >= 0, JSON.stringify(await rows()))
    await boxes.nth(i).check()
  }

  // --- adding a mixed selection ------------------------------------------------------------------
  await pick('Peeter Kask')  // active, in no group
  await pick('kati')         // Moodle-pending, left behind by the unlink

  await page.getByRole('button', { name: /add to group/i }).first().click()
  await page.getByRole('menuitem', { name: 'Rühm B' }).click()

  check(
    'adding a mixed selection sends one request, not one per kind',
    await waitUntil(() => membershipCalls.filter((c) => c.method === 'POST').length === 1),
    JSON.stringify(membershipCalls),
  )
  const post = membershipCalls.find((c) => c.method === 'POST')
  check(
    'addressed to the group that was picked',
    post?.groupId === 'g2',
    JSON.stringify(post ?? null),
  )
  check(
    'partitioned into both halves, each wrapped as core expects',
    JSON.stringify(post?.body) === JSON.stringify({
      active_students: [{ id: 's3' }],
      moodle_pending_students: [{ moodle_username: 'kati' }],
    }),
    JSON.stringify(post?.body ?? null),
  )
  check(
    'with the synthetic moodle: prefix stripped, and nowhere in the body',
    !JSON.stringify(post?.body ?? {}).includes('moodle:'),
    JSON.stringify(post?.body ?? null),
  )
  await shot('01-added')

  // --- removing a mixed selection ---------------------------------------------------------------
  // Mari and kati are both in Rühm A, so the remove menu offers it. Same partition, DELETE body.
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).uncheck()
  await pick('Mari Maasikas')
  await pick('kati')

  await page.getByRole('button', { name: /remove from group/i }).first().click()
  await page.getByRole('menuitem', { name: 'Rühm A' }).click()

  check(
    'removing a mixed selection also sends one request',
    await waitUntil(() => membershipCalls.filter((c) => c.method === 'DELETE').length === 1),
    JSON.stringify(membershipCalls),
  )
  const del = membershipCalls.find((c) => c.method === 'DELETE')
  check(
    'carrying both halves — unlike removal from a *course*, which drops the pending one',
    JSON.stringify(del?.body) === JSON.stringify({
      active_students: [{ id: 's1' }],
      moodle_pending_students: [{ moodle_username: 'kati' }],
    }),
    JSON.stringify(del?.body ?? null),
  )
  check(
    'addressed to the group it was removed from',
    del?.groupId === 'g1',
    JSON.stringify(del ?? null),
  )
  await shot('02-removed')

  // --- an all-active selection ------------------------------------------------------------------
  // The empty half must still be sent as `[]`. Core defaults it to emptyList(), so omitting it works
  // too — but a client that omits a key on one path and sends it on another is a client where the
  // next reader cannot tell which shape core actually requires.
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).uncheck()
  await pick('Jaan Tamm')
  await page.getByRole('button', { name: /add to group/i }).first().click()
  await page.getByRole('menuitem', { name: 'Rühm A' }).click()

  check(
    'an all-active selection still sends the pending half, as an empty array',
    await waitUntil(() => membershipCalls.filter((c) => c.method === 'POST').length === 2) &&
      JSON.stringify(membershipCalls.filter((c) => c.method === 'POST')[1].body) === JSON.stringify({
        active_students: [{ id: 's2' }],
        moodle_pending_students: [],
      }),
    JSON.stringify(membershipCalls.filter((c) => c.method === 'POST')[1]?.body ?? null),
  )
  await shot('03-all-active')

  await close()
})
