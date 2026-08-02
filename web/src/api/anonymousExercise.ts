import { useMutation, useQuery } from '@tanstack/react-query'
import { apiFetch } from './client.ts'

/**
 * The two endpoints the embedded exercise view uses.
 *
 * Both are `permitAll()` in `SecurityConf` and both are called with `noAuth`, because an exercise
 * embedded in someone else's page has no user and must not go looking for one — sending a stale
 * bearer token from an unrelated Lahendus session would be worse than sending none.
 */

export interface AnonymousExerciseDetails {
  title: string
  text_html: string | null
  /** Empty string when there is no template; the column is non-nullable. */
  anonymous_autoassess_template: string
  /** False for teacher-graded exercises: there is nothing to submit to. */
  submit_allowed: boolean
}

export interface AnonymousAutoassessResult {
  grade: number
  feedback: string
}

export function useAnonymousExercise(exerciseId: string | undefined) {
  return useQuery({
    queryKey: ['anonymous', 'exercise', exerciseId],
    queryFn: () =>
      apiFetch<AnonymousExerciseDetails>(`/unauth/exercises/${exerciseId}/anonymous/details`, {
        noAuth: true,
      }),
    enabled: !!exerciseId,
    // Embeds sit on pages that stay open for a long time; nothing here changes minute to minute.
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
}

export function useAnonymousAutoassess(exerciseId: string | undefined) {
  return useMutation({
    mutationFn: (solution: string) =>
      apiFetch<AnonymousAutoassessResult>(
        `/unauth/exercises/${exerciseId}/anonymous/autoassess`,
        { method: 'POST', body: { solution }, noAuth: true },
      ),
  })
}
