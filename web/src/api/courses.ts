import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type { CourseAiSettings, StudentCourse, TeacherCourse } from './types.ts'

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
      apiFetch<{
        title: string
        alias: string | null
        archived: boolean
        color: string
        course_code: string | null
        /**
         * The course's page in Moodle, or null when there is nothing to link to (EZ-1874) — the
         * course is not Moodle-linked, or this environment has no Moodle configured. Already a
         * finished, encoded URL: nothing here builds one.
         */
        moodle_course_url: string | null
      }>(`/courses/${courseId}/basic`),
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

/** EZ-1711. What the course has configured, key withheld — see `CourseAiSettings`. */
export function useCourseAiProps(courseId: string, enabled = true) {
  return useQuery({
    queryKey: ['courses', courseId, 'ai'],
    queryFn: () =>
      // `ai_props ?? null`, because react-query treats `undefined` as "the query function forgot
      // to return" and a `{}` body from a stub, or from a proxy that ate the response, would
      // otherwise produce exactly that one level down.
      apiFetch<CourseAiSettings>(`/courses/${courseId}/ai`).then((r) => ({ ...r, ai_props: r.ai_props ?? null })),
    enabled,
  })
}

/**
 * EZ-1711. `ai_props: null` switches AI features off for the course. `api_key: null` on a write
 * keeps the stored key, which is the only way to edit the model without retyping the key core will
 * never show again.
 */
export function useUpdateCourseAiProps(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      ai_props: {
        provider: 'ANTHROPIC'
        model: string
        /** Absent keeps the stored URL, `''` clears it, anything else sets it. Admin-only either way. */
        base_url?: string
        api_key: string | null
        token_budget: number | null
        max_solution_chars: number
      } | null
    }) => apiFetch(`/courses/${courseId}/ai`, { method: 'PUT', body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['courses', courseId, 'ai'] })
      // The student page reads `ai_feedback_enabled` from the exercise details.
      queryClient.invalidateQueries({ queryKey: ['student', 'courses', courseId, 'exercises'] })
    },
  })
}

/** EZ-1712. Zero the course's token counter. The budget and the audit rows stay. */
export function useResetCourseAiUsage(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch(`/courses/${courseId}/ai/reset-usage`, { method: 'POST' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['courses', courseId, 'ai'] })
      queryClient.invalidateQueries({ queryKey: ['student', 'courses', courseId, 'exercises'] })
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
