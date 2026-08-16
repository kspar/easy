/**
 * `/courses` — the page every session starts on, and one of the last two routes with no direct
 * coverage. Six other specs *visit* it, and every one of them asserts on the surrounding chrome.
 *
 * It is really three pages behind one route, chosen by `activeRole`:
 *
 *   student   `GET /v2/student/courses`   — no student_count, no create button
 *   teacher   `GET /v2/teacher/courses`   — counts, aliases, no create button
 *   admin     the same teacher endpoint   — plus create, and the *real* title instead of the alias
 *
 * The failure worth guarding is asking the **wrong endpoint for the role**. A student hitting
 * `/teacher/courses` gets a 403 from core, which the page renders as "something went wrong" — a
 * generic error for a bug that has nothing to do with the network, on the first screen anyone
 * sees. It cannot be caught by looking at the page, only by watching what it asked for.
 *
 *   cd web && npx playwright test courses-page
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

/** `ReadStudentCourses.CourseResp` — note there is no `student_count` on this one. */
const studentCourse = (id, title, over = {}) => ({
  id,
  title,
  alias: null,
  archived: false,
  color: '#1976d2',
  course_code: null,
  last_accessed: '2026-08-15T09:00:00.000Z',
  ...over,
})

/** `ReadTeacherCourses.CourseResp` — a different shape, with counts and Moodle. */
const teacherCourse = (id, title, over = {}) => ({
  id,
  title,
  alias: null,
  archived: false,
  color: '#1976d2',
  course_code: null,
  last_accessed: '2026-08-15T09:00:00.000Z',
  last_submission_at: null,
  moodle_short_name: null,
  student_count: 12,
  ...over,
})

test('courses-page', async ({ launch, check }) => {
  // --- a student ------------------------------------------------------------------------------------
  const student = await launch({ role: 'student', shotPrefix: 'courses-student-' })
  const studentAsked = []
  student.page.on('request', (r) => {
    if (r.url().includes('/v2/')) studentAsked.push(r.url().replace(/^.*\/v2/, ''))
  })
  await fakeApi(student.page, [
    ['/account/checkin', () => ({})],
    [/\/student\/courses(\?|$)/, () => ({
      courses: [
        studentCourse('1', 'Programming 101'),
        studentCourse('2', 'Algorithms', { course_code: 'LTAT.03.001' }),
      ],
    })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [teacherCourse('99', 'Should never be shown')] })],
  ], { log: false })

  await student.page.goto(`${BASE_URL}/courses`)
  check(
    'a student sees their own courses',
    await waitUntil(async () => (await student.page.getByText('Programming 101').count()) > 0),
  )
  check(
    'and they are asked for from the student endpoint',
    studentAsked.some((u) => u.startsWith('/student/courses')),
    studentAsked.join(', '),
  )
  check(
    'while the teacher endpoint is never touched, which would 403',
    !studentAsked.some((u) => u.startsWith('/teacher/courses')),
    studentAsked.filter((u) => u.includes('teacher')).join(', ') || 'none',
  )
  check(
    'so nothing from the teacher list leaks onto the page',
    (await student.page.getByText('Should never be shown').count()) === 0,
  )
  /**
   * A course card must be a **real link**, not a Card with an onClick.
   *
   * Both card variants were bare `onClick={() => navigate(...)}` until this spec was written, so
   * ctrl/cmd+click could not open a course in a new tab — on the first screen of every session.
   * There is no `href` to open, nothing in the status bar, nothing to copy from the context menu,
   * and nothing for a screen reader to announce as a link. `spaLinkProps` (now shared, previously
   * stranded in GradeTablePage) is the project's sanctioned pattern for exactly this.
   *
   * Asserting the `href` rather than the click is what catches it: clicking works either way.
   */
  const courseLink = student.page.getByRole('link', { name: /Programming 101/ }).first()
  check(
    'a course card is a real link, so it can be opened in a new tab',
    await waitUntil(async () => (await courseLink.getAttribute('href')) === '/courses/1/exercises'),
    await courseLink.getAttribute('href'),
  )

  // And a plain click is still handled in-page rather than reloading the app.
  await courseLink.click()
  check(
    'and a plain click still navigates without a full page load',
    await waitUntil(() => student.page.url().endsWith('/courses/1/exercises')),
    student.page.url(),
  )
  await student.page.goBack()
  await waitUntil(async () => (await student.page.getByText('Programming 101').count()) > 0)
  check(
    'a course code is shown when the course has one',
    (await student.page.getByText('LTAT.03.001').count()) > 0,
  )
  check(
    'and a student is offered no way to create a course',
    (await student.page.getByRole('button', { name: /new course|create/i }).count()) === 0,
  )
  await student.shot('01-student')
  await student.close()

  // --- a teacher ------------------------------------------------------------------------------------
  const teacher = await launch({ role: 'teacher', shotPrefix: 'courses-teacher-' })
  const teacherAsked = []
  teacher.page.on('request', (r) => {
    if (r.url().includes('/v2/')) teacherAsked.push(r.url().replace(/^.*\/v2/, ''))
  })
  await fakeApi(teacher.page, [
    ['/account/checkin', () => ({})],
    [/\/teacher\/courses(\?|$)/, () => ({
      courses: [
        // An alias, which a teacher sees *instead of* the real title. Distinct strings so the two
        // cannot be confused for one another.
        teacherCourse('1', 'LTAT.03.001 Programmeerimine', { alias: 'My Python course', student_count: 34 }),
        teacherCourse('2', 'Empty course', { student_count: 0 }),
      ],
    })],
    [/\/student\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await teacher.page.goto(`${BASE_URL}/courses`)
  check(
    'a teacher sees the alias rather than the official title',
    await waitUntil(async () => (await teacher.page.getByText('My Python course').count()) > 0),
  )
  check(
    'and is asked from the teacher endpoint',
    teacherAsked.some((u) => u.startsWith('/teacher/courses')),
    teacherAsked.join(', '),
  )
  check(
    'the student count is shown',
    (await teacher.page.getByText(/34/).count()) > 0,
  )
  check(
    'including zero, which must not be hidden as a falsy value',
    // `{count && <Chip/>}` renders nothing for 0 and would look identical to a course whose count
    // failed to load. A course with no students is a fact worth showing.
    (await teacher.page.getByText(/\b0\b/).count()) > 0,
  )
  check(
    'and a teacher is offered no create button either',
    (await teacher.page.getByRole('button', { name: /new course|create/i }).count()) === 0,
  )
  await teacher.shot('02-teacher')
  await teacher.close()

  // --- an admin -------------------------------------------------------------------------------------
  const admin = await launch({ role: 'teacher,admin', shotPrefix: 'courses-admin-' })
  await admin.page.addInitScript(() => localStorage.setItem('activeRole', 'admin'))
  const created = []
  await fakeApi(admin.page, [
    ['/account/checkin', () => ({})],
    [/\/teacher\/courses(\?|$)/, ({ method, body }) => {
      if (method === 'POST') {
        created.push(body)
        return { id: '7' }
      }
      return {
        courses: [
          teacherCourse('1', 'LTAT.03.001 Programmeerimine', { alias: 'My Python course' }),
        ],
      }
    }],
    [/\/student\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })

  await admin.page.goto(`${BASE_URL}/courses`)
  check(
    'an admin sees the real title, not the teacher-facing alias',
    // The inversion is the point: an alias is a teacher's private label, and an admin managing
    // courses needs the name the institution knows them by.
    await waitUntil(async () => (await admin.page.getByText('LTAT.03.001 Programmeerimine').count()) > 0),
  )
  check(
    'and is offered a create button, which the other roles are not',
    (await admin.page.getByRole('button', { name: /new course|create/i }).count()) > 0,
  )
  await admin.shot('03-admin')
  await admin.close()

  // --- nothing to show ------------------------------------------------------------------------------
  const empty = await launch({ role: 'student', shotPrefix: 'courses-empty-' })
  await fakeApi(empty.page, [
    ['/account/checkin', () => ({})],
    [/\/student\/courses(\?|$)/, () => ({ courses: [] })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })
  await empty.page.goto(`${BASE_URL}/courses`)
  // `body`, not `main` — this layout has no <main> landmark, and locating one that does not exist
  // throws rather than failing a check, which took the whole spec down after fifteen green ones.
  // (That absence is itself an accessibility gap: there is no landmark to skip to. Phase 9.)
  check(
    'a student on no courses gets a message rather than an empty screen',
    await waitUntil(async () => {
      const body = (await empty.page.locator('body').innerText()).replace(/\s+/g, ' ').trim()
      return body.length > 0 && !body.includes('went wrong')
    }),
  )
  await empty.shot('04-empty')
  await empty.close()
})
