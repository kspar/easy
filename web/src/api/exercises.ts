import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type {
  CourseExercise,
  CourseInviteResp,
  DraftResp,
  ExerciseDetails,
  GroupResp,
  InlineCommentResp,
  InlineCommentType,
  MoodlePropsResp,
  ParticipantsResp,
  SimilarityResp,
  SubmissionResp,
  SubmissionRow,
  TeacherActivityResp,
  TeacherAutoassessResp,
  TeacherCourseExercise,
  TeacherExerciseDetails,
  TeacherSubmissionDetailResp,
  TeacherSubmissionSummaryResp,
  TeacherTestSubmissionResp,
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

export interface CourseExercisePatch {
  replace?: {
    title_alias?: string
    threshold?: number
    soft_deadline?: string
    hard_deadline?: string
    student_visible?: boolean
    student_visible_from?: string
  }
  delete?: string[]
}

export function useUpdateCourseExercise(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CourseExercisePatch) =>
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

/** Same patch as useUpdateCourseExercise, but applied to several exercises at once. */
export function useUpdateCourseExercises(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      courseExerciseIds,
      ...body
    }: CourseExercisePatch & { courseExerciseIds: string[] }) =>
      Promise.all(
        courseExerciseIds.map((id) =>
          apiFetch(`/courses/${courseId}/exercises/${id}`, {
            method: 'PATCH',
            body,
          }),
        ),
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises'],
      })
    },
  })
}

export function useRemoveExercisesFromCourse(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (courseExerciseIds: string[]) =>
      Promise.all(
        courseExerciseIds.map((id) =>
          apiFetch(`/courses/${courseId}/exercises/${id}`, { method: 'DELETE' }),
        ),
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises'],
      })
    },
  })
}

export function useReorderCourseExercise(courseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    // newIndex is a position in the ordered list, matching ordering_idx
    mutationFn: ({
      courseExerciseId,
      newIndex,
    }: {
      courseExerciseId: string
      newIndex: number
    }) =>
      apiFetch(`/courses/${courseId}/exercises/${courseExerciseId}/reorder`, {
        method: 'POST',
        body: { new_index: newIndex },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises'],
      })
    },
  })
}

export function useTeacherCourseExercises(courseId: string | undefined, groupId?: string) {
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
    enabled: !!courseId,
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

// Teacher grading hooks

export function useTeacherSubmissionSummaries(
  courseId: string,
  courseExerciseId: string,
  groupId?: string,
) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId, 'submissions', 'latest', { groupId }],
    queryFn: () => {
      const url = groupId
        ? `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/latest/students?group=${groupId}`
        : `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/latest/students`
      return apiFetch<{ latest_submissions: SubmissionRow[] }>(url).then((r) => r.latest_submissions)
    },
    // Without this, a caller that does not know its course exercise yet — the similarity page before
    // an exercise is chosen — requests `/exercises//submissions/latest/students` on every render.
    enabled: !!courseExerciseId,
  })
}

export function useTeacherSubmissionDetails(
  courseId: string,
  courseExerciseId: string,
  submissionId: string | undefined,
) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId, 'submissions', submissionId],
    queryFn: () =>
      apiFetch<TeacherSubmissionDetailResp>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}`,
      ),
    enabled: !!submissionId,
  })
}

/**
 * Re-run auto-assessment on a submission a teacher is looking at.
 *
 * For the case where grading failed for a reason that had nothing to do with the student's code —
 * the executor was down, a container image was missing, a test timed out under load. The submission
 * is unchanged; only the assessment is redone.
 *
 * Two things about this endpoint that shape the UI around it:
 *
 * - **It blocks.** Core runs the assessment inside the request (`runBlocking { submitAndAwait }`),
 *   so the response arrives when grading finishes, not when it is queued. Seconds, sometimes longer
 *   under load. The caller needs a pending state, not a fire-and-forget.
 * - **A 200 does not mean grading succeeded.** Core catches assessment failures, records them as an
 *   auto-assess-failed activity and emails the sysadmin, then returns normally. So the outcome is in
 *   the refetched data rather than in the response, which is why this invalidates rather than
 *   reporting success on its own.
 *
 * Only valid on an `AUTO` exercise; core answers `EXERCISE_NOT_AUTOASSESSABLE` otherwise.
 */
export function useRetryAutoassess(courseId: string, courseExerciseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (submissionId: string) =>
      apiFetch(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/retry-autoassess`,
        { method: 'POST' },
      ),
    onSuccess: () => {
      // The whole exercise subtree: the assessment lands on the submission detail, the grade on the
      // summaries the list reads, and a new entry in the activity feed.
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function useTeacherStudentSubmissions(
  courseId: string,
  courseExerciseId: string,
  studentId: string | undefined,
) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId, 'submissions', 'all', 'students', studentId],
    queryFn: () =>
      apiFetch<{ submissions: TeacherSubmissionSummaryResp[] }>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/all/students/${studentId}`,
      ).then((r) => r.submissions),
    enabled: !!studentId,
  })
}

export function useTeacherStudentActivities(
  courseId: string,
  courseExerciseId: string,
  studentId: string | undefined,
) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId, 'students', studentId, 'activities'],
    queryFn: () =>
      apiFetch<{ teacher_activities: TeacherActivityResp[] }>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/students/${studentId}/activities`,
      ).then((r) => r.teacher_activities),
    enabled: !!studentId,
  })
}

export function usePostGrade(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ submissionId, grade, notifyStudent }: { submissionId: string; grade: number; notifyStudent: boolean }) =>
      apiFetch(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/grade`,
        { method: 'POST', body: { grade, notify_student: notifyStudent } },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function usePostFeedback(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ submissionId, feedbackMd, notifyStudent }: {
      submissionId: string
      feedbackMd: string | null
      notifyStudent: boolean
    }) =>
      apiFetch(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/feedback`,
        { method: 'POST', body: { feedback_md: feedbackMd, notify_student: notifyStudent } },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function useEditFeedback(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ submissionId, teacherActivityId, feedbackMd, notifyStudent }: {
      submissionId: string
      teacherActivityId: string
      feedbackMd: string | null
      notifyStudent: boolean
    }) =>
      apiFetch(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/feedback`,
        { method: 'PUT', body: { teacher_activity_id: teacherActivityId, feedback_md: feedbackMd, notify_student: notifyStudent } },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

// Inline comment hooks

export function useTeacherStudentInlineComments(
  courseId: string,
  courseExerciseId: string,
  studentId: string | undefined,
) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId, 'students', studentId, 'inline-comments'],
    queryFn: () =>
      apiFetch<{ inline_comments: InlineCommentResp[] }>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/students/${studentId}/inline-comments`,
      ).then((r) => r.inline_comments),
    enabled: !!studentId,
  })
}

export function useStudentInlineComments(
  courseId: string,
  courseExerciseId: string,
) {
  return useQuery({
    queryKey: ['student', 'courses', courseId, 'exercises', courseExerciseId, 'inline-comments'],
    queryFn: () =>
      apiFetch<{ inline_comments: InlineCommentResp[] }>(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/inline-comments`,
      ).then((r) => r.inline_comments),
  })
}

export function useCreateInlineComment(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ submissionId, ...body }: {
      submissionId: string
      line_start: number
      line_end: number
      code: string
      text_md: string
      type: InlineCommentType
      suggested_code?: string
      notify_student?: boolean
    }) =>
      apiFetch<InlineCommentResp>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/inline-comments`,
        { method: 'POST', body },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
        predicate: (query) => query.queryKey.includes('inline-comments'),
      })
    },
  })
}

export function useUpdateInlineComment(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ submissionId, commentId, ...body }: {
      submissionId: string
      commentId: string
      line_start: number
      line_end: number
      code: string
      text_md: string
      type: InlineCommentType
      suggested_code?: string
      notify_student?: boolean
    }) =>
      apiFetch<InlineCommentResp>(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/inline-comments/${commentId}`,
        { method: 'PUT', body },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
        predicate: (query) => query.queryKey.includes('inline-comments'),
      })
    },
  })
}

export function useDeleteInlineComment(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ submissionId, commentId }: { submissionId: string; commentId: string }) =>
      apiFetch(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/inline-comments/${commentId}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
        predicate: (query) => query.queryKey.includes('inline-comments'),
      })
    },
  })
}

export function useMarkSubmissionsSeen(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { submissions: { id: string }[]; seen: boolean }) =>
      apiFetch(
        `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/seen`,
        { method: 'POST', body },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises', courseExerciseId],
      })
    },
  })
}

export function useTeacherAutoassess(exerciseId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (solution: string) =>
      apiFetch<TeacherAutoassessResp>(
        `/exercises/${exerciseId}/testing/autoassess`,
        { method: 'POST', body: { solution } },
      ),
    // The server stores every test run, so the run just made is now part of the history the
    // testing tab reads back. Without this the count and the "last tested" time stay behind by
    // one run until something else refetches.
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['exercises', exerciseId, 'testing', 'autoassess', 'submissions'],
      })
    },
  })
}

export function useTeacherTestSubmissions(exerciseId: string | undefined) {
  return useQuery({
    queryKey: ['exercises', exerciseId, 'testing', 'autoassess', 'submissions'],
    queryFn: () =>
      apiFetch<{ submissions: TeacherTestSubmissionResp[] }>(
        `/exercises/${exerciseId}/testing/autoassess/submissions`,
      ).then((r) => r.submissions),
    enabled: !!exerciseId,
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

// Debounced markdown preview using the backend renderer
export function useMarkdownPreview(markdownText: string, debounceMs = 400): string {
  const [html, setHtml] = useState('')
  const lastRequestRef = useRef(0)

  useEffect(() => {
    const trimmed = markdownText.trim()
    if (!trimmed) {
      setHtml('')
      return
    }

    const requestId = ++lastRequestRef.current
    const timer = setTimeout(async () => {
      try {
        const resp = await apiFetch<{ content: string }>('/preview/markdown', {
          method: 'POST',
          body: { content: trimmed },
        })
        if (lastRequestRef.current === requestId) {
          setHtml(resp.content)
        }
      } catch {
        // ignore preview errors
      }
    }, debounceMs)

    return () => clearTimeout(timer)
  }, [markdownText, debounceMs])

  return html
}

/**
 * Compare submissions of one library exercise for similarity.
 *
 * A mutation rather than a query because it is expensive and explicit: the teacher asks for it, and
 * asking twice should mean running it twice. Core compares every pair — N submissions means
 * N(N-1)/2 comparisons, synchronously, inside the request — so it gets slow on a large course and can
 * time out. That is EZ-1667's open question, not something this hook can paper over; the page shows
 * the pair count up front so the wait is at least predictable.
 *
 * `exerciseId` is the *library* exercise id, not the course exercise id. `courses` scopes which
 * courses' submissions may be included, and `submissions` narrows it further — the page passes the
 * ids it got from the summaries query, which is how the group filter is applied.
 */
export function useCheckSimilarity(exerciseId: string | undefined) {
  return useMutation({
    mutationFn: ({ courseIds, submissionIds }: { courseIds: string[]; submissionIds: string[] }) =>
      apiFetch<SimilarityResp>(`/exercises/${exerciseId}/similarity`, {
        method: 'POST',
        body: {
          courses: courseIds.map((id) => ({ id })),
          submissions: submissionIds.map((id) => ({ id })),
        },
      }),
  })
}
