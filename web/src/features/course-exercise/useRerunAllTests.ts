import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client.ts'

export interface RerunProgress {
  running: boolean
  /** How many submissions the run was started with. Stays put after it ends, for the summary. */
  total: number
  /** Submissions the run has been through, successful or not. What the progress bar counts. */
  done: number
  /** How many of those `done` came back an error. */
  failed: number
  /** The one being graded right now, so the list can mark that row. */
  currentSubmissionId: string | null
  /** True once a run has ended — however it ended — until the next `start` or a `dismiss`. */
  finished: boolean
  cancelled: boolean
  /** Ended itself after too many failures in a row. See [CONSECUTIVE_FAILURE_LIMIT]. */
  gaveUp: boolean
}

const IDLE: RerunProgress = {
  running: false,
  total: 0,
  done: 0,
  failed: 0,
  currentSubmissionId: null,
  finished: false,
  cancelled: false,
  gaveUp: false,
}

/**
 * Failures in a row before the run stops by itself.
 *
 * Not a nicety. The failures this loop will actually meet are systemic rather than per-student — the
 * executor is down, a container image is missing, the session's token expired — and every one of
 * them applies equally to everybody still in the queue. Without a limit, a course of two hundred
 * gets two hundred doomed POSTs; and for an expired session `apiFetch` runs the app's whole
 * not-authenticated recovery on each one, so the page tries to bounce the teacher to the IdP two
 * hundred times while the loop grinds on. Three is enough to tell a systemic failure from one
 * student whose code hangs the grader.
 */
const CONSECUTIVE_FAILURE_LIMIT = 3

/**
 * Re-run the auto-assessment of many submissions on one course exercise, one after another.
 *
 * There is no bulk endpoint, and this deliberately does not add one. Core's per-submission
 * `retry-autoassess` grades *inside the request* (`runBlocking { submitAndAwait }`), so a server-side
 * loop over a course would be a single HTTP request held open for as long as it takes to grade every
 * student — minutes, past any proxy's patience — and it would report nothing until it was over. Run
 * from here, each submission is its own short-lived request, the teacher sees each grade land as it
 * lands, and stopping actually stops the next one from being queued.
 *
 * Three things about it that are load-bearing:
 *
 * - **Nothing here is ever abandoned mid-flight.** Core grades inside the request and finishes
 *   whether or not anyone is still listening, so dropping a request in progress cannot un-grade
 *   anything — it can only throw away *our* knowledge of a result that has already been written,
 *   leaving the row showing a stale grade. So neither cancelling nor unmounting aborts the request
 *   in flight; both stop the *next* one from starting.
 * - **This hook must be owned by something that outlives the list.** It is called from
 *   `CourseExercisePage`, not from `SubmissionsList`, precisely because selecting a student or
 *   changing tab unmounts the list. Owned there, a click to read one student's code partway through
 *   a sixty-student run would have killed the run and left no trace of it having stopped.
 * - **The grade is only in core.** The retry response body is empty, so after each submission the
 *   students list is re-read to pick up the new grade. One small list request per student, next to
 *   one full grading run per student — noise beside the thing it reports on.
 */
export default function useRerunAllTests(courseId: string, courseExerciseId: string) {
  const queryClient = useQueryClient()
  const [progress, setProgress] = useState<RerunProgress>(IDLE)

  // Refs rather than state: the loop below reads these between awaits, and a state value captured
  // when the loop started would never see the cancel that happened during it.
  const cancelRef = useRef(false)
  const runningRef = useRef(false)

  // Cleared on the way out and set again on the way in, because StrictMode runs mount → cleanup →
  // mount: a flag only ever cleared would be `false` for the whole life of the component.
  useEffect(() => {
    cancelRef.current = false
    return () => {
      cancelRef.current = true
    }
  }, [])

  /**
   * Stop after the submission currently being graded — deliberately including it.
   *
   * Cancel means what the button says: no more are started. The one in flight is left to finish so
   * that its grade lands and the count is a count of work actually done. An earlier version aborted
   * it too and then reported "stopped after 0 of 2" about a student whose tests had in fact just
   * been re-run.
   */
  const cancel = useCallback(() => {
    cancelRef.current = true
    setProgress((p) => (p.running ? { ...p, cancelled: true } : p))
  }, [])

  const dismiss = useCallback(() => {
    setProgress((p) => (p.running ? p : IDLE))
  }, [])

  const start = useCallback(
    async (submissionIds: string[]) => {
      if (runningRef.current || submissionIds.length === 0) return
      runningRef.current = true
      cancelRef.current = false

      setProgress({
        running: true,
        total: submissionIds.length,
        done: 0,
        failed: 0,
        currentSubmissionId: null,
        finished: false,
        cancelled: false,
        gaveUp: false,
      })

      let done = 0
      let failed = 0
      let consecutiveFailures = 0
      let gaveUp = false

      // `finally` rather than a line after the loop: every path out of it — the last submission, a
      // cancel, a throw nobody expected — has to put the latch down, or the button never works again.
      try {
        for (const submissionId of submissionIds) {
          if (cancelRef.current) break
          setProgress((p) => ({ ...p, currentSubmissionId: submissionId }))

          try {
            await apiFetch(
              `/teacher/courses/${courseId}/exercises/${courseExerciseId}/submissions/${submissionId}/retry-autoassess`,
              { method: 'POST' },
            )
            consecutiveFailures = 0
          } catch {
            // Counted, not thrown: one student whose grading blew up should not end the run for
            // everyone behind them. A row of them, on the other hand, is not about the students.
            failed++
            consecutiveFailures++
          }
          done++

          // Re-read before advancing the counter, so "4 of 17" and the fourth row's new grade appear
          // together rather than the number running ahead of the grades it is counting. Addressed by
          // key rather than through a callback from the list, so that it still works when the
          // teacher has navigated to a student and the list is not mounted.
          try {
            await queryClient.refetchQueries({
              queryKey: [
                'teacher', 'courses', courseId, 'exercises', courseExerciseId, 'submissions', 'latest',
              ],
            })
          } catch {
            // A refresh that fails leaves a stale row, not a failed run. Keep going.
          }

          if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
            gaveUp = true
            setProgress((p) => ({ ...p, done, failed }))
            break
          }
          setProgress((p) => ({ ...p, done, failed }))
        }
      } finally {
        runningRef.current = false
      }

      setProgress((p) => ({
        ...p,
        running: false,
        done,
        failed,
        currentSubmissionId: null,
        finished: true,
        gaveUp,
      }))

      // The grades moved, so everything downstream of them did too — the grade table, whose key ends
      // in `{groupId}` rather than the exercise id, and the per-exercise completed/ungraded counts.
      // Same reasoning as `useRetryAutoassess` (audit X-031 / review F-035). Runs even when the run
      // was stopped by an unmount: `queryClient` outlives the component, and the stale counts it
      // fixes are on pages the teacher has just navigated to.
      queryClient.invalidateQueries({
        queryKey: ['teacher', 'courses', courseId, 'exercises'],
      })
    },
    [courseId, courseExerciseId, queryClient],
  )

  return { progress, start, cancel, dismiss }
}

export type RerunController = ReturnType<typeof useRerunAllTests>
