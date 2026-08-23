/**
 * Shared fixtures for the EZ-1791 audit drivers.
 *
 * Shapes come from `web/src/api/types.ts`, which carries an `@endpoint` annotation per interface and
 * is contract-checked against `doc/core/api-shapes.json` by tests/support/api-types-contract.mjs.
 * That is the authority — five fixtures written from memory during EZ-1766 were all wrong, and a
 * sixth (`versions.core = null`) crashed a route during this programme's own setup.
 */

export const COURSE_ID = '119'
export const CE_ID = '4147'

/** GET /v2/student/courses -> courses[] */
export const studentCourse = (over = {}) => ({
  id: COURSE_ID,
  title: 'Programmeerimise alused',
  alias: null,
  archived: false,
  color: '#1976d2',
  course_code: 'LTAT.03.001',
  last_accessed: '2026-08-20T09:00:00.000Z',
  ...over,
})

/** GET /v2/student/courses/{c}/exercises -> exercises[] — `CourseExercise` */
export const studentExercise = (over = {}) => ({
  id: CE_ID,
  effective_title: 'Kahe arvu summa',
  grader_type: 'AUTO',
  deadline: null,
  is_open: true,
  status: 'UNSTARTED',
  grade: null,
  ordering_idx: 0,
  ...over,
})

/** GET /v2/student/courses/{c}/exercises/{ce} -> root — `ExerciseDetails` */
export const exerciseDetails = (over = {}) => ({
  effective_title: 'Kahe arvu summa',
  text_html:
    '<p>Kirjuta programm, mis küsib kasutajalt kaks arvu ja väljastab nende summa.</p>' +
    '<p>Näiteks kui kasutaja sisestab <code>2</code> ja <code>3</code>, siis programm väljastab <code>5</code>.</p>',
  deadline: null,
  grader_type: 'AUTO',
  threshold: 100,
  instructions_html: null,
  is_open: true,
  solution_file_name: 'lahendus.py',
  solution_file_type: 'TEXT_EDITOR',
  ...over,
})

/** GET .../submissions/all -> submissions[] — `SubmissionResp` */
export const submission = (over = {}) => ({
  id: '9001',
  number: 1,
  solution: 'a = int(input())\nb = int(input())\nprint(a + b)\n',
  submission_time: '2026-08-22T14:03:00.000Z',
  autograde_status: 'COMPLETED',
  grade: { grade: 100, is_autograde: true, is_graded_directly: false },
  submission_status: 'COMPLETED',
  auto_assessment: { grade: 100, feedback: okV3(true) },
  ...over,
})

/**
 * The grader's `OK_V3` feedback envelope, which `AutoTestResults` parses. Two tests, one of which
 * fails in the `passing: false` variant, so the student-facing failure copy is auditable.
 */
export function okV3(allPass = true) {
  return JSON.stringify({
    result_type: 'OK_V3',
    producer: 'tiivad',
    points: allPass ? 100 : 50,
    pre_evaluate_error: null,
    tests: [
      {
        title: 'Programm küsib kaks arvu',
        status: 'PASS',
        exception_message: null,
        user_inputs: ['2', '3'],
        created_files: [],
        actual_output: '5\n',
        converted_submission: null,
        checks: [
          {
            title: 'Väljund sisaldab õiget summat',
            status: 'PASS',
            feedback: 'Väljund oli ootuspärane.',
          },
        ],
      },
      {
        title: 'Programm töötab ka negatiivsete arvudega',
        status: allPass ? 'PASS' : 'FAIL',
        exception_message: null,
        user_inputs: ['-4', '2'],
        created_files: [],
        actual_output: allPass ? '-2\n' : '6\n',
        converted_submission: null,
        checks: [
          {
            title: 'Väljund sisaldab õiget summat',
            status: allPass ? 'PASS' : 'FAIL',
            feedback: allPass
              ? 'Väljund oli ootuspärane.'
              : 'Ootasin väljundis stringi "-2", aga seda ei leidnud.',
          },
        ],
      },
    ],
  })
}

/** GET .../activities -> teacher_activities[] — `TeacherActivityResp` */
export const teacherActivity = (over = {}) => ({
  id: '7001',
  submission_id: '9001',
  submission_number: 1,
  created_at: '2026-08-22T16:10:00.000Z',
  grade: 80,
  edited_at: null,
  feedback_md: 'Töötab, aga proovi järgmine kord muutujatele kõnekamad nimed anda.',
  feedback_html:
    '<p>Töötab, aga proovi järgmine kord muutujatele kõnekamad nimed anda.</p>',
  teacher: { id: 't1', given_name: 'Mari', family_name: 'Tamm' },
  ...over,
})

/**
 * The always-needed handlers. Anchored regexes, not bare substrings: `/statistics` is a prefix of
 * `/statistics/common` and the harness prints a [broad stub] warning for exactly that mistake.
 */
export const baseHandlers = () => [
  ['/account/checkin', () => ({})],
  [/\/statistics(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 12345, total_users: 678 })],
  [/\/statistics\/common(\?|$)/, () => ({ in_auto_assessing: 0, total_submissions: 12345, total_users: 678 })],
  [/\/messages(\?|$)/, () => ({ messages: [] })],
]
