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

export function useCreateCourse() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { title: string; color: string; course_code?: string }) =>
      apiFetch<{ id: string }>('/admin/courses', { method: 'POST', body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teacher', 'courses'] })
    },
  })
}

export function useCourse(courseId: string | undefined) {
  return useQuery({
    queryKey: ['course', courseId],
    queryFn: () =>
      apiFetch<{ title: string; alias: string | null; archived: boolean; color: string; course_code: string | null }>(
        `/courses/${courseId}/basic`,
      ),
    enabled: !!courseId,
  })
}

export function useUpdateCourse(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { title: string; alias: string | null; color: string; course_code: string | null }) =>
      apiFetch(`/courses/${courseId}`, { method: 'PUT', body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teacher', 'courses'] })
      queryClient.invalidateQueries({ queryKey: ['course', courseId] })
    },
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
