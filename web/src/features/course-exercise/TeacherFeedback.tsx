import { useState } from 'react'
import {
  Box,
  ButtonBase,
  Chip,
  CircularProgress,
  Alert,
  Collapse,
  Divider,
  Paper,
  Typography,
} from '@mui/material'
import {
  ExpandMoreOutlined,
  UnfoldMoreOutlined,
  UnfoldLessOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import RelativeTime from '../../components/RelativeTime.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import ReadOnlyCodeSnippet from './ReadOnlyCodeSnippet.tsx'
import { useTeacherActivities, useStudentInlineComments } from '../../api/exercises.ts'
import type { InlineCommentResp, SubmissionResp, TeacherActivityResp } from '../../api/types.ts'

const CONTEXT_LINES = 3
const MERGE_WINDOW_MS = 15 * 60 * 1000 // 15 minutes

/* ───────── Timeline (same logic as ActivityFeed) ───────── */

interface TimelineEntry {
  teacherId: string
  teacherName: string
  time: string
  activity?: TeacherActivityResp
  inlineComments: InlineCommentResp[]
  submissionNumbers: Set<number>
}

function buildTimeline(
  activities: TeacherActivityResp[],
  inlineComments: InlineCommentResp[],
): TimelineEntry[] {
  const entries: TimelineEntry[] = activities.map((a) => ({
    teacherId: a.teacher.id,
    teacherName: `${a.teacher.given_name} ${a.teacher.family_name}`,
    time: a.created_at,
    activity: a,
    inlineComments: [],
    submissionNumbers: new Set([a.submission_number]),
  }))
  entries.sort((a, b) => new Date(a.time).getTime() - new Date(b.time).getTime())

  const orphans: InlineCommentResp[] = []
  for (const c of inlineComments) {
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

  for (const c of orphans) {
    const cTime = new Date(c.created_at).getTime()
    const match = entries.find(
      (e) => !e.activity && e.teacherId === c.teacher.id &&
        Math.abs(new Date(e.time).getTime() - cTime) < MERGE_WINDOW_MS,
    )
    if (match) {
      match.inlineComments.push(c)
      match.submissionNumbers.add(c.submission_number)
    } else {
      entries.push({
        teacherId: c.teacher.id,
        teacherName: `${c.teacher.given_name} ${c.teacher.family_name}`,
        time: c.created_at,
        inlineComments: [c],
        submissionNumbers: new Set([c.submission_number]),
      })
    }
  }

  entries.sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
  return entries
}

/* ───────── Annotated code (unchanged from before) ───────── */

function AnnotatedCode({
  solution,
  comments,
}: {
  solution: string
  comments: InlineCommentResp[]
}) {
  const { t } = useTranslation()
  const lines = solution.split('\n')
  if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()

  const [showAll, setShowAll] = useState(false)

  const sorted = [...comments].sort((a, b) => a.line_start - b.line_start)

  const visibleLines = new Set<number>()
  const annotatedLines = new Set<number>()

  for (const c of sorted) {
    for (let i = c.line_start; i <= c.line_end; i++) annotatedLines.add(i)
    const ctxStart = Math.max(1, c.line_start - CONTEXT_LINES)
    const ctxEnd = Math.min(lines.length, c.line_end + CONTEXT_LINES)
    for (let i = ctxStart; i <= ctxEnd; i++) visibleLines.add(i)
  }

  const commentsAfterLine = new Map<number, InlineCommentResp[]>()
  for (const c of sorted) {
    const key = c.line_end
    if (!commentsAfterLine.has(key)) commentsAfterLine.set(key, [])
    commentsAfterLine.get(key)!.push(c)
  }

  const lineNumWidth = String(lines.length).length

  const elements: React.ReactNode[] = []
  let i = 0
  while (i < lines.length) {
    const lineNum = i + 1
    if (showAll || visibleLines.has(lineNum)) {
      elements.push(
        <CodeLine
          key={`L${lineNum}`}
          lineNum={lineNum}
          text={lines[i]}
          lineNumWidth={lineNumWidth}
          highlighted={annotatedLines.has(lineNum)}
        />,
      )
      const coms = commentsAfterLine.get(lineNum)
      if (coms) {
        for (let ci = 0; ci < coms.length; ci++) {
          elements.push(
            <InlineCommentCard key={`C${lineNum}-${ci}`} comment={coms[ci]} />,
          )
        }
      }
      i++
    } else {
      const gapStart = i
      while (i < lines.length && !showAll && !visibleLines.has(i + 1)) i++
      const hiddenCount = i - gapStart
      elements.push(
        <CollapsedGap
          key={`G${gapStart}`}
          count={hiddenCount}
          onClick={() => setShowAll(true)}
        />,
      )
    }
  }

  return (
    <Box sx={{ mt: 1.5 }}>
      <Box
        sx={{
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          overflow: 'hidden',
          fontSize: '0.8rem',
          fontFamily: 'monospace',
        }}
      >
        {elements}
      </Box>
      {!showAll && lines.length > visibleLines.size && (
        <ButtonBase
          onClick={() => setShowAll(true)}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.5,
            mt: 0.5,
            px: 1,
            py: 0.25,
            borderRadius: 1,
            fontSize: '0.75rem',
            color: 'text.secondary',
            '&:hover': { color: 'primary.main' },
          }}
        >
          <UnfoldMoreOutlined sx={{ fontSize: 16 }} />
          {t('submission.showAllLines')}
        </ButtonBase>
      )}
      {showAll && lines.length > visibleLines.size && (
        <ButtonBase
          onClick={() => setShowAll(false)}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.5,
            mt: 0.5,
            px: 1,
            py: 0.25,
            borderRadius: 1,
            fontSize: '0.75rem',
            color: 'text.secondary',
            '&:hover': { color: 'primary.main' },
          }}
        >
          <UnfoldLessOutlined sx={{ fontSize: 16 }} />
          {t('submission.collapseLines')}
        </ButtonBase>
      )}
    </Box>
  )
}

function CodeLine({
  lineNum,
  text,
  lineNumWidth,
  highlighted,
}: {
  lineNum: number
  text: string
  lineNumWidth: number
  highlighted: boolean
}) {
  return (
    <Box
      sx={{
        display: 'flex',
        bgcolor: highlighted
          ? (t) =>
              t.palette.mode === 'dark'
                ? 'rgba(255, 167, 38, 0.08)'
                : 'rgba(255, 167, 38, 0.06)'
          : 'transparent',
        '&:hover': { bgcolor: 'action.hover' },
        lineHeight: 1.6,
      }}
    >
      <Box
        component="span"
        sx={{
          display: 'inline-block',
          width: `${lineNumWidth + 2}ch`,
          flexShrink: 0,
          textAlign: 'right',
          pr: 1.5,
          pl: 1,
          color: 'text.disabled',
          userSelect: 'none',
          borderRight: 1,
          borderColor: 'divider',
        }}
      >
        {lineNum}
      </Box>
      <Box
        component="pre"
        sx={{
          m: 0,
          pl: 1.5,
          pr: 1,
          flex: 1,
          minWidth: 0,
          overflow: 'auto',
          whiteSpace: 'pre',
        }}
      >
        {text || ' '}
      </Box>
    </Box>
  )
}

function InlineCommentCard({ comment }: { comment: InlineCommentResp }) {
  const { t } = useTranslation()
  return (
    <Box
      sx={{
        py: 1,
        px: 1.5,
        bgcolor: (th) =>
          th.palette.mode === 'dark'
            ? 'rgba(255,255,255,0.04)'
            : 'rgba(0,0,0,0.025)',
        borderTop: 1,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.25 }}>
        {comment.teacher.given_name} {comment.teacher.family_name}
      </Typography>
      <RenderedMarkdown
        sx={{
          fontSize: '0.85rem',
          fontFamily: 'inherit',
        }}
        html={comment.text_html}
      />
      {comment.suggested_code && (
        <Box sx={{ mt: 1 }}>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: 'block', mb: 0.5, fontFamily: 'inherit' }}
          >
            {t('submission.suggestedChange')}
          </Typography>
          <Box
            component="pre"
            sx={{
              m: 0,
              p: 1,
              bgcolor: (th) =>
                th.palette.mode === 'dark'
                  ? 'rgba(46, 125, 50, 0.10)'
                  : 'rgba(46, 125, 50, 0.06)',
              borderRadius: 0.5,
              fontSize: '0.8rem',
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
            }}
          >
            {comment.suggested_code}
          </Box>
        </Box>
      )}
    </Box>
  )
}

function CollapsedGap({
  count,
  onClick,
}: {
  count: number
  onClick: () => void
}) {
  const { t } = useTranslation()
  return (
    <ButtonBase
      onClick={onClick}
      sx={{
        display: 'flex',
        width: '100%',
        justifyContent: 'center',
        py: 0.25,
        bgcolor: (th) =>
          th.palette.mode === 'dark'
            ? 'rgba(255,255,255,0.03)'
            : 'rgba(0,0,0,0.02)',
        borderTop: 1,
        borderBottom: 1,
        borderColor: 'divider',
        fontSize: '0.75rem',
        color: 'text.secondary',
        gap: 0.5,
        '&:hover': { color: 'primary.main', bgcolor: 'action.hover' },
      }}
    >
      <UnfoldMoreOutlined sx={{ fontSize: 14 }} />
      {t('submission.showHiddenLines', { count })}
    </ButtonBase>
  )
}

/* ───────── Main component ───────── */

export default function TeacherFeedback({
  courseId,
  courseExerciseId,
  submissions,
  solutionFileName,
  onSelectSubmissionNumber,
}: {
  courseId: string
  courseExerciseId: string
  submissions?: SubmissionResp[]
  solutionFileName?: string
  onSelectSubmissionNumber?: (nr: number) => void
}) {
  const { t } = useTranslation()

  const {
    data: activities,
    isLoading: activitiesLoading,
    error: activitiesError,
  } = useTeacherActivities(courseId, courseExerciseId)

  const {
    data: inlineComments,
    isLoading: commentsLoading,
  } = useStudentInlineComments(courseId, courseExerciseId)

  const isLoading = activitiesLoading || commentsLoading

  if (isLoading) return <CircularProgress size={24} />
  if (activitiesError)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if ((!activities || activities.length === 0) && (!inlineComments || inlineComments.length === 0)) return null

  // Build a map of submission_id -> solution for quick lookup
  const solutionBySubmissionId = new Map<string, string>()
  if (submissions) {
    for (const sub of submissions) {
      solutionBySubmissionId.set(sub.id, sub.solution)
    }
  }

  const timelineEntries = buildTimeline(activities ?? [], inlineComments ?? [])

  return (
    <Box>
      <Divider sx={{ my: 3 }} />
      <Typography variant="h6" gutterBottom>
        {t('submission.teacherFeedback')}
      </Typography>

      {timelineEntries.map((entry, i) => {
        const { activity } = entry
        const subNums = [...entry.submissionNumbers].sort((a, b) => a - b)

        // Find inline comments that have corresponding submissions with solutions
        const inlineWithSolution = entry.inlineComments.filter(
          (c) => solutionBySubmissionId.has(c.submission_id),
        )

        // Group inline comments by submission for annotated code view
        const commentsBySubmission = new Map<string, InlineCommentResp[]>()
        for (const c of inlineWithSolution) {
          const arr = commentsBySubmission.get(c.submission_id) ?? []
          arr.push(c)
          commentsBySubmission.set(c.submission_id, arr)
        }

        return (
          <Paper
            key={activity?.id ?? `orphan-${i}`}
            variant="outlined"
            sx={{ p: 2, mb: 1.5 }}
          >
            <Box
              sx={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'baseline',
                mb: 1,
              }}
            >
              <Typography variant="subtitle2">
                {entry.teacherName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                <RelativeTime date={entry.time} />
                {subNums.length > 0 && (
                  <>
                    {' · '}
                    {subNums.map((n, i) => (
                      <span key={n}>
                        {i > 0 && ', '}
                        {onSelectSubmissionNumber ? (
                          <Box
                            component="span"
                            onClick={() => onSelectSubmissionNumber(n)}
                            sx={{ cursor: 'pointer', '&:hover': { textDecoration: 'underline', color: 'primary.main' } }}
                          >
                            {t('submission.submissionNr', { nr: n })}
                          </Box>
                        ) : (
                          t('submission.submissionNr', { nr: n })
                        )}
                      </span>
                    ))}
                  </>
                )}
              </Typography>
            </Box>

            {activity && activity.grade != null && (
              <Chip
                label={t('submission.gradedPoints', {
                  points: activity.grade,
                })}
                size="small"
                color="primary"
                variant="outlined"
                sx={{ mb: 1, fontWeight: 600 }}
              />
            )}

            {activity?.feedback_html && (
              <RenderedMarkdown
                sx={{
                  fontSize: '0.85rem',
                }}
                html={activity.feedback_html}
              />
            )}

            {/* Inline comments: render inline in code if source available */}
            {commentsBySubmission.size > 0 && [...commentsBySubmission.entries()].map(([subId, comments]) => {
              const solution = solutionBySubmissionId.get(subId)
              if (!solution) return null
              return (
                <AnnotatedCode
                  key={subId}
                  solution={solution}
                  comments={comments}
                />
              )
            })}

            {/* Inline comments without solution — show as flat cards */}
            {entry.inlineComments.length > inlineWithSolution.length && (
              <InlineCommentFlatList
                comments={entry.inlineComments.filter((c) => !solutionBySubmissionId.has(c.submission_id))}
                solutionFileName={solutionFileName}
                t={t}
              />
            )}
          </Paper>
        )
      })}
    </Box>
  )
}

function InlineCommentFlatList({
  comments,
  solutionFileName,
  t,
}: {
  comments: InlineCommentResp[]
  solutionFileName?: string
  t: (key: string, opts?: Record<string, unknown>) => string
}) {
  const [expanded, setExpanded] = useState(false)

  return (
    <Box sx={{ mt: 1 }}>
      <Box
        onClick={() => setExpanded((v) => !v)}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          cursor: 'pointer',
          userSelect: 'none',
        }}
      >
        <ExpandMoreOutlined
          sx={{
            fontSize: 16,
            color: 'text.secondary',
            transform: expanded ? 'rotate(0deg)' : 'rotate(-90deg)',
            transition: 'transform 0.2s',
          }}
        />
        <Typography variant="caption" color="text.secondary">
          {t('submission.inlineCommentCount', { count: comments.length })}
        </Typography>
      </Box>
      <Collapse in={expanded}>
        <Box sx={{ mt: 0.5 }}>
          {comments.map((comment) => (
            <Box
              key={comment.id}
              sx={{
                p: 1.5,
                mb: 1,
                bgcolor: 'action.hover',
                borderRadius: 1,
              }}
            >
              <Box sx={{ mb: 1 }}>
                <ReadOnlyCodeSnippet
                  code={comment.code}
                  fileName={solutionFileName}
                  firstLineNumber={comment.line_start}
                />
              </Box>
              <RenderedMarkdown html={comment.text_html} />
              {comment.suggested_code && (
                <Box sx={{ mt: 1 }}>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: 'block', mb: 0.5 }}
                  >
                    {t('submission.suggestedChange')}
                  </Typography>
                  <ReadOnlyCodeSnippet
                    code={comment.suggested_code}
                    fileName={solutionFileName}
                  />
                </Box>
              )}
            </Box>
          ))}
        </Box>
      </Collapse>
    </Box>
  )
}
