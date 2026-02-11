// Course types

export interface StudentCourse {
  id: string
  title: string
  alias: string | null
  archived: boolean
  last_accessed: string
}

export interface TeacherCourse {
  id: string
  title: string
  alias: string | null
  archived: boolean
  student_count: number
  last_accessed: string
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

export interface ParticipantsResp {
  students: StudentParticipant[] | null
  teachers: TeacherParticipant[] | null
}
