// Course types

/** @endpoint GET /v2/student/courses -> courses[] */
export interface StudentCourse {
  id: string
  title: string
  alias: string | null
  course_code: string | null
  archived: boolean
  last_accessed: string
  color: string
}

/** @endpoint GET /v2/teacher/courses -> courses[] */
export interface TeacherCourse {
  id: string
  title: string
  alias: string | null
  course_code: string | null
  archived: boolean
  student_count: number
  last_accessed: string
  moodle_short_name: string | null
  last_submission_at: string | null
  color: string
}

// Exercise types

export type GraderType = 'AUTO' | 'TEACHER'
export type StudentExerciseStatus = 'UNSTARTED' | 'UNGRADED' | 'STARTED' | 'COMPLETED'
export type AutoGradeStatus = 'NONE' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
export type SolutionFileType = 'TEXT_EDITOR' | 'TEXT_UPLOAD'

/** @endpoint GET /v2/student/courses/{courseId}/exercises -> exercises[].grade */
export interface GradeResp {
  grade: number
  is_autograde: boolean
  is_graded_directly: boolean
}

/** @endpoint GET /v2/student/courses/{courseId}/exercises -> exercises[] */
export interface CourseExercise {
  id: string
  effective_title: string
  grader_type: GraderType
  deadline: string | null
  is_open: boolean
  status: StudentExerciseStatus
  grade: GradeResp | null
  ordering_idx: number
}

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId} -> (root) */
export interface ExerciseDetails {
  effective_title: string
  text_html: string | null
  deadline: string | null
  grader_type: GraderType
  threshold: number
  instructions_html: string | null
  is_open: boolean
  solution_file_name: string
  solution_file_type: SolutionFileType
}

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/all -> submissions[].auto_assessment */
export interface AutomaticAssessmentResp {
  grade: number
  feedback: string | null
}

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/all -> submissions[] */
export interface SubmissionResp {
  id: string
  number: number
  solution: string
  submission_time: string
  autograde_status: AutoGradeStatus
  grade: GradeResp | null
  submission_status: StudentExerciseStatus
  auto_assessment: AutomaticAssessmentResp | null
}

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/draft -> (root) */
export interface DraftResp {
  solution: string
  created_at: string
}

// Teacher activity types (student view)

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/activities -> teacher_activities[].teacher */
export interface TeacherResp {
  id: string
  given_name: string
  family_name: string
}

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/activities -> teacher_activities[] */
export interface TeacherActivityResp {
  id: string
  submission_id: string
  submission_number: number
  created_at: string
  grade: number | null
  edited_at: string | null
  feedback_md: string | null
  feedback_html: string | null
  teacher: TeacherResp
}

// Was `'comment' | 'suggestion'` until EZ-1777, which is the whole of that issue: core took the
// field as free text and echoed it back, so this union was a promise core did not keep. It is
// core's `InlineCommentType` enum now, hence the uppercase — the same spelling as every other enum
// on this API, and the api-types-contract check compares the two value sets in both directions.
export type InlineCommentType = 'COMMENT' | 'SUGGESTION'

/** @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/inline-comments -> inline_comments[] */
/** @endpoint POST /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/inline-comments -> (root) */
export interface InlineCommentResp {
  id: string
  submission_id: string
  submission_number: number
  teacher: TeacherResp
  created_at: string
  edited_at: string | null
  line_start: number
  line_end: number
  code: string
  text_md: string
  text_html: string
  type: InlineCommentType
  suggested_code?: string
}

// Teacher exercise types

/** @endpoint GET /v2/courses/{courseId}/groups -> groups[] */
export interface GroupResp {
  id: string
  name: string
}

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students -> latest_submissions[].submission */
export interface LatestSubmissionResp {
  id: string
  submission_number: number
  time: string
  grade: GradeResp | null
  seen: boolean
}

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students -> latest_submissions[] */
export interface SubmissionRow {
  submission: LatestSubmissionResp | null
  status: StudentExerciseStatus
  student_id: string
  given_name: string
  family_name: string
  groups: GroupResp[]
}

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises -> exercises[] */
export interface TeacherCourseExercise {
  course_exercise_id: string
  exercise_id: string
  library_title: string
  title_alias: string | null
  effective_title: string
  grade_threshold: number
  student_visible: boolean
  student_visible_from: string | null
  soft_deadline: string | null
  hard_deadline: string | null
  grader_type: GraderType
  ordering_idx: number
  unstarted_count: number
  ungraded_count: number
  started_count: number
  completed_count: number
  latest_submissions: SubmissionRow[]
}

// Teacher exercise detail types

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId} -> exception_students[].soft_deadline */
export interface ExceptionValue {
  value: string | null
}

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId} -> exception_students[] */
export interface ExceptionStudent {
  student_id: string
  soft_deadline: ExceptionValue | null
  hard_deadline: ExceptionValue | null
  student_visible_from: ExceptionValue | null
}

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId} -> exception_groups[] */
export interface ExceptionGroup {
  group_id: number
  soft_deadline: ExceptionValue | null
  hard_deadline: ExceptionValue | null
  student_visible_from: ExceptionValue | null
}

/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId} -> (root) */
export interface TeacherExerciseDetails {
  exercise_id: string
  title: string
  title_alias: string | null
  text_html: string | null
  text_md: string | null
  instructions_html: string | null
  instructions_md: string | null
  soft_deadline: string | null
  hard_deadline: string | null
  grader_type: GraderType
  solution_file_name: string
  solution_file_type: SolutionFileType
  threshold: number
  last_modified: string
  student_visible: boolean
  student_visible_from: string | null
  assessments_student_visible: boolean
  // The auto-assessment configuration. Sent for every AUTO exercise regardless of library access,
  // and these were missing here long after the server was returning them — which is why the
  // course exercise's assessment tab was a placeholder: there was no typed way to reach the data
  // it was meant to show.
  grading_script: string | null
  container_image: string | null
  max_time_sec: number | null
  max_mem_mb: number | null
  assets: LibraryExerciseAsset[] | null
  executors: { id: string; name: string }[] | null
  has_lib_access: boolean
  exception_students: ExceptionStudent[] | null
  exception_groups: ExceptionGroup[] | null
}

// Participants types

/** @endpoint GET /v2/courses/{courseId}/participants -> students[] */
export interface StudentParticipant {
  id: string
  email: string
  given_name: string
  family_name: string
  created_at: string | null
  /**
   * The Moodle account this student is linked to, on a Moodle-linked course. Core has always sent
   * it; it was undeclared here until EZ-1772, so the app could not read it. Nothing renders it yet
   * — the participant table shows a Moodle username only for *pending* students — which is EZ-1778.
   */
  moodle_username: string | null
  groups: GroupResp[]
}

/** @endpoint GET /v2/courses/{courseId}/participants -> teachers[] */
export interface TeacherParticipant {
  id: string
  email: string
  given_name: string
  family_name: string
  created_at: string | null
}

/** @endpoint GET /v2/courses/{courseId}/participants -> students_moodle_pending[] */
export interface MoodlePendingStudent {
  moodle_username: string
  email: string
  invite_id: string
  groups: GroupResp[]
}

/** @endpoint GET /v2/courses/{courseId}/participants -> (root) */
export interface ParticipantsResp {
  students: StudentParticipant[] | null
  teachers: TeacherParticipant[] | null
  students_moodle_pending: MoodlePendingStudent[] | null
  moodle_linked: boolean
}

/** @endpoint GET /v2/courses/{courseId}/moodle -> (root) */
export interface MoodlePropsResp {
  moodle_props: {
    moodle_short_name: string
    students_synced: boolean
    sync_students_in_progress: boolean
    grades_synced: boolean
    sync_grades_in_progress: boolean
  } | null
}

// Teacher submission summary (from all-submissions-by-student endpoint, no solution)
/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/all/students/{studentId} -> submissions[] */
export interface TeacherSubmissionSummaryResp {
  id: string
  submission_number: number
  created_at: string
  status: StudentExerciseStatus
  grade: GradeResp | null
}

// Teacher submission detail (includes solution code)
/** @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId} -> (root) */
export interface TeacherSubmissionDetailResp {
  id: string
  submission_number: number
  solution: string
  created_at: string
  seen: boolean
  autograde_status: AutoGradeStatus
  grade: GradeResp | null
  auto_assessment: AutomaticAssessmentResp | null
}

// Teacher's own test submission
/** @endpoint GET /v2/exercises/{exerciseId}/testing/autoassess/submissions -> submissions[] */
export interface TeacherTestSubmissionResp {
  id: string
  solution: string
  /** Null when the run produced no result — an executor failure, or a submission predating EZ-1756. */
  grade: number | null
  feedback: string | null
  created_at: string
}

// Teacher autoassess result
/** @endpoint POST /v2/exercises/{exerciseId}/testing/autoassess -> (root) */
export interface TeacherAutoassessResp {
  grade: number
  feedback: string | null
}

/** @endpoint GET /v2/courses/{courseId}/invite -> (root) */
export interface CourseInviteResp {
  invite_id: string
  expires_at: string
  created_at: string
  allowed_uses: number
  used_count: number
  uses_remaining: number
}

// Library types

export type DirAccessLevel = 'P' | 'PR' | 'PRA' | 'PRAW' | 'PRAWM'

/** @endpoint GET /v2/lib/dirs/{dirId} -> child_dirs[] */
export interface LibraryDir {
  id: string
  name: string
  effective_access: DirAccessLevel
  is_shared: boolean
  created_at: string
  modified_at: string
}

/** @endpoint GET /v2/lib/dirs/{dirId} -> child_exercises[] */
export interface LibraryExercise {
  exercise_id: string
  dir_id: string
  title: string
  effective_access: DirAccessLevel
  is_shared: boolean
  grader_type: GraderType
  courses_count: number
  created_at: string
  created_by: string
  modified_at: string
  modified_by: string
}

/** @endpoint GET /v2/lib/dirs/{dirId} -> (root) */
export interface LibraryDirResp {
  current_dir: LibraryDir | null
  child_dirs: LibraryDir[]
  child_exercises: LibraryExercise[]
}

/** @endpoint GET /v2/lib/dirs/{dirId}/parents -> parents[] */
export interface LibraryDirParent {
  id: string
  name: string
}

/** @endpoint GET /v2/exercises/{exerciseId} -> assets[] */
export interface LibraryExerciseAsset {
  file_name: string
  file_content: string
}

/** @endpoint GET /v2/exercises/{exerciseId} -> on_courses[] */
export interface LibraryExerciseCourse {
  id: string
  title: string
  alias: string | null
  course_exercise_id: string
  course_exercise_title_alias: string | null
}

/** @endpoint GET /v2/exercises/{exerciseId} -> (root) */
export interface LibraryExerciseDetail {
  dir_id: string
  effective_access: DirAccessLevel
  created_at: string
  is_public: boolean
  is_anonymous_autoassess_enabled: boolean
  owner_id: string
  last_modified: string
  last_modified_by_id: string
  grader_type: GraderType
  solution_file_name: string
  solution_file_type: SolutionFileType
  title: string
  text_html: string | null
  text_md: string | null
  /** Empty string when there is no template; the column is non-nullable. */
  anonymous_autoassess_template: string
  grading_script: string | null
  container_image: string | null
  max_time_sec: number | null
  max_mem_mb: number | null
  assets: LibraryExerciseAsset[] | null
  executors: { id: string; name: string }[] | null
  on_courses: LibraryExerciseCourse[]
  on_courses_no_access: number
}

/** Body of PUT /exercises/{id} — the whole exercise, not a patch. */
/** @requestBody PUT /v2/exercises/{exerciseId} */
export interface LibraryExerciseUpdate {
  title: string
  text_md: string | null
  grader_type: GraderType
  solution_file_name: string
  solution_file_type: SolutionFileType
  grading_script: string | null
  container_image: string | null
  max_time_sec: number | null
  max_mem_mb: number | null
  assets: LibraryExerciseAsset[] | null
}

// Library sharing/access types

/** @endpoint GET /v2/lib/dirs/{dirId}/access -> direct_accounts[].inherited_from */
export interface InheritingDirRef {
  id: string
  name: string
}

/** @endpoint GET /v2/lib/dirs/{dirId}/access -> direct_any */
export interface AnyAccessResp {
  access: DirAccessLevel
  inherited_from?: InheritingDirRef
}

/** @endpoint GET /v2/lib/dirs/{dirId}/access -> direct_accounts[] */
export interface AccountAccessResp {
  username: string
  given_name: string
  family_name: string
  email: string | null
  group_id: string
  access: DirAccessLevel
  inherited_from?: InheritingDirRef
}

/** @endpoint GET /v2/lib/dirs/{dirId}/access -> direct_groups[] */
export interface GroupAccessResp {
  id: string
  name: string
  access: DirAccessLevel
  inherited_from?: InheritingDirRef
}

/** @endpoint GET /v2/lib/dirs/{dirId}/access -> (root) */
export interface DirAccessesResp {
  direct_any: AnyAccessResp | null
  direct_accounts: AccountAccessResp[]
  direct_groups: GroupAccessResp[]
  inherited_any: AnyAccessResp | null
  inherited_accounts: AccountAccessResp[]
  inherited_groups: GroupAccessResp[]
}

// --- similarity ---------------------------------------------------------------------------------
// `POST /exercises/{exerciseId}/similarity` compares submissions of the same *library* exercise,
// optionally across several courses. Both scores are percentages of a whole-text comparison: a Dice
// coefficient over character bigrams, and a Levenshtein-based FuzzyWuzzy ratio. Neither understands
// code, so renaming variables lowers both — worth knowing before reading a low score as innocence.

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> submissions[] */
export interface SimilarSubmissionResp {
  id: string
  created_at: string
  solution: string
  given_name: string
  family_name: string
  course_title: string
}

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> scores[] */
export interface SimilarityScoreResp {
  sub_1: string
  sub_2: string
  /** Dice coefficient, 0–100. */
  score_a: number
  /** Levenshtein-based ratio, 0–100. */
  score_b: number
}

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> (root) */
export interface SimilarityResp {
  submissions: SimilarSubmissionResp[]
  /** Core returns at most the 100 highest-scoring pairs, ordered by score_a + score_b. */
  scores: SimilarityScoreResp[]
}
