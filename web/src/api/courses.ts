import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type { StudentCourse, TeacherCourse } from './types.ts'

export function useStudentCourses() {
  return useQuery({
    queryKey: ['student', 'courses'],
    queryFn: () =>
      apiFetch<{ courses: StudentCourse[] }>('/student/courses').then(
        (r) => r.courses.sort((a, b) =>
          new Date(b.last_accessed).getTime() - new Date(a.last_accessed).getTime(),
        ),
      ),
  })
}

export function useTeacherCourses() {
  return useQuery({
    queryKey: ['teacher', 'courses'],
    queryFn: () =>
      apiFetch<{ courses: TeacherCourse[] }>('/teacher/courses').then(
        (r) => r.courses.sort((a, b) =>
          new Date(b.last_accessed).getTime() - new Date(a.last_accessed).getTime(),
        ),
      ),
  })
}

export function useUpdateLastAccess(role: 'student' | 'teacher' | 'admin', courseId: string) {
  const prefix = role === 'student' ? 'student' : 'teacher'
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch(`/${prefix}/courses/${courseId}/access`, { method: 'POST' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [prefix, 'courses'] })
    },
  })
}
