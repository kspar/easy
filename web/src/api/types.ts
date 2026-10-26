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
  /** EZ-1712. The course has an AI provider, so a failed submission can be explained. */
  ai_feedback_enabled: boolean
  /** How many more explanations this student may ask for on this exercise. */
  ai_explanations_left: number
  /** Longest solution, in characters, the course will explain. */
  ai_max_solution_chars: number
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

/**
 * EZ-1712. An AI's explanation of a failed submission. No teacher, no grade; the feed shows it under
 * an "AI" label. The same shape comes back from the student's POST that creates one.
 *
 * @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/activities -> ai_feedback[]
 * @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/students/{studentId}/activities -> ai_feedback[]
 * @endpoint POST /v2/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/ai-feedback -> (root)
 */
export interface AiFeedbackResp {
  id: string
  submission_id: string
  submission_number: number
  created_at: string
  provider: 'ANTHROPIC'
  model: string
  feedback_md: string
  feedback_html: string
}

/**
 * @endpoint GET /v2/student/courses/{courseId}/exercises/{courseExerciseId}/activities -> (root)
 * @endpoint GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/students/{studentId}/activities -> (root)
 */
export interface ActivitiesResp {
  teacher_activities: TeacherActivityResp[]
  ai_feedback: AiFeedbackResp[]
}

/** @endpoint GET /v2/courses/{courseId}/ai -> ai_props */
export interface CourseAiProps {
  provider: 'ANTHROPIC'
  model: string
  /** The key itself never comes back; this and the last four characters are all a teacher sees. */
  api_key_configured: boolean
  api_key_hint: string | null
}

/**
 * The provider block is null when AI is off; everything else is the course's own and survives a
 * switch-off, so the dialog can show it either way.
 *
 * @endpoint GET /v2/courses/{courseId}/ai -> (root)
 */
export interface CourseAiSettings {
  ai_props: CourseAiProps | null
  /** Tokens (in + out); null means no limit. */
  token_budget: number | null
  /** Spent against the budget since `tokens_reset_at` (or ever, when that is null). */
  tokens_used: number
  tokens_reset_at: string | null
  /** The same spend split by direction, from the audit rows. Output costs several times input. */
  tokens_in_used: number
  tokens_out_used: number
  /** Longest solution, in characters, that gets an explanation. */
  max_solution_chars: number
  /** Admin-set; kept across a switch-off. */
  base_url: string | null
}

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
  // `suggested_code` is the discriminator: present means a suggestion, absent means a plain
  // comment. There was a `type: 'comment' | 'suggestion'` here too, restating it — core accepted
  // any string in that field, and EZ-1777 ended by removing it rather than validating it.
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
  /** Whether *this* teacher has opened it. Core answers per caller; see `flagged` for the shared mark. */
  seen: boolean
  flagged: boolean
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
  /** EZ-1712. AI explanations one student may get on this exercise; 0 = off. */
  ai_explanations_per_student: number
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
  /** Whether *this* teacher has opened it. Core answers per caller; see `flagged` for the shared mark. */
  seen: boolean
  flagged: boolean
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
// optionally across several courses. Both scores are percentages from JPlag, which compares language
// tokens rather than text: `similarity` is its average and `max_similarity` the higher of the two
// one-sided scores, so a high max over a low average means a short solution was pasted into a longer
// one. Renaming variables does not lower either — except on an exercise core has no tokeniser for,
// where `language` comes back as "text" and the comparison is word-level.

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> submissions[] */
export interface SimilarSubmissionResp {
  id: string
  created_at: string
  /**
   * Null for a member of an identical group that no pair names and that is not the group's first: its
   * code is that one's but for layout, and only that one is shown.
   */
  solution: string | null
  given_name: string
  family_name: string
  course_title: string
  /**
   * Who handed it in. The page's own list names each student's latest submission, which with the first
   * attempts compared these are not. Absent from an older core.
   */
  student_id?: string
  /** Which of the student's attempts this is, from 1. Absent from an older core. */
  number?: number
}

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> scores[].matches[] */
export interface SimilarityMatchResp {
  start_line_1: number
  end_line_1: number
  start_line_2: number
  end_line_2: number
  /** How many tokens this region covers — the length JPlag actually matched on. */
  tokens: number
}

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> scores[] */
export interface SimilarityScoreResp {
  sub_1: string
  sub_2: string
  /**
   * JPlag average similarity, 0–100. For Python it is token-based, so renaming variables does not
   * lower it; in text mode every word is its own token, so renaming does.
   */
  similarity: number
  /** JPlag maximum similarity, 0–100 — the shorter submission's share of matched tokens. */
  max_similarity: number
  matches: SimilarityMatchResp[]
  /**
   * What the provider measured, by name: `similarity`, `max_similarity` and `shared_tokens` for JPlag,
   * `levenshtein` and `dice` for edit distance. Optional because a core older than providers sends none.
   */
  metrics?: SimilarityMetricResp[]
}

/**
 * One named measurement of a pair: a whole percent for unit `percent`, a number for unit `count`.
 * @endpoint POST /v2/exercises/{exerciseId}/similarity -> scores[].metrics[]
 */
export interface SimilarityMetricResp {
  id: string
  value: number
  unit: string
}

/**
 * A submission the comparison could not use: CANNOT_PARSE (tokenisation failed), TOO_SMALL (fewer
 * tokens than the shortest match it will look for), TOO_LARGE (above core's token cap, so never
 * parsed), TOO_COMPLEX (nested so deeply the parser would take minutes) or TOO_REPETITIVE (repeats
 * itself past the point where one pair could be compared in bounded time). Core also maps EMPTY and
 * UNUSABLE, which cannot occur today.
 *
 * @endpoint POST /v2/exercises/{exerciseId}/similarity -> skipped[]
 */
export interface SimilaritySkippedResp {
  id: string
  reason: string
}

/**
 * The body of a similarity check, and of its estimate (EZ-1667). `students` are who it is about: which of
 * each one's submissions is compared, the latest or the first, is the course's setting, and core answers
 * it. `course_exercise_id` is the one the page lists, of an exercise that can be on a course twice.
 * `submissions` are the same students' latest, for a core older than `students`, which ignores those and
 * reads these, so the group filter holds against it too; a newer core reads the students, not these.
 *
 * @requestBody POST /v2/exercises/{exerciseId}/similarity
 * @requestBody POST /v2/exercises/{exerciseId}/similarity/estimate
 */
export interface SimilarityReq {
  courses: { id: string }[]
  course_exercise_id?: string
  students?: { id: string }[]
  submissions?: { id: string }[]
}

/** @endpoint POST /v2/exercises/{exerciseId}/similarity -> (root) */
export interface SimilarityResp {
  submissions: SimilarSubmissionResp[]
  /** How many submissions were compared, including ones no pair references. */
  compared: number
  /**
   * Core returns the 100 pairs sharing the most code together with the 100 highest by `max_similarity`,
   * so up to 200, listed most shared code first — not by `similarity`, which would bury a short solution
   * pasted whole into a long one, and not by the percentage alone, by which a short stub inside every
   * solution outranked real copies.
   */
  scores: SimilarityScoreResp[]
  /**
   * Which tokeniser ran: "python", "java", "sql", or "text" where the exercise mapped to none. A string
   * rather than a union, since a newer core may add one; see similarityLanguage.ts for what the page
   * makes of each, including one it does not know.
   */
  language: string
  /** Submissions that were dropped before comparison, so an empty result cannot mislead. */
  skipped: SimilaritySkippedResp[]
  /** True when more pairs existed than were returned. */
  truncated: boolean
  /**
   * Identical submissions by three or more authors, one entry each instead of every pair among them.
   * `scores` holds none between a group's members, and names one member for the group against anyone
   * else. Optional because a core older than groups, which a deploy keeps answering for a while, sends
   * none.
   */
  groups?: SimilarityGroupResp[]
  /**
   * Which provider measured the scores: `jplag` compares tokens, so renaming does not hide a copy;
   * `edit-distance` compares whole texts. Optional because a core older than providers sends none.
   */
  provider?: string
  /** `full` or `optimised`: the course's setting, or the default. */
  mode?: string
  /**
   * Why the comparison did not run as asked, or null when it did: `PINNED_UNAVAILABLE` (the course's
   * pinned provider may not compare this language now) or `LANGUAGE_UNAVAILABLE` (compared as text).
   */
  provider_fallback?: string | null
  /**
   * Whether `scores[].matches` says where each pair matches. True, an empty list means the pair shares
   * nothing; false (edit distance), matches are never sent and the page finds the shared code itself.
   * Optional because a core older than providers sends none, and its matches were JPlag's.
   */
  match_ranges?: boolean
  /**
   * Pairs worth comparing - a student's own left out, identical submissions once - and how many were
   * scored: fewer when an optimised run ruled some out. Optional, as an older core sends neither.
   */
  pairs_total?: number
  pairs_compared?: number
  /**
   * False when an optimised run stopped at the time limit with pairs left: `scores` are the best of the
   * pairs it reached, the likeliest first, and a more similar pair may be among the rest.
   */
  complete?: boolean
  /**
   * `latest` or `first`: which of each student's submissions were compared - or `named`, for a request
   * that named the submissions themselves. Absent from an older core.
   */
  attempts?: string
}

/**
 * What a similarity check with the same body would do, priced from the submissions' sizes without
 * running it. `within_budget` false means the check would be refused as SIMILARITY_TOO_COSTLY.
 *
 * @endpoint POST /v2/exercises/{exerciseId}/similarity/estimate -> (root)
 */
export interface SimilarityEstimateResp {
  submissions: number
  /** Pairs it would score: identical texts once, none between one student's own submissions. */
  pairs: number
  provider: string
  language: string
  mode: string
  provider_fallback: string | null
  /** About how long, in whole seconds rounded up. */
  estimated_sec: number
  budget_sec: number
  within_budget: boolean
  /**
   * Whether it would run optimised: `estimated_sec` is then the most it can take, and over the budget it
   * is not refused but stops there with the likeliest pairs found.
   */
  optimised: boolean
  /**
   * Where the provider choice and the mode came from: `exercise` (its own), `course` or `default`. Absent
   * from an older core.
   */
  provider_from?: string
  mode_from?: string
  /**
   * `latest` or `first`: which of each student's submissions it would compare, and where that came from -
   * or `named` and `request`, for a request that named the submissions themselves.
   */
  attempts?: string
  attempts_from?: string
}

/**
 * A course's similarity settings: the provider it pinned, or null for the admin's order, and the mode.
 * `providers` are those an admin has on, in the order a language is offered to them.
 *
 * @endpoint GET /v2/courses/{courseId}/similarity-settings -> (root)
 */
export interface CourseSimilaritySettings {
  provider: string | null
  mode: string
  /** `latest` or `first`. Absent from an older core. */
  attempts?: string
  providers: SimilarityProviderOption[]
  /** The course exercises with settings of their own. Absent from an older core. */
  exercises?: CourseExerciseSimilaritySettings[]
}

/**
 * One course exercise's own settings, over the course's: with `own_provider` false the course's provider
 * applies, with it true `provider` does (null being the admin's order); a null `mode` is the course's.
 *
 * @endpoint GET /v2/courses/{courseId}/similarity-settings -> exercises[]
 */
export interface CourseExerciseSimilaritySettings {
  course_exercise_id: string
  own_provider: boolean
  provider: string | null
  mode: string | null
  /** `latest`, `first`, or null for the course's. */
  attempts?: string | null
}

/**
 * A provider a course may pin, with the languages it compares there now.
 * @endpoint GET /v2/courses/{courseId}/similarity-settings -> providers[]
 */
export interface SimilarityProviderOption {
  id: string
  languages: string[]
}

/**
 * Every installed similarity provider as an admin has left it, in the order a language is offered to
 * them, and which of them compares each language now.
 *
 * @endpoint GET /v2/admin/similarity-providers -> (root)
 */
export interface AdminSimilarityProviders {
  providers: AdminSimilarityProvider[]
  languages: AdminSimilarityLanguage[]
}

/** @endpoint GET /v2/admin/similarity-providers -> providers[] */
export interface AdminSimilarityProvider {
  id: string
  enabled: boolean
  /** Higher is offered a language first; equal priorities go by id. */
  priority: number
  default_priority: number
  /** Whether an admin has set it at all, rather than leaving the provider's own defaults. */
  overridden: boolean
  languages: AdminSimilarityProviderLanguage[]
}

/** @endpoint GET /v2/admin/similarity-providers -> providers[].languages[] */
export interface AdminSimilarityProviderLanguage {
  language: string
  /** `stable` (on unless switched off) or `experimental` (off unless switched on). */
  support: string
  enabled: boolean
  overridden: boolean
}

/** @endpoint GET /v2/admin/similarity-providers -> languages[] */
export interface AdminSimilarityLanguage {
  language: string
  /** Off refuses the language outright, whatever a provider could do with it. */
  enabled: boolean
  /** The provider that compares it now, or null when none may. */
  provider: string | null
  /** What it is compared as - itself, or `text` where that provider falls back. */
  compared_as: string | null
}

/** @requestBody PUT /v2/admin/similarity-providers/{providerId} */
export interface AdminSimilarityProviderReq {
  enabled: boolean
  priority: number
  /** The provider's language overrides, replacing the ones before; a language left out goes back to its default. */
  languages: { language: string; enabled: boolean }[]
}

/** @requestBody PUT /v2/admin/similarity-languages/{language} */
export interface AdminSimilarityLanguageReq {
  enabled: boolean
}

/** @requestBody PUT /v2/courses/{courseId}/similarity-settings */
export interface CourseSimilaritySettingsReq {
  provider: string | null
  mode: string
  attempts: string
}

/** @requestBody PUT /v2/courses/{courseId}/similarity-settings/exercises/{courseExerciseId} */
export interface CourseExerciseSimilaritySettingsReq {
  own_provider: boolean
  provider: string | null
  mode: string | null
  attempts: string | null
}

/**
 * Submissions that are the same text but for layout, and so the same tokens — each group's ids in id
 * order.
 *
 * @endpoint POST /v2/exercises/{exerciseId}/similarity -> groups[]
 */
export interface SimilarityGroupResp {
  submissions: string[]
}
