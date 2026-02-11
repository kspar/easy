import { useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Chip,
  CircularProgress,
  Alert,
  Divider,
  Paper,
  Typography,
} from '@mui/material'
import { ExpandMore } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { format } from 'date-fns'
import { et, enUS } from 'date-fns/locale'
import { useSubmissions } from '../../api/exercises.ts'

export default function PreviousSubmissions({
  courseId,
  courseExerciseId,
}: {
  courseId: string
  courseExerciseId: string
}) {
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enUS
  const [expanded, setExpanded] = useState(false)

  const {
    data: submissions,
    isLoading,
    error,
  } = useSubmissions(courseId, courseExerciseId)

  if (isLoading) return <CircularProgress size={24} />
  if (error)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if (!submissions || submissions.length === 0) return null

  return (
    <Box>
      <Divider sx={{ my: 3 }} />
      <Accordion
        expanded={expanded}
        onChange={() => setExpanded(!expanded)}
        disableGutters
        variant="outlined"
        sx={{ '&:before': { display: 'none' } }}
      >
        <AccordionSummary expandIcon={<ExpandMore />}>
          <Typography variant="h6">
            {t('submission.previousSubmissions')} ({submissions.length})
          </Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ p: 0 }}>
          {submissions.map((sub) => (
            <SubmissionItem
              key={sub.id}
              submission={sub}
              dateFnsLocale={dateFnsLocale}
            />
          ))}
        </AccordionDetails>
      </Accordion>
    </Box>
  )
}

function SubmissionItem({
  submission,
  dateFnsLocale,
}: {
  submission: {
    id: string
    number: number
    solution: string
    submission_time: string
    grade: { grade: number } | null
    autograde_status: string
  }
  dateFnsLocale: Locale
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  return (
    <Box sx={{ borderTop: 1, borderColor: 'divider' }}>
      <Box
        onClick={() => setOpen(!open)}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          px: 2,
          py: 1.5,
          cursor: 'pointer',
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        <Typography variant="body2" fontWeight={500}>
          {t('submission.submissionNr', { nr: submission.number })}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {format(new Date(submission.submission_time), 'PPp', {
            locale: dateFnsLocale,
          })}
        </Typography>
        <Box sx={{ ml: 'auto', display: 'flex', alignItems: 'center', gap: 1 }}>
          {submission.grade != null && (
            <Chip
              label={`${submission.grade.grade}p`}
              size="small"
              color="primary"
              variant="outlined"
            />
          )}
          {submission.autograde_status === 'IN_PROGRESS' && (
            <CircularProgress size={16} />
          )}
        </Box>
      </Box>

      {open && (
        <Paper
          variant="outlined"
          sx={{ mx: 2, mb: 1.5, p: 1.5, bgcolor: 'action.hover' }}
        >
          <Typography
            variant="body2"
            component="pre"
            sx={{
              whiteSpace: 'pre-wrap',
              fontFamily: 'monospace',
              fontSize: '0.8rem',
              maxHeight: 300,
              overflow: 'auto',
              m: 0,
            }}
          >
            {submission.solution}
          </Typography>
        </Paper>
      )}
    </Box>
  )
}
