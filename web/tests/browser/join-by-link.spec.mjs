/**
 * Joining a course from an invite link — 92 lines of page in which almost every branch is
 * invisible when it is wrong.
 *
 * Nothing covered this before. It matters more than its size suggests: the link is handed out by
 * teachers and by Moodle, it is the first thing a new student ever sees of this application, and
 * every failure mode is silent. A lowercased invite id 404s and reads as "the teacher gave me a
 * bad link". A missing `moodle/` prefix asks the wrong endpoint and reads the same way. A
 * non-student following it should be told so, not shown a join button that cannot work.
 *
 * The subtlest one is the `joiningStarted` guard, whose comment describes a bug nothing pinned:
 * joining refreshes the student's course list, and that refreshed list arriving would otherwise
 * satisfy the "already on this course" redirect and throw the visitor past their own confirmation.
 *
 *   cd web && npx playwright test join-by-link
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '4242'
const INVITE = 'ABC123'
const TITLE = 'Programming 101'

/**
 * A row of `GET /v2/student/courses`, which is a different shape from the teacher's course list —
 * it carries `last_accessed` and no `student_count`. Getting that backwards was the fixture's
 * first mistake here, and it is the kind that never shows on screen because the page reads only
 * `id`.
 */
const course = (id, title) => ({
  id,
  title,
  alias: null,
  archived: false,
  color: '#1976d2',
  course_code: null,
  last_accessed: '2026-08-15T09:00:00.000Z',
})

test('join-by-link', async ({ launch, check }) => {
  // --- a teacher is told it is not for them ---------------------------------------------------------
  // First, because it is the one branch that must make **no** requests at all: `useCourseByInvite`
  // is passed `enabled: isStudent`, so a teacher following the link should not even look it up.
  const teacher = await launch({ role: 'teacher', shotPrefix: 'join-teacher-' })
  const teacherCalls = []
  teacher.page.on('request', (r) => {
    if (r.url().includes('/v2/')) teacherCalls.push(r.url().replace(/^.*\/v2/, ''))
  })
  await fakeApi(teacher.page, [
    ['/account/checkin', () => ({})],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await teacher.page.goto(`${BASE_URL}/link/${INVITE}`)
  check(
    'a teacher following an invite is told it is for students',
    await waitUntil(async () => (await teacher.page.getByText(/students/i).count()) > 0),
  )
  check(
    'and no invite is looked up on their behalf',
    !teacherCalls.some((u) => u.includes('/invite/') || u.includes('/join/')),
    teacherCalls.filter((u) => u.includes('invite') || u.includes('join')).join(', ') || 'none',
  )
  check(
    'and certainly nothing is joined',
    !teacherCalls.some((u) => u.includes('/join/')),
    teacherCalls.join(', ') || 'none',
  )
  await teacher.shot('01-teacher-refused')
  await teacher.close()

  // --- a student, and the shape of what gets asked ---------------------------------------------------
  const student = await launch({ role: 'student', shotPrefix: 'join-' })
  const lookups = []
  const joins = []
  let myCourses = []

  await fakeApi(student.page, [
    ['/account/checkin', () => ({})],
    [/\/courses\/(moodle\/)?invite\//, ({ url }) => {
      lookups.push(new URL(url).pathname)
      return { course_id: COURSE, course_title: TITLE }
    }],
    [/\/courses\/(moodle\/)?join\//, ({ url, method }) => {
      if (method === 'POST') joins.push(new URL(url).pathname)
      myCourses = [course(COURSE, TITLE)]
      return { course_id: COURSE }
    }],
    [/\/student\/courses(\?|$)/, () => ({ courses: myCourses })],
    [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  // Deliberately lowercase in the URL. Invite ids are minted uppercase and the page upper-cases
  // before asking — without that, every link anyone typed by hand or that passed through a
  // lowercasing mail client would 404 and look like the teacher's fault.
  await student.page.goto(`${BASE_URL}/link/${INVITE.toLowerCase()}`)
  check(
    'the course behind the invite is shown',
    await waitUntil(async () => (await student.page.getByText(TITLE).count()) > 0),
  )
  check(
    'a lowercase invite in the URL is asked for in upper case',
    lookups.length > 0 && lookups.at(-1).endsWith(`/invite/${INVITE}`),
    lookups.join(', '),
  )
  check(
    'and the plain course endpoint is used, not the Moodle one',
    !lookups.at(-1)?.includes('/moodle/'),
    lookups.at(-1),
  )
  check('the invite code is shown back to the visitor', (await student.page.getByText(INVITE).count()) > 0)
  await student.shot('02-invited')

  // --- joining -----------------------------------------------------------------------------------------
  await student.page.getByRole('button', { name: /join/i }).first().click()

  check(
    'joining posts once, to the plain join endpoint',
    await waitUntil(() => joins.length === 1),
    joins.join(', '),
  )
  check('and not the Moodle one', !joins[0]?.includes('/moodle/'), joins[0])
  check(
    'with the invite id upper-cased there too',
    joins[0]?.endsWith(`/join/${INVITE}`),
    joins[0],
  )

  /**
   * The `joiningStarted` guard.
   *
   * The join succeeds and invalidates the student's course list; the refetched list now contains
   * this course. Without the guard, the "already on this course" branch fires on that refresh and
   * redirects immediately — past the confirmation, past the celebration, and past the welcome
   * message the destination shows from `state.joinedCourse`.
   *
   * So the assertion is that the visitor is *still here* a moment after joining. The page holds
   * for 1.7s deliberately; this checks well inside that.
   */
  await student.page.waitForTimeout(600)
  check(
    'the confirmation is not skipped by the course list refreshing underneath it',
    student.page.url().includes(`/link/`),
    student.page.url(),
  )
  await student.shot('03-joined')

  check(
    'and then it hands over to the course',
    await waitUntil(() => student.page.url().includes(`/courses/${COURSE}/exercises`), { timeout: 5000 }),
    student.page.url(),
  )

  // --- already a member --------------------------------------------------------------------------------
  // Now that the course list contains it, following the link again should not offer to join
  // anything — it should go straight there.
  // Sampled *before* the navigation. Taking it afterwards would fold an auto-join fired during
  // that goto into the baseline, so the "without joining a second time" check below would pass on
  // precisely the regression it exists to catch.
  const joinsBefore = joins.length
  await student.page.goto(`${BASE_URL}/link/${INVITE}`)
  check(
    'following the link again goes straight to the course',
    await waitUntil(() => student.page.url().includes(`/courses/${COURSE}/exercises`)),
    student.page.url(),
  )
  check('without joining a second time', joins.length === joinsBefore, `${joins.length - joinsBefore} extra`)
  await student.close()

  // --- the Moodle variant ------------------------------------------------------------------------------
  // A separate route with the same component and `isMoodle`. The prefix appears twice — once for
  // the lookup and once for the join — and getting either wrong asks core a question about the
  // wrong kind of invite.
  const moodle = await launch({ role: 'student', shotPrefix: 'join-moodle-' })
  const moodleLookups = []
  const moodleJoins = []
  await fakeApi(moodle.page, [
    ['/account/checkin', () => ({})],
    [/\/courses\/(moodle\/)?invite\//, ({ url }) => {
      moodleLookups.push(new URL(url).pathname)
      return { course_id: COURSE, course_title: TITLE }
    }],
    [/\/courses\/(moodle\/)?join\//, ({ url, method }) => {
      if (method === 'POST') moodleJoins.push(new URL(url).pathname)
      return { course_id: COURSE }
    }],
    [/\/student\/courses(\?|$)/, () => ({ courses: [] })],
    [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await moodle.page.goto(`${BASE_URL}/moodle/link/${INVITE.toLowerCase()}`)
  check(
    'the Moodle route looks the invite up under moodle/',
    await waitUntil(() => moodleLookups.length > 0 && moodleLookups.at(-1).includes('/moodle/invite/')),
    moodleLookups.join(', '),
  )
  await moodle.page.getByRole('button', { name: /join/i }).first().click()
  check(
    'and joins under moodle/ as well, which is the half that is easy to forget',
    await waitUntil(() => moodleJoins.length === 1 && moodleJoins[0].includes('/moodle/join/')),
    moodleJoins.join(', '),
  )
  await moodle.shot('04-moodle')
  await moodle.close()
})
