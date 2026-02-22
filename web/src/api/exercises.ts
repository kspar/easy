import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type {
  CourseExercise,
  CourseInviteResp,
  DraftResp,
  ExerciseDetails,
  GroupResp,
  MoodlePropsResp,
  ParticipantsResp,
  SubmissionResp,
  TeacherActivityResp,
  TeacherCourseExercise,
  TeacherExerciseDetails,
} from './types.ts'

export function useCourseExercises(courseId: string | undefined) {
  return useQuery({
    queryKey: ['student', 'courses', courseId, 'exercises'],
    queryFn: () =>
      apiFetch<{ exercises: CourseExercise[] }>(
        `/student/courses/${courseId}/exercises`,
      ).then((r) => r.exercises),
    enabled: !!courseId,
  })
}

export function useExerciseDetails(
  courseId: string,
  courseExerciseId: string,
) {
  return useQuery({
    queryKey: ['student', 'courses', courseId, 'exercises', courseExerciseId],
    queryFn: () =>
      apiFetch<ExerciseDetails>(
        `/student/courses/${courseId}/exercises/${courseExerciseId}`,
      ),
  })
}

export function useSubmissions(
  courseId: string,
  courseExerciseId: string,
) {
  return useQuery({
    queryKey: [
      'student',
      'courses',
      courseId,
      'exercises',
      courseExerciseId,
      'submissions',
    ],
    queryFn: () =>
      apiFetch<{ submissions: SubmissionResp[] }>(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/submissions/all`,
      ).then((r) => r.submissions),
  })
}

export function useDraft(courseId: string, courseExerciseId: string) {
  return useQuery({
    queryKey: [
      'student',
      'courses',
      courseId,
      'exercises',
      courseExerciseId,
      'draft',
    ],
    queryFn: () =>
      apiFetch<DraftResp | undefined>(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/draft`,
      ),
  })
}

export function useSubmitSolution(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (solution: string) =>
      apiFetch(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/submissions`,
        { method: 'POST', body: { solution } },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [
          'student',
          'courses',
          courseId,
          'exercises',
          courseExerciseId,
          'submissions',
        ],
      })
      queryClient.invalidateQueries({
        queryKey: ['student', 'courses', courseId, 'exercises'],
      })
    },
  })
}

export function useSaveDraft(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (solution: string) =>
      apiFetch(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/draft`,
        { method: 'POST', body: { solution } },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [
          'student',
          'courses',
          courseId,
          'exercises',
          courseExerciseId,
          'draft',
        ],
      })
    },
  })
}

export function useTeacherActivities(
  courseId: string,
  courseExerciseId: string,
) {
  return useQuery({
    queryKey: [
      'student',
      'courses',
      courseId,
      'exercises',
      courseExerciseId,
      'activities',
    ],
    queryFn: () =>
      apiFetch<{ teacher_activities: TeacherActivityResp[] }>(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/activities`,
      ).then((r) => r.teacher_activities),
  })
}

// Teacher hooks

export function useTeacherExerciseDetails(
  courseId: string,
  courseExerciseId: string,
) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
    queryFn: () =>
      apiFetch<TeacherExerciseDetails>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}`,
      ),
  })
}

export function useUpdateCourseExercise(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      replace?: {
        title_alias?: string
        threshold?: number
        soft_deadline?: string
        hard_deadline?: string
        student_visible?: boolean
        student_visible_from?: string
      }
      delete?: string[]
    }) =>
      apiFetch(`/courses/${courseId}/exercises/${courseExerciseId}`, {
        method: 'PATCH',
        body,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises'],
      })
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function useTeacherCourseExercises(courseId: string, groupId?: string) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', { groupId }],
    queryFn: () => {
      const url = groupId
        ? `/teacher/courses/${courseId}/exercises?group=${groupId}`
        : `/teacher/courses/${courseId}/exercises`
      return apiFetch<{ exercises: TeacherCourseExercise[] }>(url).then(
        (r) => r.exercises,
      )
    },
  })
}

export function useParticipants(courseId: string) {
  return useQuery({
    queryKey: ['courses', courseId, 'participants'],
    queryFn: () =>
      apiFetch<ParticipantsResp>(`/courses/${courseId}/participants`),
  })
}

export function useCourseGroups(courseId: string) {
  return useQuery({
    queryKey: ['courses', courseId, 'groups'],
    queryFn: () =>
      apiFetch<{ groups: GroupResp[] }>(`/courses/${courseId}/groups`).then(
        (r) => r.groups,
      ),
  })
}

export function useCourseInvite(courseId: string) {
  return useQuery({
    queryKey: ['courses', courseId, 'invite'],
    queryFn: () =>
      apiFetch<CourseInviteResp | null>(`/courses/${courseId}/invite`).then(
        (r) => r ?? null,
      ),
  })
}

export function useCreateInvite(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { expires_at: string; allowed_uses: number }) =>
      apiFetch<{ invite_id: string }>(`/courses/${courseId}/invite`, {
        method: 'PUT',
        body,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'invite'],
      })
    },
  })
}

export function useDeleteInvite(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch(`/courses/${courseId}/invite`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'invite'],
      })
    },
  })
}

export function usePutExerciseExceptions(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      exception_students?: {
        student_id: string
        soft_deadline?: { value: string | null } | null
        hard_deadline?: { value: string | null } | null
        student_visible_from?: { value: string | null } | null
      }[]
      exception_groups?: {
        group_id: number
        soft_deadline?: { value: string | null } | null
        hard_deadline?: { value: string | null } | null
        student_visible_from?: { value: string | null } | null
      }[]
    }) =>
      apiFetch(
        `/courses/${courseId}/exercises/${courseExerciseId}/exception`,
        { method: 'PUT', body },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function useDeleteExerciseExceptions(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      exception_students?: string[]
      exception_groups?: number[]
    }) =>
      apiFetch(
        `/courses/${courseId}/exercises/${courseExerciseId}/exception`,
        { method: 'DELETE', body },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function useAwaitAutograde(
  courseId: string,
  courseExerciseId: string,
) {
  return useMutation({
    mutationFn: () =>
      apiFetch(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/submissions/latest/await`,
      ),
    // No cache invalidation here — the caller (CourseExercisePage) controls
    // when submissions + exercises queries update to coordinate with the
    // autograde reveal animation.
  })
}

// Moodle invite hooks

export function useSendMoodleInvites(courseId: string) {
  return useMutation({
    mutationFn: (moodleUsernames: string[]) =>
      apiFetch(`/courses/moodle/${courseId}/students/invite`, {
        method: 'POST',
        body: { students: moodleUsernames },
      }),
  })
}

// Participant management hooks

export function useAddTeachers(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (teachers: { email: string }[]) =>
      apiFetch<{ accesses_added: number }>(
        `/courses/${courseId}/teachers`,
        { method: 'POST', body: { teachers } },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

export function useRemoveTeachers(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (teacherIds: string[]) =>
      apiFetch(`/courses/${courseId}/teachers`, {
        method: 'DELETE',
        body: { teachers: teacherIds.map((id) => ({ id })) },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

export function useRemoveStudents(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (studentIds: string[]) =>
      apiFetch<{ removed_active_count: number }>(
        `/courses/${courseId}/students`,
        {
          method: 'DELETE',
          body: { active_students: studentIds.map((id) => ({ id })) },
        },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'groups'],
      })
    },
  })
}

export function useCreateGroup(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) =>
      apiFetch<{ id: string }>(`/courses/${courseId}/groups`, {
        method: 'POST',
        body: { name },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'groups'],
      })
    },
  })
}

export function useDeleteGroup(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (groupId: string) =>
      apiFetch(`/courses/${courseId}/groups/${groupId}`, {
        method: 'DELETE',
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'groups'],
      })
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

export function useDeleteGroups(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (groupIds: string[]) =>
      Promise.all(
        groupIds.map((id) =>
          apiFetch(`/courses/${courseId}/groups/${id}`, { method: 'DELETE' }),
        ),
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'groups'],
      })
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

export function useAddStudentsToGroup(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      groupId,
      activeStudentIds,
      moodlePendingUsernames,
    }: {
      groupId: string
      activeStudentIds: string[]
      moodlePendingUsernames: string[]
    }) =>
      apiFetch(`/courses/${courseId}/groups/${groupId}/students`, {
        method: 'POST',
        body: {
          active_students: activeStudentIds.map((id) => ({ id })),
          moodle_pending_students: moodlePendingUsernames.map((u) => ({
            moodle_username: u,
          })),
        },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

export function useRemoveStudentFromGroup(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      groupId,
      activeStudentIds,
      moodlePendingUsernames,
    }: {
      groupId: string
      activeStudentIds: string[]
      moodlePendingUsernames: string[]
    }) =>
      apiFetch(`/courses/${courseId}/groups/${groupId}/students`, {
        method: 'DELETE',
        body: {
          active_students: activeStudentIds.map((id) => ({ id })),
          moodle_pending_students: moodlePendingUsernames.map((u) => ({
            moodle_username: u,
          })),
        },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

// Moodle hooks

export function useMoodleProps(courseId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['courses', courseId, 'moodle'],
    queryFn: () => apiFetch<MoodlePropsResp>(`/courses/${courseId}/moodle`),
    enabled,
  })
}

export function useSyncMoodleStudents(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<{ status: string }>(`/courses/${courseId}/moodle/students`, {
        method: 'POST',
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'moodle'],
      })
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}

export function useSyncMoodleGrades(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<{ status: string }>(`/courses/${courseId}/moodle/grades`, {
        method: 'POST',
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'moodle'],
      })
    },
  })
}

export function useUpdateMoodleProps(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      moodle_props: {
        moodle_short_name: string
        sync_students: boolean
        sync_grades: boolean
      } | null
    }) =>
      apiFetch(`/courses/${courseId}/moodle`, {
        method: 'PUT',
        body,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'moodle'],
      })
      queryClient.invalidateQueries({
        queryKey: ['courses', courseId, 'participants'],
      })
    },
  })
}
