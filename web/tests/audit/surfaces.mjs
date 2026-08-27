/**
 * The route inventory the sweeps drive — extracted from c5-a11y-sweep.mjs so the viewport sweeps
 * (S3/S4/S5) and any later systematic pass iterate the SAME surfaces with the SAME fixtures. Two
 * lists would drift, and a surface missing from one sweep reads as "clean" rather than "unvisited".
 */
import {
  COURSE_ID,
  CE_ID,
  studentCourse,
  studentExercise,
  exerciseDetails,
  submission,
  teacherActivity,
} from './fixtures.mjs'

/**
 * A response that satisfies almost any `.then(r => r.something)` map.
 *
 * The alternative is stubbing every endpoint of twenty routes, which is a day's work and mostly
 * wasted: this unit is scanning *chrome and controls*, not asserting on data. Returning a superset
 * object means a hook that picks one list key gets an empty array rather than `undefined` — and
 * `undefined` is what makes react-query throw and the route render its error boundary instead of the
 * surface under audit.
 *
 * The honest cost, recorded in the log: routes scanned this way are scanned in their *empty* state.
 * Empty states are real surfaces and C2 wants them anyway, but a table with no rows cannot show a
 * contrast problem in a table cell. Any route whose findings matter gets a realistic pass later.
 */
export const superset = () => ({
  courses: [],
  exercises: [],
  submissions: [],
  teacher_activities: [],
  comments: [],
  inline_comments: [],
  messages: [],
  articles: [],
  students: [],
  teachers: [],
  groups: [],
  invites: [],
  dirs: [],
  items: [],
  executors: [],
  images: [],
  versions: [],
  reports: [],
  count: 0,
  total: 0,
})

/** The surfaces. `thin` marks the ones scanned against `superset()` rather than real fixtures. */
export const SURFACES = [
  {
    name: 'landing',
    path: '/landing',
    role: 'student',
    outsideShell: true,
    thin: true,
  },
  { name: 'not-found', path: '/no-such-page', role: 'student', thin: true },
  { name: 'courses-student', path: '/courses', role: 'student',
    handlers: [[/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })]] },
  { name: 'courses-teacher', path: '/courses', role: 'teacher',
    handlers: [[/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 12, last_submission_at: null, moodle_short_name: null }] })]] },
  { name: 'courses-admin', path: '/courses', role: 'admin',
    handlers: [[/\/teacher\/courses(\?|$)/, () => ({ courses: [{ ...studentCourse(), student_count: 12, last_submission_at: null, moodle_short_name: null }] })]] },
  {
    name: 'exercise-list-student',
    path: `/courses/${COURSE_ID}/exercises`,
    role: 'student',
    handlers: [
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise()] })],
    ],
  },
  {
    name: 'exercise-student',
    path: `/courses/${COURSE_ID}/exercises/${CE_ID}`,
    role: 'student',
    handlers: [
      [/\/student\/courses(\?|$)/, () => ({ courses: [studentCourse()] })],
      [new RegExp(`/student/courses/${COURSE_ID}/exercises(\\?|$)`), () => ({ exercises: [studentExercise()] })],
      [new RegExp(`/exercises/${CE_ID}/submissions/all`), () => ({ submissions: [submission()] })],
      [new RegExp(`/exercises/${CE_ID}/activities`), () => ({ teacher_activities: [teacherActivity()] })],
      [new RegExp(`/exercises/${CE_ID}(\\?|$)`), () => exerciseDetails()],
    ],
  },
  { name: 'exercise-list-teacher', path: `/courses/${COURSE_ID}/exercises`, role: 'teacher', thin: true },
  { name: 'exercise-teacher', path: `/courses/${COURSE_ID}/exercises/${CE_ID}`, role: 'teacher', thin: true },
  { name: 'participants', path: `/courses/${COURSE_ID}/participants`, role: 'teacher', thin: true },
  { name: 'grades', path: `/courses/${COURSE_ID}/grades`, role: 'teacher', thin: true },
  { name: 'similarity', path: `/courses/${COURSE_ID}/similarity`, role: 'teacher', thin: true },
  { name: 'library-dir', path: '/library/dir/root', role: 'teacher', thin: true },
  { name: 'library-exercise', path: '/library/exercise/4242/x', role: 'teacher', thin: true },
  { name: 'join-by-link', path: '/link/ABC123', role: 'student', thin: true },
  { name: 'join-by-moodle-link', path: '/moodle/link/ABC123', role: 'student', thin: true },
  { name: 'articles-admin', path: '/articles', role: 'admin', thin: true },
  { name: 'article-public', path: '/a/juhend', role: 'student', thin: true },
  {
    name: 'about-teacher',
    path: '/about',
    role: 'teacher',
    handlers: [[/\/versions(\?|$)/, () => ({ core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' }, executors: [] })]],
  },
  {
    name: 'about-admin',
    path: '/about',
    role: 'admin',
    handlers: [[/\/versions(\?|$)/, () => ({ core: { version: '4.0', commit: 'abc1234', built_at: '2026-08-10T09:26:52.903Z' }, executors: [] })]],
  },
  { name: 'account', path: '/account', role: 'student', thin: true },
  { name: 'admin-messages', path: '/admin/messages', role: 'admin', thin: true },
  { name: 'embed', path: `/embed/exercises/4242`, role: 'student', outsideShell: true, thin: true },
]
