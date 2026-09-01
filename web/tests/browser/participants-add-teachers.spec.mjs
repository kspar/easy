/**
 * Adding teachers to a course, and — the part this spec exists for — what happens when core says no.
 *
 * A teacher reported (EZ-1830) that pasting a list of addresses and pressing Add did nothing
 * visible when one of them had no account: the dialog closed on success and simply sat there on
 * failure, while core answered 400 eight times in a row. `handleAddTeachers` passed `onSuccess`
 * and no `onError`, so the typed error core had gone to the trouble of sending was thrown away by
 * the one call site that could have shown it.
 *
 * Two things had to change together, and neither is provable without the other:
 *
 *   core   looks every address up before reporting any, and names all the unresolved ones in
 *          `attrs.emails` — it used to throw on the first one it tripped over, so a paste of
 *          thirty lines with three bad addresses took three round trips to diagnose
 *   web     renders that message in the still-open dialog, with the typed list intact
 *
 * So the failing case here stubs the response core now sends, and asserts the dialog names *both*
 * unresolved addresses. A version of the page that swallows the error, or one that only reads the
 * older single-address `attrs.email`, fails it.
 *
 *   cd web && npx playwright test participants-add-teachers
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '81'

const participants = {
  students: [],
  teachers: [
    {
      id: 't1',
      email: 'tiiu@example.com',
      given_name: 'Tiiu',
      family_name: 'Tamm',
      created_at: '2026-07-01T09:00:00.000Z',
    },
  ],
  students_moodle_pending: [],
  moodle_linked: false,
}

/** Set per-request: what the next POST /teachers should answer. */
let reject = null

test('participants-add-teachers', async ({ launch, check }) => {
  const { page, shot, close } = await launch({
    role: 'teacher,admin',
    shotPrefix: 'participants-add-teachers-',
  })

  const posts = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/basic`, () => ({
      title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
      moodle_course_url: null,
    })],
    [new RegExp(`/courses/${COURSE}/groups(\\?|$)`), () => ({ groups: [] })],
    [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => participants],
    [new RegExp(`/courses/${COURSE}/teachers(\\?|$)`), ({ method, body, route }) => {
      if (method !== 'POST') return { accesses_added: 0 }
      posts.push(body)
      if (!reject) return { accesses_added: body.teachers.length }
      // Fulfilled by hand rather than returned: this is core's error envelope, which the contract
      // checker knows nothing about because it only ever describes success shapes.
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'fake-error-id',
          code: 'ACCOUNT_EMAIL_NOT_FOUND',
          attrs: { emails: reject },
          log_msg: `No account with email(s) ${reject}`,
        }),
      })
    }],
    [new RegExp(`/courses/${COURSE}/invite(\\?|$)`), ({ route }) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '' })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
    [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
  ], { log: false })

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await page.getByRole('tab', { name: /Teachers/ }).click()

  const dialog = page.getByRole('dialog')
  const box = dialog.getByPlaceholder('Enter email addresses, one per line')
  const addButton = () => dialog.getByRole('button', { name: 'Add', exact: true })

  // --- the reported case: two addresses with no account ------------------------------------------
  reject = 'kadri@example.com, urmas@example.com'

  await page.getByRole('button', { name: 'Add teachers' }).click()
  await box.fill('kadri@example.com\nurmas@example.com\nmari@example.com')
  await addButton().click()

  check(
    'the whole list goes to core in one request',
    await waitUntil(() => posts.length === 1),
    `${posts.length} request(s)`,
  )
  check(
    'carrying every address, in the order they were typed',
    JSON.stringify(posts[0]) ===
      JSON.stringify({
        teachers: [
          { email: 'kadri@example.com' },
          { email: 'urmas@example.com' },
          { email: 'mari@example.com' },
        ],
      }),
    JSON.stringify(posts[0] ?? null),
  )

  // Scoped to the error line rather than the whole dialog: the textarea's *typed* value is not part
  // of the dialog's innerText, but relying on that to keep the addresses out of the haystack would
  // make "mari is not blamed" below depend on a DOM detail instead of on the message.
  const errorLine = dialog.getByText(/No Lahendus user/)
  const message = async () => (await errorLine.innerText()).replace(/\s+/g, ' ')

  check(
    'the rejection is shown, rather than nothing at all — the whole of EZ-1830',
    await waitUntil(async () => (await errorLine.count()) === 1),
    await dialog.innerText(),
  )
  check(
    'and it names the first unresolved address',
    (await message()).includes('kadri@example.com'),
    await message(),
  )
  check(
    'and the second one too, so a long paste needs one round trip and not one per bad address',
    (await message()).includes('urmas@example.com'),
    await message(),
  )
  check(
    'while the address that was fine is not blamed for the failure',
    !(await message()).includes('mari@example.com'),
    await message(),
  )
  check(
    'the dialog stays open, because the list it is complaining about is in it',
    await dialog.isVisible(),
  )
  check(
    'with what was typed still there to be corrected',
    (await box.inputValue()).includes('mari@example.com'),
    await box.inputValue(),
  )
  await shot('01-unresolved-addresses')

  // --- correcting it in place --------------------------------------------------------------------
  // The point of keeping the dialog open. Nothing is retyped: the two bad lines are replaced and
  // the same button is pressed again.
  reject = null
  await box.fill('mari@example.com')
  await addButton().click()

  check(
    'a corrected list is accepted',
    await waitUntil(() => posts.length === 2),
    `${posts.length} request(s)`,
  )
  check(
    'the dialog closes on success',
    await waitUntil(async () => !(await dialog.isVisible())),
  )
  await shot('02-added')

  // --- what people actually paste ----------------------------------------------------------------
  // The placeholder asks for one per line and the box is fed by a clipboard: a spreadsheet column
  // arrives comma-separated and a mail client's recipient list semicolon-separated. Sent whole,
  // each is one unresolvable address, and the error would name a string nobody typed.
  await page.getByRole('button', { name: 'Add teachers' }).click()
  await box.fill('anu@example.com, peeter@example.com; tiit@example.com\nanu@example.com')
  await addButton().click()

  await waitUntil(() => posts.length === 3)
  check(
    'commas and semicolons separate addresses, and a repeat is sent once',
    JSON.stringify(posts[2]) ===
      JSON.stringify({
        teachers: [
          { email: 'anu@example.com' },
          { email: 'peeter@example.com' },
          { email: 'tiit@example.com' },
        ],
      }),
    JSON.stringify(posts[2] ?? null),
  )

  // The recipient list an Outlook or Gmail To: field hands over. Splitting on whitespace as well
  // would make six tokens of this and tell the teacher that `Tamm` and `Mets` have no account —
  // a worse version of the message EZ-1830 is about, so it is pinned rather than left to taste.
  await page.getByRole('button', { name: 'Add teachers' }).click()
  await box.fill('Tiiu Tamm <tiiu@example.com>; Mari Mets <mari@example.com>')
  await addButton().click()

  await waitUntil(() => posts.length === 4)
  check(
    'a display name is not mistaken for an address',
    JSON.stringify(posts[3]) ===
      JSON.stringify({
        teachers: [{ email: 'tiiu@example.com' }, { email: 'mari@example.com' }],
      }),
    JSON.stringify(posts[3] ?? null),
  )

  await close()
})
