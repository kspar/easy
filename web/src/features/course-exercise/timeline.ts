import type { AiFeedbackResp, InlineCommentResp, TeacherActivityResp } from '../../api/types.ts'

/**
 * Comments landing within this of each other from the same teacher are one card. Mirrors core's
 * `easy.core.activity.merge-window.s`, which decides the same thing on the write side.
 */
export const MERGE_WINDOW_MS = 15 * 60 * 1000

/**
 * One card in a feedback feed.
 *
 * `kind` says who wrote it. A teacher entry has a name, and may carry a feedback activity, inline
 * comments, or both. An AI entry (EZ-1712) has neither name nor comments: its `teacherId` is a
 * synthetic `ai:<id>` so that the inline-comment matching below can never attach a human's comment
 * to it, and `aiFeedback` is the whole of its content.
 */
export interface TimelineEntry {
  kind: 'teacher' | 'ai'
  teacherId: string
  teacherName: string
  time: string
  activity?: TeacherActivityResp
  aiFeedback?: AiFeedbackResp
  inlineComments: InlineCommentResp[]
  submissionNumbers: Set<number>
}

/**
 * Merges activities, inline comments and AI explanations into one list, newest first.
 *
 * Shared by the student's `TeacherFeedback` and the teacher's `ActivityFeed`, which used to carry
 * two copies of this and were one edit away from disagreeing about what a card is.
 */
export function buildTimeline(
  activities: TeacherActivityResp[] | undefined,
  inlineComments: InlineCommentResp[] | undefined,
  aiFeedback: AiFeedbackResp[] | undefined = [],
): TimelineEntry[] {
  // One entry per activity, sorted ascending by time for matching
  const entries: TimelineEntry[] = (activities ?? []).map((a) => ({
    kind: 'teacher' as const,
    teacherId: a.teacher.id,
    teacherName: `${a.teacher.given_name} ${a.teacher.family_name}`,
    time: a.created_at,
    activity: a,
    inlineComments: [],
    submissionNumbers: new Set([a.submission_number]),
  }))
  entries.sort((a, b) => new Date(a.time).getTime() - new Date(b.time).getTime())

  // Attach each inline comment to the closest activity by the same teacher within the window
  // Prefer preceding activities; only use a following activity if no preceding one is in the window
  const orphans: InlineCommentResp[] = []
  for (const c of inlineComments ?? []) {
    const cTime = new Date(c.created_at).getTime()
    let bestBefore: TimelineEntry | null = null
    let bestBeforeDiff = Infinity
    let bestAfter: TimelineEntry | null = null
    let bestAfterDiff = Infinity
    for (const entry of entries) {
      if (!entry.activity || entry.teacherId !== c.teacher.id) continue
      const eTime = new Date(entry.time).getTime()
      const diff = Math.abs(eTime - cTime)
      if (diff >= MERGE_WINDOW_MS) continue
      if (eTime <= cTime && diff < bestBeforeDiff) {
        bestBefore = entry
        bestBeforeDiff = diff
      } else if (eTime > cTime && diff < bestAfterDiff) {
        bestAfter = entry
        bestAfterDiff = diff
      }
    }
    const best = bestBefore ?? bestAfter
    if (best) {
      best.inlineComments.push(c)
      best.submissionNumbers.add(c.submission_number)
    } else {
      orphans.push(c)
    }
  }

  // Orphan inline comments (no preceding activity) — group by teacher within the window
  for (const c of orphans) {
    const cTime = new Date(c.created_at).getTime()
    const match = entries.find(
      (e) => e.kind === 'teacher' && !e.activity && e.teacherId === c.teacher.id &&
        Math.abs(new Date(e.time).getTime() - cTime) < MERGE_WINDOW_MS,
    )
    if (match) {
      match.inlineComments.push(c)
      match.submissionNumbers.add(c.submission_number)
    } else {
      entries.push({
        kind: 'teacher',
        teacherId: c.teacher.id,
        teacherName: `${c.teacher.given_name} ${c.teacher.family_name}`,
        time: c.created_at,
        inlineComments: [c],
        submissionNumbers: new Set([c.submission_number]),
      })
    }
  }

  // AI explanations never merge with anything: one card per explanation, after the matching above
  // so no comment can have been attached to them.
  for (const a of aiFeedback ?? []) {
    entries.push({
      kind: 'ai',
      teacherId: `ai:${a.id}`,
      teacherName: '',
      time: a.created_at,
      aiFeedback: a,
      inlineComments: [],
      submissionNumbers: new Set([a.submission_number]),
    })
  }

  // Sort descending for display
  entries.sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
  return entries
}
