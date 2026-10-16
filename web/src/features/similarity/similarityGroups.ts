import type { SimilarityGroupResp } from '../../api/types.ts'

/**
 * Who handed in a submission, as far as the page knows: the student its rows name, or the submission
 * itself where they name none - an unknown author differs from everyone, as in core.
 */
export type StudentOf = (submissionId: string) => string | undefined

const authorOf = (studentOf: StudentOf) => (id: string) => studentOf(id) ?? `#${id}`

/**
 * How many students handed in a group's code. Students, not submissions: explicit ids can bring one
 * student's several identical attempts into a group, and counted as submissions they made three people
 * read as four.
 */
export function groupAuthors(group: SimilarityGroupResp, studentOf: StudentOf): number {
  return new Set(group.submissions.map(authorOf(studentOf))).size
}

/**
 * For each submission in a group of identical ones, how many other students handed in the same code.
 *
 * Core compares a group once and names one member for it in `scores`, so a pair row showing that member
 * stands for the whole group — and without saying so, "Mari – Jüri 100%" reads as one finding when it is
 * twelve. Here rather than in the page because a wrong count is invisible there: an off-by-one says "+12
 * identical" for a group of twelve, and nothing looks broken.
 *
 * `groups` may be absent: a core older than groups sends none.
 */
export function identicalOthers(groups: SimilarityGroupResp[] | undefined, studentOf: StudentOf = () => undefined): Map<string, number> {
  const author = authorOf(studentOf)
  const others = new Map<string, number>()
  for (const group of groups ?? []) {
    const authors = new Set(group.submissions.map(author))
    for (const id of group.submissions) others.set(id, authors.size - 1)
  }
  return others
}
