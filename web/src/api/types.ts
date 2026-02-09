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
