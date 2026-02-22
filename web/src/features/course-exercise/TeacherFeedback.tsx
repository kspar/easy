import { useState } from 'react'
import {
  Box,
  Chip,
  CircularProgress,
  Alert,
  Divider,
  Paper,
  Typography,
  ButtonBase,
} from '@mui/material'
import {
  UnfoldMoreOutlined,
  UnfoldLessOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import RelativeTime from '../../components/RelativeTime.tsx'
import { useTeacherActivities } from '../../api/exercises.ts'
import type { InlineComment, SubmissionResp } from '../../api/types.ts'

const CONTEXT_LINES = 3

function AnnotatedCode({
  solution,
  comments,
}: {
  solution: string
  comments: InlineComment[]
}) {
  const { t } = useTranslation()
  const lines = solution.split('\n')
  // Remove trailing empty line from final newline
  if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()

  const [showAll, setShowAll] = useState(false)

  // Sort comments by line_start
  const sorted = [...comments].sort((a, b) => a.line_start - b.line_start)

  // Build set of visible line numbers (1-indexed) based on context around comments
  const visibleLines = new Set<number>()
  const annotatedLines = new Set<number>()

  for (const c of sorted) {
    for (let i = c.line_start; i <= c.line_end; i++) annotatedLines.add(i)
    const ctxStart = Math.max(1, c.line_start - CONTEXT_LINES)
    const ctxEnd = Math.min(lines.length, c.line_end + CONTEXT_LINES)
    for (let i = ctxStart; i <= ctxEnd; i++) visibleLines.add(i)
  }

  // Build a map: after which line (1-indexed) do we insert comments?
  const commentsAfterLine = new Map<number, InlineComment[]>()
  for (const c of sorted) {
    const key = c.line_end
    if (!commentsAfterLine.has(key)) commentsAfterLine.set(key, [])
    commentsAfterLine.get(key)!.push(c)
  }

  const lineNumWidth = String(lines.length).length

  // Build render segments: runs of visible lines, collapsed gaps, and inline comments
  const elements: React.ReactNode[] = []
  let i = 0
  while (i < lines.length) {
    const lineNum = i + 1 // 1-indexed
    if (showAll || visibleLines.has(lineNum)) {
      // Render this line
      elements.push(
        <CodeLine
          key={`L${lineNum}`}
          lineNum={lineNum}
          text={lines[i]}
          lineNumWidth={lineNumWidth}
          highlighted={annotatedLines.has(lineNum)}
        />,
      )
      // Insert any comments that end at this line
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
      // Count hidden lines
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

function InlineCommentCard({ comment }: { comment: InlineComment }) {
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
      <Box
        sx={{
          '& p:first-of-type': { mt: 0 },
          '& p:last-of-type': { mb: 0 },
          fontSize: '0.85rem',
          fontFamily: 'inherit',
        }}
        dangerouslySetInnerHTML={{ __html: comment.text_html }}
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

export default function TeacherFeedback({
  courseId,
  courseExerciseId,
  submissions,
}: {
  courseId: string
  courseExerciseId: string
  submissions?: SubmissionResp[]
}) {
  const { t } = useTranslation()

  const {
    data: activities,
    isLoading,
    error,
  } = useTeacherActivities(courseId, courseExerciseId)

  if (isLoading) return <CircularProgress size={24} />
  if (error)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if (!activities || activities.length === 0) return null

  // Build a map of submission_id -> solution for quick lookup
  const solutionBySubmissionId = new Map<string, string>()
  if (submissions) {
    for (const sub of submissions) {
      solutionBySubmissionId.set(sub.id, sub.solution)
    }
  }

  return (
    <Box>
      <Divider sx={{ my: 3 }} />
      <Typography variant="h6" gutterBottom>
        {t('submission.teacherFeedback')}
      </Typography>

      {[...activities].reverse().map((activity) => {
        const inlineComments = activity.feedback?.inline ?? []
        const hasInline = inlineComments.length > 0
        const solution = solutionBySubmissionId.get(activity.submission_id)

        return (
          <Paper
            key={activity.id}
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
                {activity.teacher.given_name} {activity.teacher.family_name}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                <RelativeTime date={activity.created_at} />
                {' · '}
                {t('submission.submissionNr', {
                  nr: activity.submission_number,
                })}
              </Typography>
            </Box>

            {activity.grade != null && (
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

            {activity.feedback?.general_html && (
              <Box
                sx={{
                  '& p:first-of-type': { mt: 0 },
                  '& p:last-of-type': { mb: 0 },
                }}
                dangerouslySetInnerHTML={{
                  __html: activity.feedback.general_html,
                }}
              />
            )}

            {/* Inline comments: render inline in code if source available, else flat cards */}
            {hasInline && solution != null ? (
              <AnnotatedCode
                solution={solution}
                comments={inlineComments}
              />
            ) : (
              hasInline && (
                <Box sx={{ mt: 1 }}>
                  {inlineComments.map((comment, idx) => (
                    <Box
                      key={idx}
                      sx={{
                        p: 1.5,
                        mb: 1,
                        bgcolor: 'action.hover',
                        borderRadius: 1,
                      }}
                    >
                      <Box
                        component="pre"
                        sx={{
                          m: 0,
                          mb: 1,
                          p: 1,
                          bgcolor: 'background.default',
                          borderRadius: 0.5,
                          fontSize: '0.8rem',
                          overflow: 'auto',
                        }}
                      >
                        <code>{comment.code}</code>
                      </Box>
                      <Box
                        sx={{
                          '& p:first-of-type': { mt: 0 },
                          '& p:last-of-type': { mb: 0 },
                        }}
                        dangerouslySetInnerHTML={{
                          __html: comment.text_html,
                        }}
                      />
                      {comment.suggested_code && (
                        <Box sx={{ mt: 1 }}>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: 'block', mb: 0.5 }}
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
                            }}
                          >
                            <code>{comment.suggested_code}</code>
                          </Box>
                        </Box>
                      )}
                    </Box>
                  ))}
                </Box>
              )
            )}
          </Paper>
        )
      })}
    </Box>
  )
}
