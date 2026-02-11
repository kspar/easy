import { useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Chip,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Collapse,
} from '@mui/material'
import { ExpandMore, ExpandLess } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { format } from 'date-fns'
import { et, enUS } from 'date-fns/locale'
import { useSubmissions } from '../../api/exercises.ts'
import type { SubmissionResp } from '../../api/types.ts'

export default function SubmissionsTab({
  courseId,
  courseExerciseId,
}: {
  courseId: string
  courseExerciseId: string
}) {
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enUS
  const {
    data: submissions,
    isLoading,
    error,
  } = useSubmissions(courseId, courseExerciseId)

  if (isLoading) return <CircularProgress />
  if (error)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>

  if (!submissions || submissions.length === 0) {
    return (
      <Typography color="text.secondary">
        {t('exercises.notSubmitted')}
      </Typography>
    )
  }

  return (
    <List disablePadding>
      {submissions.map((sub, idx) => (
        <SubmissionRow
          key={sub.id}
          submission={sub}
          isLatest={idx === 0}
          dateFnsLocale={dateFnsLocale}
        />
      ))}
    </List>
  )
}

function SubmissionRow({
  submission,
  isLatest,
  dateFnsLocale,
}: {
  submission: SubmissionResp
  isLatest: boolean
  dateFnsLocale: Locale
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  const gradeLabel =
    submission.grade != null ? `${submission.grade.grade}p` : null

  const autogradeInProgress =
    submission.autograde_status === 'IN_PROGRESS'

  return (
    <Box sx={{ mb: 1 }}>
      <ListItemButton
        onClick={() => setOpen(!open)}
        sx={{ borderRadius: 1 }}
      >
        <ListItemText
          primary={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Typography variant="body1">
                #{submission.number}
              </Typography>
              {isLatest && (
                <Chip label={t('submission.latestSuffix')} size="small" />
              )}
              {autogradeInProgress && (
                <CircularProgress size={16} />
              )}
            </Box>
          }
          secondary={format(
            new Date(submission.submission_time),
            'PPp',
            { locale: dateFnsLocale },
          )}
        />
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          {gradeLabel && (
            <Typography variant="body2" fontWeight={500}>
              {gradeLabel}
            </Typography>
          )}
          {open ? <ExpandLess /> : <ExpandMore />}
        </Box>
      </ListItemButton>

      <Collapse in={open}>
        <Paper variant="outlined" sx={{ p: 2, ml: 2, mt: 0.5 }}>
          <Typography
            variant="body2"
            component="pre"
            sx={{
              whiteSpace: 'pre-wrap',
              fontFamily: 'monospace',
              fontSize: '0.85rem',
              maxHeight: 400,
              overflow: 'auto',
            }}
          >
            {submission.solution}
          </Typography>

          {submission.auto_assessment && (
            <Box sx={{ mt: 2 }}>
              <Typography variant="subtitle2" gutterBottom>
                {t('submission.autoTests')}
              </Typography>
              <Typography variant="body2">
                {t('submission.grade')}: {submission.auto_assessment.grade}p
              </Typography>
              {submission.auto_assessment.feedback && (
                <Paper
                  variant="outlined"
                  sx={{
                    p: 1.5,
                    mt: 1,
                    bgcolor: 'action.hover',
                    whiteSpace: 'pre-wrap',
                    fontFamily: 'monospace',
                    fontSize: '0.85rem',
                  }}
                >
                  {submission.auto_assessment.feedback}
                </Paper>
              )}
            </Box>
          )}
        </Paper>
      </Collapse>
    </Box>
  )
}