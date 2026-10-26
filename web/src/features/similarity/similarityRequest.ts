import type { SimilarityReq } from '../../api/types.ts'

/**
 * What the page asks a similarity check, and its estimate, about (EZ-1667): the students listed for one
 * course exercise who have handed something in. Why the course exercise is named, and why their latest
 * submissions go beside them, is on `SimilarityReq`. A plain module, so a unit test can hold the body to
 * what core and an older core each read from it.
 */
export function similarityRequest(
  courseId: string,
  courseExerciseId: string,
  submitted: { student_id: string; submission?: { id: string } | null }[],
): SimilarityReq {
  return {
    courses: [{ id: courseId }],
    course_exercise_id: courseExerciseId,
    students: submitted.map((r) => ({ id: r.student_id })),
    submissions: submitted.flatMap((r) => (r.submission ? [{ id: r.submission.id }] : [])),
  }
}
