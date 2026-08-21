/**
 * The Moodle half of `ParticipantsPage` — 1823 lines with no coverage at all until now, and the
 * only surface in this app that reaches a real external system.
 *
 * The centrepiece is **EZ-1768**, a bug that is invisible by construction: the sync-status poll
 * stops mid-sync, and "no further updates" looks exactly like "nothing has changed yet". A teacher
 * sees a spinner that never resolves and reloads the page, which fixes it, which is why nobody ever
 * filed it.
 *
 * Timing is real here rather than faked. The poll is a 3-second interval, so this spec spends about
 * fifteen seconds watching request counts. That is the only way to tell a *running* poll from a
 * dead one — every DOM assertion looks identical in both cases, which is the whole problem.
 *
 *   cd web && npx playwright test participants-moodle
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '42'

// `ReadBasicCourseInfo.Resp` exactly: no `id` (the caller already knows it, and core does not send
// one), and the three fields it is easy to forget.
const COURSE_BASIC = {
  title: 'Programming 101',
  alias: null,
  archived: false,
  color: '#1976d2',
  course_code: null,
}

// Every field core's StudentsResp carries, including `moodle_username` — which
// web/src/api/types.ts does not declare, so the app cannot read it today (EZ-1772's half of the
// contract work). The fixture matches core rather than the TypeScript, because core is what the
// browser would actually receive.
const student = (id, given, family) => ({
  id,
  email: `${id}@example.com`,
  given_name: given,
  family_name: family,
  created_at: '2026-08-01T09:00:00.000Z',
  moodle_username: null,
  groups: [],
})

const participants = {
  students: [student('s1', 'Mari', 'Maasikas'), student('s2', 'Jaan', 'Tamm')],
  teachers: [
    {
      id: 't1',
      email: 't1@example.com',
      given_name: 'Tiiu',
      family_name: 'Tamm',
      created_at: '2026-07-01T09:00:00.000Z',
    },
  ],
  // Two students who were invited from Moodle and have not joined. They are here for the unlink
  // confirmation below, which has to say how many invitations it is about to destroy — unlinking
  // deletes them since EZ-1780, where it used to leave them live and still able to enrol.
  students_moodle_pending: [
    { moodle_username: 'kati', email: 'kati@moodle.example.com', invite_id: 'M-KATI', groups: [] },
    { moodle_username: 'juri', email: 'juri@moodle.example.com', invite_id: 'M-JURI', groups: [] },
  ],
  moodle_linked: true,
}

/** The five fields core's MoodlePropsResp carries; nothing invented. */
const moodleProps = (over = {}) => ({
  moodle_props: {
    moodle_short_name: 'PROG-2026',
    students_synced: true,
    sync_students_in_progress: false,
    grades_synced: true,
    sync_grades_in_progress: false,
    ...over,
  },
})

test('participants-moodle', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'participants-moodle-' })

  // Mutable server state, and a request counter per endpoint. The counter is the instrument: this
  // spec is about whether requests keep arriving, not about what they answer.
  let props = moodleProps()
  let moodleGets = 0
  const puts = []

  await fakeApi(page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
    [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => participants],
    [`/courses/${COURSE}/basic`, () => COURSE_BASIC],
    [new RegExp(`/courses/${COURSE}/moodle(\\?|$)`), ({ method, body }) => {
      if (method === 'PUT') {
        puts.push(body)
        return {}
      }
      moodleGets++
      return props
    }],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  // Unlinking is admin-only, and `launch()` pins activeRole to 'teacher' for every non-student
  // role. This init script is added after launch's, so it wins, and it re-runs on every navigation.
  await page.addInitScript(() => localStorage.setItem('activeRole', 'admin'))

  await page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  // Four: two joined and two still holding a Moodle invitation. The tab counts both, which is right —
  // a teacher asking "how many students" means the roster, not the subset that has logged in.
  await page.getByRole('tab', { name: 'Students (4)' }).waitFor()

  // The Moodle panel is a tab, and it only exists when the course is linked — so its presence is
  // the first thing worth asserting rather than something to click past.
  const moodleTab = page.getByRole('tab', { name: 'Moodle' })
  check('a linked course offers a Moodle tab', (await moodleTab.count()) === 1)
  await moodleTab.click()
  await page.getByText('PROG-2026').first().waitFor()

  check('and it shows the linked course short name', (await page.getByText('PROG-2026').count()) > 0)
  await shot('01-linked')

  // --- the poll only runs while a sync is running --------------------------------------------------
  // Establish the quiet baseline first. A poll that runs when nothing is syncing would make every
  // assertion below pass for the wrong reason.
  const quiet = moodleGets
  await page.waitForTimeout(4000)
  check(
    'no polling while nothing is syncing',
    moodleGets === quiet,
    `${moodleGets - quiet} request(s) in 4s`,
  )

  // --- a sync starts, and the page starts asking --------------------------------------------------
  props = moodleProps({ sync_students_in_progress: true })
  // Nudge react-query into seeing the new state the way a real sync would: the user presses Sync,
  // the mutation succeeds, and its onSuccess invalidates the moodle query.
  await page.getByRole('button', { name: /sync/i }).first().click()

  const started = await waitUntil(async () => {
    const before = moodleGets
    await page.waitForTimeout(3500)
    return moodleGets > before ? moodleGets - before : false
  }, { timeout: 15_000, interval: 0 })
  check('a running student sync makes the page poll', !!started, `${started} request(s) per 3.5s`)
  await shot('02-syncing')

  /**
   * EZ-1768, the whole reason this spec exists.
   *
   * The flags change — students finishes, grades starts — while *something* is still in progress.
   * The old effect kept the interval id in a ref, and its cleanup cleared the interval without
   * nulling the ref; the guard `!pollRef.current` then read false and no replacement timer was ever
   * started. The page went silent for the rest of the sync.
   *
   * Nothing on screen changes at that moment. The spinner keeps spinning. Only the request count
   * knows.
   */
  props = moodleProps({ sync_students_in_progress: false, sync_grades_in_progress: true })
  await waitUntil(() => moodleGets > 0, { timeout: 5000 })
  await page.waitForTimeout(3500) // let the app observe the new flags

  const afterHandover = moodleGets
  await page.waitForTimeout(7000)
  check(
    'the poll survives one sync finishing while another is still running',
    moodleGets > afterHandover,
    `${moodleGets - afterHandover} request(s) in 7s after the flags changed — 0 means EZ-1768 is back`,
  )
  await shot('03-handover')

  // --- and stops when everything is done ------------------------------------------------------------
  props = moodleProps()
  await page.waitForTimeout(4000)
  const settled = moodleGets
  await page.waitForTimeout(5000)
  check(
    'the poll stops once nothing is in progress',
    moodleGets === settled,
    `${moodleGets - settled} request(s) after both syncs finished`,
  )

  // --- unlinking -------------------------------------------------------------------------------------
  // `moodle_props: null` specifically, not an empty object and not a flag: null is what core reads
  // as "unlink". The request body is the only place that distinction is visible.
  const unlink = page.getByRole('button', { name: 'Unlink from Moodle' })
  check('an admin is offered the unlink control', (await unlink.count()) === 1)
  await unlink.click()

  check(
    'it confirms first, saying what stops',
    await waitUntil(async () => (await page.getByText(/syncing will stop/i).count()) > 0),
  )
  // The count, not just a warning. Unlinking deletes every outstanding Moodle invitation (EZ-1780 —
  // before that they survived and still enrolled people), and a destructive action that does not say
  // what it destroys is one a teacher cannot consent to. Two pending students in the fixture, so the
  // plural form has to interpolate rather than say "students who have not joined".
  check(
    'and names how many invitations it will drop',
    (await page.getByRole('dialog').getByText(/2 students who have not joined/i).count()) === 1,
    (await page.getByRole('dialog').innerText()).replace(/\s+/g, ' '),
  )
  check('and nothing is sent before confirming', puts.length === 0, JSON.stringify(puts))
  await shot('04-unlink-confirm')

  // The dialog's own button, not the one on the page behind it.
  await page.getByRole('dialog').getByRole('button', { name: 'Unlink from Moodle' }).click()
  check(
    'unlinking sends moodle_props: null',
    await waitUntil(() => puts.length > 0),
    JSON.stringify(puts.at(-1) ?? null),
  )
  // `null` specifically — not an empty object, not a flag. It is the only way core is told to
  // unlink, and it is a distinction that exists nowhere except in the request body.
  check(
    'and null specifically, which is what core reads as unlink',
    puts.at(-1)?.moodle_props === null,
    JSON.stringify(puts.at(-1) ?? null),
  )

  await close()

  // --- and a plain teacher cannot do it -------------------------------------------------------------
  // A second context rather than a role switch, because `activeRole` is seeded in an init script
  // that only re-runs on navigation, and a half-applied role is worse than no check.
  const asTeacher = await launch({ role: 'teacher', shotPrefix: 'participants-moodle-teacher-' })
  const teacherPuts = []
  await fakeApi(asTeacher.page, [
    ['/account/checkin', () => ({})],
    [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
    [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => participants],
    [`/courses/${COURSE}/basic`, () => COURSE_BASIC],
    [new RegExp(`/courses/${COURSE}/moodle(\\?|$)`), ({ method, body }) => {
      if (method === 'PUT') teacherPuts.push(body)
      return method === 'PUT' ? {} : moodleProps()
    }],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await asTeacher.page.goto(`${BASE_URL}/courses/${COURSE}/participants`)
  await asTeacher.page.getByRole('tab', { name: 'Moodle' }).click()
  await asTeacher.page.getByText('PROG-2026').first().waitFor()

  check(
    'a teacher sees the Moodle panel but is offered no unlink button',
    (await asTeacher.page.getByRole('button', { name: 'Unlink from Moodle' }).count()) === 0,
  )
  check(
    'and is told why instead of being left to wonder',
    // The exact sentence, not /admin/i — that would also match the role switcher in the nav and
    // pass on a page that said nothing at all.
    (await asTeacher.page.getByText('Contact an administrator to change Moodle settings.').count()) === 1,
  )
  check('and sent nothing', teacherPuts.length === 0, JSON.stringify(teacherPuts))
  await asTeacher.shot('05-teacher-no-unlink')

  await asTeacher.close()
})
