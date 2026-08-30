import type { StudentExerciseStatus } from '../api/types.ts'

/**
 * The non-component half of [ExerciseStatusIcon].
 *
 * Split out for the same reason `auth-context` is split from `AuthContext`: `react-refresh` refuses
 * a module that exports constants or helpers alongside a component, and both of these are wanted by
 * callers that render the chip rather than the icon.
 */

/** The `exercises.*` key naming each status, shared by the icon and the chip beside it. */
export const STATUS_LABEL_KEY = {
  COMPLETED: 'exercises.completed',
  STARTED: 'exercises.started',
  UNGRADED: 'exercises.ungraded',
  UNSTARTED: 'exercises.unstarted',
} as const

export function statusColor(
  status: StudentExerciseStatus,
): 'success' | 'warning' | 'info' | 'default' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'STARTED':
      return 'warning'
    case 'UNGRADED':
      return 'info'
    case 'UNSTARTED':
      return 'default'
  }
}
