import { useQuery } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type { StudentCourse, TeacherCourse } from './types.ts'

export function useStudentCourses() {
  return useQuery({
    queryKey: ['student', 'courses'],
    queryFn: () =>
      apiFetch<{ courses: StudentCourse[] }>('/student/courses').then(
        (r) => r.courses,
      ),
  })
}

export function useTeacherCourses() {
  return useQuery({
    queryKey: ['teacher', 'courses'],
    queryFn: () =>
      apiFetch<{ courses: TeacherCourse[] }>('/teacher/courses').then(
        (r) => r.courses,
      ),
  })
}
