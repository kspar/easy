import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import type {
  CourseExercise,
  DraftResp,
  ExerciseDetails,
  ParticipantsResp,
  SubmissionResp,
  TeacherActivityResp,
  TeacherCourseExercise,
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

export function useTeacherCourseExercises(courseId: string) {
  return useQuery({
    queryKey: ['teacher', 'courses', courseId, 'exercises'],
    queryFn: () =>
      apiFetch<{ exercises: TeacherCourseExercise[] }>(
        `/teacher/courses/${courseId}/exercises`,
      ).then((r) => r.exercises),
  })
}

export function useParticipants(courseId: string) {
  return useQuery({
    queryKey: ['courses', courseId, 'participants'],
    queryFn: () =>
      apiFetch<ParticipantsResp>(`/courses/${courseId}/participants`),
  })
}

export function useAwaitAutograde(
  courseId: string,
  courseExerciseId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch(
        `/student/courses/${courseId}/exercises/${courseExerciseId}/submissions/latest/await`,
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
