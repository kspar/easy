import { Box, Chip, Paper, Tooltip, Typography } from '@mui/material'
import { AutoAwesomeOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import RelativeTime from '../../components/RelativeTime.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import type { AiFeedbackResp } from '../../api/types.ts'

/**
 * An AI explanation in a feedback feed (EZ-1712).
 *
 * Everything about this card exists so that it cannot be mistaken for a teacher's: a dashed border
 * where theirs is solid, an icon and an "AI" chip where theirs has a name, and a one-line
 * disclaimer under the text. That is the feature's one non-negotiable — a student who thinks their
 * teacher wrote this has been misled, however good the explanation.
 *
 * Shared by the student's feed and the teacher's, so both see the same thing the student saw.
 */
export default function AiFeedbackCard({
  entry,
  onSelectSubmissionNumber,
  dense = false,
}: {
  entry: AiFeedbackResp
  onSelectSubmissionNumber?: (nr: number) => void
  /** The teacher's feed is narrower and its cards tighter; match it. */
  dense?: boolean
}) {
  const { t } = useTranslation()
  const nr = entry.submission_number

  return (
    <Paper
      variant="outlined"
      sx={{
        p: dense ? 1.5 : 2,
        mb: 1.5,
        borderStyle: 'dashed',
        // A faint wash of the primary colour, on both themes: the alpha, not a fixed tint, is what
        // keeps it faint on dark backgrounds too.
        bgcolor: (theme) => theme.palette.mode === 'dark'
          ? 'rgba(144, 202, 249, 0.06)'
          : 'rgba(25, 118, 210, 0.04)',
      }}
    >
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1, gap: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}>
          <AutoAwesomeOutlined sx={{ fontSize: 18 }} color="primary" />
          <Typography variant={dense ? 'caption' : 'subtitle2'} fontWeight={500} noWrap>
            {t('submission.aiFeedbackTitle')}
          </Typography>
          <Chip label={t('submission.aiLabel')} size="small" variant="outlined" color="primary" sx={{ height: 20 }} />
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
          <RelativeTime date={entry.created_at} />
          {' · '}
          {onSelectSubmissionNumber ? (
            <Box
              component="span"
              onClick={() => onSelectSubmissionNumber(nr)}
              sx={{ cursor: 'pointer', '&:hover': { textDecoration: 'underline', color: 'primary.main' } }}
            >
              {t('submission.submissionNr', { nr })}
            </Box>
          ) : (
            t('submission.submissionNr', { nr })
          )}
        </Typography>
      </Box>

      <RenderedMarkdown sx={{ fontSize: '0.85rem' }} html={entry.feedback_html} />

      <Tooltip title={entry.model}>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
          {t('submission.aiFeedbackDisclaimer')}
        </Typography>
      </Tooltip>
    </Paper>
  )
}
