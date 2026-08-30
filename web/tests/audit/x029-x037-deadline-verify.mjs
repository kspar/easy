/**
 * Verification driver for X-029 and X-037, the two deadline findings.
 *
 * X-029: a passed deadline was shown as a bare date. The exercise page said nothing about it at
 * all, and the exercise *list* said it only in red — meaning carried by colour alone, which is no
 * meaning at all for a reader who does not see the red. Both now name the state.
 *
 * The scope is deliberately narrow: the page says the deadline has passed, and stops. It does not
 * claim anything about whether a late submission counts, because nothing in the API supports such a
 * claim — soft and hard deadlines carry no penalty semantics in core, only `is_open`.
 *
 * X-037: `canSave` compared neither deadline against the other, so a closing time *before* the
 * deadline saved without a word — submissions refused before the deadline the student was given.
 *
 * The interesting assertions are the negative ones. "Tähtaeg" is a prefix of "Tähtaeg möödas", so a
 * check that the future case says "Tähtaeg" passes just as happily on the past one; each case has
 * to assert the other state's wording is absent.
 *
 *   cd web && npx vite --config vite.stub.config.ts --port 5299 --strictPort &
 *   HARNESS_PORT=5299 node tests/audit/x029-x037-deadline-verify.mjs
 */
import { withBrowser, fakeApi, shoot, VIEWPORTS, BASE_URL, waitUntil } from './audit.mjs'
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  baseHandlers,
} from './fixtures.mjs'

let failures = 0
const check = (ok, label) => {
  console.log(`  ${ok ? '✓' : '✗ FAIL'} ${label}`)
  if (!ok) failures++
}

const iso = (daysFromNow) =>
  new Date(Date.now() + daysFromNow * 24 * 3600 * 1000).toISOString()

const PASSED = iso(-21)
const FUTURE = iso(21)

/** Open the student's exercise page with a given deadline and open/closed state. */
async function openStudentExercise(launch, { deadline, isOpen }) {
  const { page } = await launch({ role: 'student', language: 'et', viewport: VIEWPORTS.laptop })
  await fakeApi(
    page,
    [
      ...baseHandlers(),
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [
        new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`),
        () => ({ exercises: [{ ...studentExercise(), deadline, is_open: isOpen }] }),
      ],
      [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions: [] })],
      [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [] })],
      [new RegExp(`/exercises/${CE_ID}/inline-comments`), () => ({ comments: [] })],
      [
        new RegExp(`/exercises/${CE_ID}(\\?|$)`),
        () => ({ ...exerciseDetails(), deadline, is_open: isOpen }),
      ],
    ],
    { log: false },
  )
  return page
}

const bodyText = (page) => page.evaluate(() => document.body.innerText)

await withBrowser(async ({ launch }) => {
  // ─── X-029 on the exercise page: past deadline, still open ──────────────────────────────────────
  {
    const page = await openStudentExercise(launch, { deadline: PASSED, isOpen: true })
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(400)

    const text = await bodyText(page)
    check(/Tähtaeg möödas/.test(text), 'the page says the deadline has passed')
    check(
      (await page.getByRole('button', { name: /Esita ja kontrolli/i }).count()) > 0,
      'and submitting is still offered, because the exercise is still open',
    )
    // The finding's scope, held: no invented promise about lateness either way.
    check(
      !/hilinen/i.test(text),
      'and says nothing about lateness, which the API cannot support',
    )
    await shoot(page, 'x029-page-deadline-passed')
    await page.close()
  }

  // ─── X-029: a future deadline is untouched ──────────────────────────────────────────────────────
  {
    const page = await openStudentExercise(launch, { deadline: FUTURE, isOpen: true })
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.locator('.cm-content').count()) > 0, { timeout: 15000 })
    await page.waitForTimeout(400)

    const text = await bodyText(page)
    // "Tähtaeg" alone would also match "Tähtaeg möödas", so the absence is the real assertion.
    check(/Tähtaeg:/.test(text), 'a future deadline still reads as a plain deadline')
    check(!/möödas/.test(text), 'and is not marked as passed')
    await page.close()
  }

  // ─── X-029 in the exercise list: the state is in words, not only in red ─────────────────────────
  {
    const readRow = async (deadline) => {
      const page = await openStudentExercise(launch, { deadline, isOpen: true })
      await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises`)
      await waitUntil(async () => (await page.getByRole('listitem').count()) > 0, { timeout: 15000 })
      await page.waitForTimeout(300)
      const row = await page.evaluate(() => {
        const li = document.querySelector('main li') ?? document.querySelector('li')
        const secondary = li?.querySelector('.MuiListItemText-secondary')
        return {
          text: secondary?.textContent ?? '',
          colour: secondary ? getComputedStyle(secondary).color : '',
        }
      })
      return { page, row }
    }

    const past = await readRow(PASSED)
    check(/Tähtaeg möödas/.test(past.row.text), `the list row names the state in words (${JSON.stringify(past.row.text)})`)
    await shoot(past.page, 'x029-list-deadline-passed')
    await past.page.close()

    // The colour was the whole signal before, and it should still be there — but "not black" is
    // not a test: MUI's own text.secondary is rgba(0,0,0,0.6), so deleting the error branch would
    // pass it. The future row is the control, and the two have to differ.
    const future = await readRow(FUTURE)
    check(!/möödas/.test(future.row.text), 'the future row is not marked as passed')
    check(
      past.row.colour !== future.row.colour,
      `and the red is kept alongside the words — past ${past.row.colour} vs future ${future.row.colour}`,
    )
    await future.page.close()
  }

  // ─── X-037: a closing time before the deadline is refused ───────────────────────────────────────
  {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}(\\?|$)`), () => ({
          ...exerciseDetails(),
          effective_title: 'Kahe arvu summa',
          soft_deadline: iso(7),
          hard_deadline: iso(14),
          threshold: 50,
          student_visible_from: null,
          exception_students: [],
          exception_groups: [],
        })],
        [new RegExp(`/teacher/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [] })],
        [/\/participants/, () => ({ students: [], teachers: [], students_pending: [], students_moodle_pending: [] })],
        [/\/groups/, () => ({ groups: [] })],
        [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
      ],
      { log: false, contract: false },
    )
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })

    const settings = page.getByRole('button', { name: /Ülesande sätted/i }).first()
    if ((await settings.count()) === 0) {
      check(false, 'could not reach the settings dialog — the rest of X-037 was not exercised')
    } else {
      await settings.click()
      await page.waitForTimeout(700)

      const saveBtn = page.getByRole('button', { name: /^Salvesta/i }).last()
      check(await saveBtn.isEnabled(), 'a well-ordered pair saves')

      // Drag the closing time before the deadline by typing into its field.
      const closing = page.getByLabel(/Sulgemise aeg/i).first()
      await closing.click()
      await closing.press('ControlOrMeta+a')
      const before = new Date(Date.now() + 1 * 24 * 3600 * 1000)
      const dd = String(before.getDate()).padStart(2, '0')
      const mm = String(before.getMonth() + 1).padStart(2, '0')
      await closing.type(`${dd}${mm}${before.getFullYear()}0900`)
      await page.waitForTimeout(600)

      const text = await bodyText(page)
      check(/ei saa olla tähtajast varem/.test(text), 'a closing time before the deadline is named')
      check(!(await saveBtn.isEnabled()), 'and Save is gated on it')
      await shoot(page, 'x037-closing-before-deadline')

      // A date that cannot be parsed at all. Before the review this passed validation, and
      // `toISOString()` threw RangeError out of the click handler: the button did nothing and the
      // teacher was told nothing. Save must be closed, and clicking must not throw.
      await closing.click()
      await closing.press('ControlOrMeta+a')
      await closing.type('45132026'.slice(0, 8) + '0900')
      await page.waitForTimeout(500)
      const errors = []
      page.on('pageerror', (e) => errors.push(String(e)))
      check(!(await saveBtn.isEnabled()), 'an unparseable date closes Save rather than reaching toISOString')
      await page.waitForTimeout(300)
      check(errors.length === 0, `and nothing throws (${errors.length} page errors)`)
    }
    await page.close()
  }

  // ─── X-037 in an exception row, which is where a teacher is likeliest to invert the pair ───────
  {
    const { page } = await launch({ role: 'teacher', language: 'et', viewport: VIEWPORTS.laptop })
    await fakeApi(
      page,
      [
        ...baseHandlers(),
        [new RegExp(`/teacher/courses/${COURSE_ID}/exercises/${CE_ID}(\\?|$)`), () => ({
          ...exerciseDetails(),
          effective_title: 'Kahe arvu summa',
          soft_deadline: iso(7),
          hard_deadline: iso(14),
          threshold: 50,
          student_visible_from: null,
          // The extension a teacher grants: this student's deadline moves out to +30 days, but
          // their closing time was left at +2. Arrives already inverted, so no typing is needed.
          exception_students: [{
            student_id: 'mari',
            soft_deadline: { value: iso(30) },
            hard_deadline: { value: iso(2) },
            student_visible_from: null,
          }],
          exception_groups: [],
        })],
        [new RegExp(`/teacher/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [] })],
        [/\/participants/, () => ({
          students: [{ id: 'mari', given_name: 'Mari', family_name: 'Maasikas', email: 'm@example.org', groups: [], created_at: iso(-40) }],
          teachers: [], students_pending: [], students_moodle_pending: [],
        })],
        [/\/groups/, () => ({ groups: [] })],
        [/\/v2\//, () => ({ courses: [], exercises: [], count: 0 })],
      ],
      { log: false, contract: false },
    )
    await page.goto(`${BASE_URL}/courses/${COURSE_ID}/exercises/${CE_ID}`)
    await waitUntil(async () => (await page.getByText('Kahe arvu summa').count()) > 0, { timeout: 15000 })
    const settings = page.getByRole('button', { name: /Ülesande sätted/i }).first()
    if ((await settings.count()) === 0) {
      check(false, 'could not reach the settings dialog for the exception case')
    } else {
      await settings.click()
      await page.waitForTimeout(900)
      const saveBtn = page.getByRole('button', { name: /^Salvesta/i }).last()
      check(
        !(await saveBtn.isEnabled()),
        'an exception whose closing time precedes its own deadline gates Save too',
      )
      await shoot(page, 'x037-exception-out-of-order')
    }
    await page.close()
  }
})

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}`)
process.exit(failures === 0 ? 0 : 1)
