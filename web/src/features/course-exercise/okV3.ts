export type V3Status = 'PASS' | 'FAIL' | 'SKIP'

export interface OkV3Check {
  title: string
  status: V3Status
  feedback: string | null
}

export interface OkV3File {
  name: string
  content: string
}

export interface OkV3Test {
  title: string
  status: V3Status
  user_inputs: string[]
  created_files: OkV3File[]
  converted_submission: string | null
  actual_output: string | null
  exception_message: string | null
  checks: OkV3Check[]
}

export interface OkV3Feedback {
  result_type: 'OK_V3'
  producer: string
  pre_evaluate_error: string | null
  points: number
  tests: OkV3Test[]
}

export function parseOkV3(feedback: string | null): OkV3Feedback | null {
  if (!feedback) return null
  try {
    const parsed = JSON.parse(feedback)
    if (parsed?.result_type === 'OK_V3' && Array.isArray(parsed.tests)) {
      return parsed as OkV3Feedback
    }
    return null
  } catch {
    return null
  }
}

// Deliberately NO feedback-based "is this a grader failure" classifier here. The first attempt
// at audit X-026 treated every non-OK_V3 feedback string as an infrastructure outage — but plain
// text is the legitimate answer of every legacy grader (aae parses their 'grade:' format) and of
// aae's own time/memory verdicts, and `pre_evaluate_error` is the *student's* pre-check failure,
// not the teacher's. The one honest outage signal is the status core sets when grading itself
// broke:

/**
 * Whether this submission's automatic grading failed as an *infrastructure* matter. Core sets
 * FAILED without attaching a new assessment — but an old assessment from a previous run can
 * still be present (a teacher's retry-autoassess on a previously graded submission leaves the
 * stale one in place), so callers must not infer the outage from the assessment's absence.
 */
export function isGraderFailed(submission: { autograde_status: string }): boolean {
  return submission.autograde_status === 'FAILED'
}
