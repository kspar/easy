import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'
import { COURSE, participants, baseStubs, participantsStub } from '../support/participants-groups-fixtures.mjs'

/**
 * A course that is *currently* linked to Moodle: group membership is Moodle's, and the UI says so by
 * offering no way to change it.
 *
 * Five affordances, all gated on `!isMoodleLinked`, and the gate is the whole behaviour — so this
 * asserts their **absence** plus zero requests to the membership endpoint. Asserting absence is
 * usually weak, and here it is not: ungating any one of them would let a teacher edit groups that
 * the next Moodle sync overwrites, with no error and no trace of what happened.
 *
 * The fifth is **creating** a group, which was ungated until now and was the worst of them: the
 * sync deletes every group Moodle does not have, so the group lasted until the nightly run and took
 * its per-exercise deadline exceptions with it on the way out. Absence alone would be a poor fix —
 * a button that is simply gone reads as a missing feature — so the sentence that replaces it is
 * asserted too.
 */
test('participants-groups-moodle-locked', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'participants-groups-locked-' })

  const membershipCalls = []
  await fakeApi(page, [...baseStubs(membershipCalls), ...participantsStub({ ...participants, moodle_linked: true })], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await page.getByRole('tab', { name: /Students/ }).waitFor()
  await waitUntil(async () => (await page.locator('tbody tr').count()) === 4)

  check(
    'no per-row add-to-group chip on a linked course',
    (await page.getByRole('button', { name: /add to group/i }).count()) === 0,
  )

  // Select every student, which is what reveals the toolbar's group buttons on an unlinked course.
  const boxes = page.locator('tbody tr input[type="checkbox"]')
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check()

  check(
    'and no add-to-group in the selection toolbar either',
    (await page.getByRole('button', { name: /add to group/i }).count()) === 0,
  )
  check(
    'nor remove-from-group, though a selected student is in one',
    (await page.getByRole('button', { name: /remove from group/i }).count()) === 0,
  )
  check(
    'the group chips are not deletable — no way to pull a student out one at a time',
    // MUI renders a Chip's onDelete as a nested button; without onDelete there is none.
    (await page.locator('tbody tr .MuiChip-deleteIcon').count()) === 0,
  )
  check(
    'and nothing has been sent to the membership endpoint',
    membershipCalls.length === 0,
    JSON.stringify(membershipCalls),
  )
  await shot('01-locked')

  // The Groups tab. Uncheck first: the students' selection toolbar is not what gates this, and a
  // selection left behind would make the assertion pass for the wrong reason.
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).uncheck()
  await page.getByRole('tab', { name: /^Groups/ }).click()
  await page.locator('tbody tr').first().waitFor()

  check(
    'no create-group button on a linked course',
    (await page.getByRole('button', { name: /create group/i }).count()) === 0,
  )
  check(
    'and the tab says where the groups come from instead',
    await waitUntil(async () => /Groups come from Moodle/i.test(await page.locator('main').innerText())),
    (await page.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 200),
  )
  await shot('02-locked-groups-tab')
  await close()
})
