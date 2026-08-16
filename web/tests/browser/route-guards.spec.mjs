/**
 * Who is allowed to reach which page, and — the half that matters — what the app asks for on the
 * way to finding out.
 *
 * `RequireAuth` is 58 lines with no coverage at all, and it is the only thing standing between a
 * student and a teacher's page. The client-side guard is not the security boundary — core's
 * `@Secured` and `assertAccess` are, and `EndpointAuthorizationMatrixTest` covers those across all
 * 124 endpoints. What this pins is the other failure: a guard that redirects **after** its
 * children have mounted and fired their queries.
 *
 * That pattern leaks in a way nobody sees. The page flashes and vanishes, the redirect looks
 * right, and meanwhile the browser has asked for the participants list, the grade table and the
 * library — each of which core refuses, so nothing breaks and nothing is logged anywhere a
 * developer looks. It is only visible by counting requests, which is what this spec does.
 *
 * The guard as written returns `<Navigate>` before rendering children, so the count should be
 * zero. That is a property worth a test precisely because it is one `useEffect` away from not
 * being true, and the diff that broke it would look like a tidy-up.
 *
 *   cd web && npx playwright test route-guards
 */
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

const COURSE = '9006'

/** Every route `routes.tsx` restricts, with the roles it lets through. */
const RESTRICTED = [
  { path: '/admin/messages', roles: ['admin'], name: 'system messages' },
  { path: `/courses/${COURSE}/participants`, roles: ['teacher', 'admin'], name: 'participants' },
  { path: `/courses/${COURSE}/grades`, roles: ['teacher', 'admin'], name: 'the grade table' },
  { path: `/courses/${COURSE}/similarity`, roles: ['teacher', 'admin'], name: 'similarity' },
  { path: '/library/dir/root', roles: ['teacher', 'admin'], name: 'the library' },
  { path: '/articles', roles: ['admin'], name: 'articles' },
]

/**
 * Requests that only a teacher's or admin's page ever makes.
 *
 * Deliberately not "any /v2 request": the shell asks for the course list and the account on every
 * page whatever the role, and counting those would make every assertion below fail for a reason
 * that has nothing to do with the guard.
 */
const PRIVILEGED = [
  '/participants',
  '/grades',
  '/similarity',
  '/lib/dirs',
  '/articles',
  '/management/common/messages',
  '/submissions/latest',
]

test('route-guards', async ({ launch, check }) => {
  // --- a student meets every restricted route ------------------------------------------------------
  const student = await launch({ role: 'student', shotPrefix: 'guards-student-' })
  const studentCalls = []

  await fakeApi(student.page, [
    ['/account/checkin', () => ({})],
    // Anchored: as a substring this also matched `/student/courses/9006/exercises` and answered it
    // with a course list, which core never sends there.
    [/\/student\/courses(\?|$)/, () => ({ courses: [] })],
    [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
  ], { log: false })
  student.page.on('request', (r) => {
    if (r.url().includes('/v2/')) studentCalls.push(r.url().replace(/^.*\/v2/, ''))
  })

  for (const route of RESTRICTED) {
    const before = studentCalls.length
    await student.page.goto(`${BASE_URL}${route.path}`)
    // The redirect is synchronous — `<Navigate>` renders instead of the children — but the URL is
    // updated by the router a tick later, so this waits rather than reading it immediately.
    const landed = await waitUntil(() => student.page.url().endsWith('/courses'))

    check(`a student sent to ${route.name} lands on /courses`, landed, student.page.url())

    const during = studentCalls.slice(before)
    const leaked = during.filter((u) => PRIVILEGED.some((p) => u.includes(p)))
    check(
      `and asks for nothing privileged on the way`,
      leaked.length === 0,
      leaked.join(', ') || 'none',
    )
  }
  await student.shot('01-student-redirected')

  // A student *is* allowed here — otherwise the checks above would pass on an app that redirected
  // everything to /courses, including /courses.
  await student.page.goto(`${BASE_URL}/courses`)
  check(
    'a student reaches their own courses page',
    await waitUntil(async () => (await student.page.getByRole('heading').count()) > 0) &&
      student.page.url().endsWith('/courses'),
  )
  await student.close()

  // --- a teacher is admitted where a student was not ------------------------------------------------
  // The mirror image, and the reason it is here: a guard that refused *everyone* would satisfy
  // every assertion above.
  const teacher = await launch({ role: 'teacher', shotPrefix: 'guards-teacher-' })
  await fakeApi(teacher.page, [
    ['/account/checkin', () => ({})],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
    [`/courses/${COURSE}/basic`, () => ({
      title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
    })],
    [`/courses/${COURSE}/groups`, () => ({ groups: [] })],
    [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => ({
      students: [], teachers: [], students_moodle_pending: [], moodle_linked: false,
    })],
    [/\/teacher\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
    [/\/lib\/dirs\//, () => ({ current_dir: null, child_dirs: [], child_exercises: [] })],
  ], { log: false })

  for (const route of RESTRICTED.filter((r) => r.roles.includes('teacher'))) {
    await teacher.page.goto(`${BASE_URL}${route.path}`)
    check(
      `a teacher reaches ${route.name}`,
      await waitUntil(() => teacher.page.url().includes(route.path)),
      teacher.page.url(),
    )
  }
  await teacher.shot('02-teacher-admitted')

  // And is still refused the admin-only ones, which is what distinguishes a role check from a
  // logged-in check.
  for (const route of RESTRICTED.filter((r) => !r.roles.includes('teacher'))) {
    await teacher.page.goto(`${BASE_URL}${route.path}`)
    check(
      `a teacher is refused ${route.name}, which is admin-only`,
      await waitUntil(() => teacher.page.url().endsWith('/courses')),
      teacher.page.url(),
    )
  }
  await teacher.close()

  // --- an admin gets everything ----------------------------------------------------------------------
  const admin = await launch({ role: 'teacher,admin', shotPrefix: 'guards-admin-' })
  await admin.page.addInitScript(() => localStorage.setItem('activeRole', 'admin'))
  await fakeApi(admin.page, [
    ['/account/checkin', () => ({})],
    [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
    ['/management/common/messages', () => ({ messages: [] })],
    [/\/articles(\?|$)/, () => ({ articles: [], count: 0 })],
  ], { log: false })

  for (const route of RESTRICTED.filter((r) => r.roles.includes('admin') && !r.roles.includes('teacher'))) {
    await admin.page.goto(`${BASE_URL}${route.path}`)
    check(
      `an admin reaches ${route.name}`,
      await waitUntil(() => admin.page.url().includes(route.path)),
      admin.page.url(),
    )
  }
  await admin.shot('03-admin-admitted')
  await admin.close()
})
