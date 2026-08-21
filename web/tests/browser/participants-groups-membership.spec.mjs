import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'
import { COURSE, participants, baseStubs, participantsStub } from '../support/participants-groups-fixtures.mjs'

/**
 * Adding students to a group and removing them from one, on a course with no Moodle link.
 *
 * The assertion that matters is the **exact body**, not that a request happened. `active_students`
 * and `moodle_pending_students` are separate arrays of different shapes — ids in one, Moodle
 * usernames in the other — and the page addresses pending students by a synthetic `moodle:<username>`
 * row id of its own invention that core knows nothing about. A prefix left on would be stored as a
 * username matching no Moodle account; a prefix stripped from the wrong half would send `moodle:kati`
 * as an *account id*. Both produce a request core accepts.
 *
 * ### This spec used to test the mixed selection, and cannot any more
 *
 * It was written against a course that was `moodle_linked: false` **with pending invitations still in
 * it** — the only state in which the UI offers group editing (gated on `!isMoodleLinked`) while
 * pending students exist. That state was reachable solely because unlinking a course deleted nothing,
 * which was **EZ-1780**: those invitations also still worked, so unlinking did not stop Moodle-driven
 * enrolment. Unlink now deletes the pending rows, changeset `210826-5` clears the ones earlier
 * unlinks left behind, and the join refuses an invite whose course is no longer linked — so
 * `moodle_linked: false` with a populated `students_moodle_pending` is not a state the server can
 * produce.
 *
 * So the mixed-selection assertions are gone rather than rewritten. A browser test can always
 * construct an impossible payload with `fakeApi`; what it cannot do is make the result mean anything.
 * Pinning client behaviour in a state the server cannot reach is worse than not testing it, because
 * it reads as coverage.
 *
 * What remains is the reachable case, and the `moodle_pending_students: []` assertions are kept
 * deliberately: the client still partitions every selection and still sends both halves, it just
 * always finds the pending half empty now. Core defaults the field to `emptyList()`, so omitting it
 * would work too — but a client that omits a key on one path and sends it on another is a client
 * where the next reader cannot tell which shape core actually requires.
 */
test('participants-groups-membership', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'participants-groups-membership-' })

  const membershipCalls = []
  await fakeApi(
    page,
    [
      ...baseStubs(membershipCalls),
      // No Moodle link and no pending rows — the two now go together, which is the point of EZ-1780.
      ...participantsStub({ ...participants, moodle_linked: false, students_moodle_pending: [] }),
    ],
    { log: false },
  )

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await page.getByRole('tab', { name: /Students/ }).waitFor()

  const rows = async () =>
    (await page.locator('tbody tr').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim())
  await waitUntil(async () => (await rows()).length === 3)

  check(
    'an unlinked course lists its active students and nothing pending',
    (await rows()).length === 3 && !(await rows()).join(' ').includes('kati'),
    JSON.stringify(await rows()),
  )

  const boxes = page.locator('tbody tr input[type="checkbox"]')
  const pick = async (needle) => {
    const i = (await rows()).findIndex((r) => r.includes(needle))
    check(`the roster lists ${needle}`, i >= 0, JSON.stringify(await rows()))
    await boxes.nth(i).check()
  }

  // --- adding a multi-student selection ----------------------------------------------------------
  await pick('Peeter Kask')   // in no group
  await pick('Jaan Tamm')     // in Rühm B

  await page.getByRole('button', { name: /add to group/i }).first().click()
  await page.getByRole('menuitem', { name: 'Rühm B' }).click()

  check(
    'adding a selection sends one request, not one per student',
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
    'both halves present, ids wrapped as core expects, pending half empty',
    JSON.stringify(post?.body) === JSON.stringify({
      active_students: [{ id: 's3' }, { id: 's2' }],
      moodle_pending_students: [],
    }),
    JSON.stringify(post?.body ?? null),
  )
  check(
    'no synthetic moodle: row id leaks into the body',
    !JSON.stringify(post?.body ?? {}).includes('moodle:'),
    JSON.stringify(post?.body ?? null),
  )
  await shot('01-added')

  // --- removing a selection ---------------------------------------------------------------------
  // Mari is in Rühm A, so the remove menu offers it.
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).uncheck()
  await pick('Mari Maasikas')

  await page.getByRole('button', { name: /remove from group/i }).first().click()
  await page.getByRole('menuitem', { name: 'Rühm A' }).click()

  check(
    'removing sends one request',
    await waitUntil(() => membershipCalls.filter((c) => c.method === 'DELETE').length === 1),
    JSON.stringify(membershipCalls),
  )
  const del = membershipCalls.find((c) => c.method === 'DELETE')
  check(
    'the DELETE carries the same two arrays as the POST',
    JSON.stringify(del?.body) === JSON.stringify({
      active_students: [{ id: 's1' }],
      moodle_pending_students: [],
    }),
    JSON.stringify(del?.body ?? null),
  )
  check(
    'addressed to the group it was removed from',
    del?.groupId === 'g1',
    JSON.stringify(del ?? null),
  )
  await shot('02-removed')

  // --- a single-row chip removal ----------------------------------------------------------------
  // The row's own group chip has a delete icon, which is a different code path from the toolbar
  // above — `handleRemoveFromGroup` rather than `handleBulkRemoveFromGroup` — and it is the one a
  // teacher actually uses for a single student.
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).uncheck()
  const jaanRow = page.locator('tbody tr').filter({ hasText: 'Jaan Tamm' })
  await jaanRow.getByRole('button', { name: /Rühm B/ }).getByTestId('CancelIcon').click()

  check(
    'the chip delete sends its own request with just that student',
    await waitUntil(() => membershipCalls.filter((c) => c.method === 'DELETE').length === 2) &&
      JSON.stringify(membershipCalls.filter((c) => c.method === 'DELETE')[1].body) === JSON.stringify({
        active_students: [{ id: 's2' }],
        moodle_pending_students: [],
      }),
    JSON.stringify(membershipCalls.filter((c) => c.method === 'DELETE')[1]?.body ?? null),
  )
  await shot('03-chip-removed')

  await close()
})
