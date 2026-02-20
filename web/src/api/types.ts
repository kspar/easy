// Course types

export interface StudentCourse {
  id: string
  title: string
  alias: string | null
  course_code: string | null
  archived: boolean
  last_accessed: string
  color: string
}

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

export interface GradeResp {
  grade: number
  is_autograde: boolean
  is_graded_directly: boolean
}

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

export interface AutomaticAssessmentResp {
  grade: number
  feedback: string | null
}

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

export interface DraftResp {
  solution: string
  created_at: string
}

// Teacher activity types (student view)

export interface TeacherResp {
  id: string
  given_name: string
  family_name: string
}

export interface FeedbackResp {
  feedback_html: string
  feedback_adoc: string
}

export interface TeacherActivityResp {
  id: string
  submission_id: string
  submission_number: number
  created_at: string
  grade: number | null
  edited_at: string | null
  feedback: FeedbackResp | null
  teacher: TeacherResp
}

// Teacher exercise types

export interface GroupResp {
  id: string
  name: string
}

export interface LatestSubmissionResp {
  id: string
  submission_number: number
  time: string
  grade: GradeResp | null
  seen: boolean
}

export interface SubmissionRow {
  submission: LatestSubmissionResp | null
  status: StudentExerciseStatus
  student_id: string
  given_name: string
  family_name: string
  groups: GroupResp[]
}

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

export interface ExceptionValue {
  value: string | null
}

export interface ExceptionStudent {
  student_id: string
  soft_deadline: ExceptionValue | null
  hard_deadline: ExceptionValue | null
  student_visible_from: ExceptionValue | null
}

export interface ExceptionGroup {
  group_id: number
  soft_deadline: ExceptionValue | null
  hard_deadline: ExceptionValue | null
  student_visible_from: ExceptionValue | null
}

export interface TeacherExerciseDetails {
  exercise_id: string
  title: string
  title_alias: string | null
  text_html: string | null
  instructions_html: string | null
  soft_deadline: string | null
  hard_deadline: string | null
  grader_type: GraderType
  solution_file_name: string
  solution_file_type: SolutionFileType
  threshold: number
  student_visible: boolean
  student_visible_from: string | null
  has_lib_access: boolean
  exception_students: ExceptionStudent[] | null
  exception_groups: ExceptionGroup[] | null
}

// Participants types

export interface StudentParticipant {
  id: string
  email: string
  given_name: string
  family_name: string
  created_at: string | null
  groups: GroupResp[]
}

export interface TeacherParticipant {
  id: string
  email: string
  given_name: string
  family_name: string
  created_at: string | null
}

export interface MoodlePendingStudent {
  moodle_username: string
  email: string
  invite_id: string
  groups: GroupResp[]
}

export interface ParticipantsResp {
  students: StudentParticipant[] | null
  teachers: TeacherParticipant[] | null
  students_moodle_pending: MoodlePendingStudent[] | null
  moodle_linked: boolean
}

export interface MoodlePropsResp {
  moodle_props: {
    moodle_short_name: string
    students_synced: boolean
    sync_students_in_progress: boolean
    grades_synced: boolean
    sync_grades_in_progress: boolean
  } | null
}

export interface CourseInviteResp {
  invite_id: string
  expires_at: string
  created_at: string
  allowed_uses: number
  used_count: number
  uses_remaining: number
}
