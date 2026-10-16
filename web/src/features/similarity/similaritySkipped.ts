import type { SimilaritySkippedResp, SubmissionRow } from '../../api/types.ts'

/** Why core could not compare a submission, as this page groups it. */
export type SkippedReason = 'CANNOT_PARSE' | 'TOO_LARGE' | 'TOO_COMPLEX' | 'TOO_REPETITIVE' | 'TOO_SMALL' | 'OTHER'

export interface SkippedStudent {
  submissionId: string
  /** Null when the submission is not among the rows the page compared, which should not happen. */
  studentId: string | null
  name: string | null
}

export interface SkippedGroup {
  reason: SkippedReason
  students: SkippedStudent[]
}

/**
 * Suspicious first. A solution that runs but could not be compared is the one worth opening - padded
 * past the size cap, nested until the parser would take minutes, repeated until comparing it would
 * stall - and TOO_SMALL, which is mostly empty submissions, is the least interesting. Anything core
 * sends that this page does not know goes last, under a generic heading, rather than being counted
 * and not shown.
 */
const ORDER: readonly SkippedReason[] = ['CANNOT_PARSE', 'TOO_LARGE', 'TOO_COMPLEX', 'TOO_REPETITIVE', 'TOO_SMALL', 'OTHER']

const KNOWN: ReadonlySet<string> = new Set(ORDER.filter((reason) => reason !== 'OTHER'))

/**
 * Who could not be compared, and why, named.
 *
 * The page used to say how many were skipped for each reason and not who. That is harmless while
 * every skip is an accident and useless when one is not: a student who pads a copy past the cap, or
 * nests one line past what the parser can follow, drops out of the comparison with code that still
 * passes its tests, and "1 did not parse" does not say where to look. The names come from the rows
 * the page sent for comparison, which carry them; the response does not.
 *
 * Extracted from the page because being wrong here is invisible - a name under the wrong reason, or
 * a skip that silently lists nobody, reads as plausibly as the right answer.
 */
export function skippedByReason(
  skipped: readonly SimilaritySkippedResp[],
  rows: readonly SubmissionRow[],
): SkippedGroup[] {
  const bySubmission = new Map<string, SubmissionRow>()
  for (const row of rows) if (row.submission) bySubmission.set(row.submission.id, row)

  const groups = new Map<SkippedReason, SkippedStudent[]>()
  for (const s of skipped) {
    const reason = KNOWN.has(s.reason) ? (s.reason as SkippedReason) : 'OTHER'
    const row = bySubmission.get(s.id)
    const student: SkippedStudent = {
      submissionId: s.id,
      studentId: row?.student_id ?? null,
      name: row ? `${row.given_name} ${row.family_name}` : null,
    }
    groups.set(reason, [...(groups.get(reason) ?? []), student])
  }

  // Named ones alphabetically, so a teacher can find a student in a long TOO_SMALL list; any without
  // a name after them, in the order core sent them.
  const byName = (a: SkippedStudent, b: SkippedStudent) =>
    a.name === null ? (b.name === null ? 0 : 1) : b.name === null ? -1 : a.name.localeCompare(b.name)
  return ORDER.filter((reason) => groups.has(reason)).map((reason) => ({
    reason,
    students: [...groups.get(reason)!].sort(byName),
  }))
}
