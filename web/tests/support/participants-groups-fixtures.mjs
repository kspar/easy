/**
 * Shared fixtures and stubs for the three `participants-groups*` specs.
 *
 * They exist as a module rather than as duplicated preambles because the three differ on **one
 * field** — `moodle_linked` — and on nothing else. Copying the roster three times would have made
 * that the least visible thing about them, when it is the only thing that matters:
 *
 *   locked      moodle_linked: true   — group editing is Moodle's, the UI offers none
 *   membership  moodle_linked: false  — and, since EZ-1780, with no pending rows either
 *   groups      moodle_linked: true   — counting and deleting groups, which are not gated
 *
 * The membership case used to be `moodle_linked: false` **with** the pending rows an unlink left
 * behind, which is how it reached a mixed active+pending selection. Unlinking deletes them now, so
 * that combination no longer exists and the spec overrides `students_moodle_pending` to `[]` rather
 * than relying on the shared value. The shared value stays populated because the other two specs are
 * Moodle-linked, where pending students are exactly what you expect to see.
 *
 * **One `test()` per spec file.** Not a style preference: `spec.mjs` compares each test's check count
 * against a single number keyed by *file*, and `record-checks.mjs` keys its Map by file too — so a
 * second test in one file makes the smaller test fail the ratchet, and recording would keep only the
 * last count as the floor. Three scenarios, three files.
 */
export const COURSE = '88'

export const GROUPS = [
  { id: 'g1', name: 'Rühm A' },
  { id: 'g2', name: 'Rühm B' },
  { id: 'g3', name: 'Empty group' },
]

export const active = (id, given, family, groups = []) => ({
  id,
  email: `${id}@example.com`,
  given_name: given,
  family_name: family,
  created_at: '2026-08-01T09:00:00.000Z',
  moodle_username: null,
  groups,
})

export const pending = (username, groups = []) => ({
  moodle_username: username,
  email: `${username}@moodle.example.com`,
  invite_id: `inv-${username}`,
  groups,
})

export const participants = {
  students: [
    active('s1', 'Mari', 'Maasikas', [GROUPS[0]]),
    active('s2', 'Jaan', 'Tamm', [GROUPS[1]]),
    active('s3', 'Peeter', 'Kask'),
  ],
  teachers: [],
  // In Rühm A, so that group holds one active student and one pending one. A count that only
  // counted active students would say 1 where the truth is 2, and look entirely reasonable.
  students_moodle_pending: [pending('kati', [GROUPS[0]])],
  moodle_linked: true,
}

/**
 * Everything except the participants payload, which is what the three tests differ on.
 *
 * `membershipCalls` records the group-membership endpoint by method, group id and body — the body is
 * the point of the third test, so it is captured rather than counted.
 */
export const baseStubs = (membershipCalls = [], deletedGroups = []) => [
  ['/account/checkin', () => ({})],
  [`/courses/${COURSE}/basic`, () => ({
    title: 'Programming 101', alias: null, archived: false, color: '#1976d2', course_code: null,
    moodle_course_url: null,
  })],
  // Students in/out of a group, which carries a body; and deleting a group, which does not. Both
  // patterns must precede the plain `/groups` one below, which would otherwise swallow them.
  [/\/courses\/\d+\/groups\/[^/]+\/students$/, ({ method, url, body }) => {
    membershipCalls.push({ method, groupId: new URL(url).pathname.split('/').at(-2), body })
    // `deleted_count` on the DELETE, per RemoveStudentsFromCourseGroupController.Resp.
    return method === 'DELETE' ? { deleted_count: 0 } : {}
  }],
  [/\/courses\/\d+\/groups\/[^/]+$/, ({ method, url }) => {
    if (method === 'DELETE') deletedGroups.push(new URL(url).pathname.split('/').pop())
    return {}
  }],
  [new RegExp(`/courses/${COURSE}/groups(\\?|$)`), () => ({ groups: GROUPS })],
  // A course with no invite link yet: an empty body, not an object full of nulls, because
  // ReadCourseInviteDetails.Resp has no nullable fields and the app reads `r ?? null`.
  [new RegExp(`/courses/${COURSE}/invite(\\?|$)`), ({ route }) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '' })],
  [/\/student\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: [] })],
  [/\/teacher\/courses(\?|$)/, () => ({ courses: [] })],
]

export const participantsStub = (payload) => [
  [new RegExp(`/courses/${COURSE}/participants(\\?|$)`), () => payload],
]
