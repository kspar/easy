import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type { StudentCourse, TeacherCourse } from './types.ts'

export function useStudentCourses(enabled = true) {
  return useQuery({
    queryKey: ['student', 'courses'],
    queryFn: () =>
      apiFetch<{ courses: StudentCourse[] }>('/student/courses').then(
        (r) => r.courses.sort((a, b) =>
          new Date(b.last_accessed).getTime() - new Date(a.last_accessed).getTime(),
        ),
      ),
    enabled,
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

/**
 * Course info behind a join link. Errors (400) if the invite is invalid, expired or used up.
 * Moodle-linked courses have a separate, per-student invite.
 */
export function useCourseByInvite(inviteId: string, isMoodle: boolean, enabled = true) {
  return useQuery({
    queryKey: ['invite', isMoodle ? 'moodle' : 'course', inviteId],
    queryFn: () =>
      apiFetch<{ course_id: string; course_title: string }>(
        `/courses/${isMoodle ? 'moodle/' : ''}invite/${encodeURIComponent(inviteId)}`,
      ),
    enabled: enabled && !!inviteId,
    retry: false,
    staleTime: 0,
  })
}

export function useJoinByInvite(inviteId: string, isMoodle: boolean) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<{ course_id: string }>(
        `/courses/${isMoodle ? 'moodle/' : ''}join/${encodeURIComponent(inviteId)}`,
        { method: 'POST' },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['student', 'courses'] })
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
