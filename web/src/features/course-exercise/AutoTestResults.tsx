import { useMemo } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Paper,
  Typography,
} from '@mui/material'
import {
  ExpandMoreOutlined,
  CheckCircle,
  Cancel,
  HelpOutlineOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { AutomaticAssessmentResp } from '../../api/types.ts'

interface V3Check {
  point_nr: number
  feedback: string
  stdout: string
  stderr: string
  status: 'PASSED' | 'FAILED' | 'UNKNOWN'
}

interface V3Group {
  points: number
  max_points: number
  checks: V3Check[]
}

interface V3Feedback {
  groups: V3Group[]
}

function parseAutoFeedback(feedback: string | null): V3Feedback | null {
  if (!feedback) return null
  try {
    const parsed = JSON.parse(feedback)
    if (parsed?.groups && Array.isArray(parsed.groups)) {
      return parsed as V3Feedback
    }
    return null
  } catch {
    return null
  }
}

export default function AutoTestResults({
  autoAssessment,
}: {
  autoAssessment: AutomaticAssessmentResp
}) {
  const { t } = useTranslation()

  const v3 = useMemo(
    () => parseAutoFeedback(autoAssessment.feedback),
    [autoAssessment.feedback],
  )

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('submission.autoTests')}
      </Typography>

      <Typography variant="body2" sx={{ mb: 2 }}>
        {t('submission.autoGrade')}: <strong>{autoAssessment.grade} / 100</strong>
      </Typography>

      {v3 ? (
        v3.groups.flatMap((group, gi) =>
          group.checks.map((check, ci) => {
            const statusIcon =
              check.status === 'PASSED' ? (
                <CheckCircle color="success" fontSize="small" />
              ) : check.status === 'FAILED' ? (
                <Cancel color="error" fontSize="small" />
              ) : (
                <HelpOutlineOutlined color="disabled" fontSize="small" />
              )

            const label =
              check.status === 'PASSED'
                ? t('submission.testPassed')
                : check.status === 'FAILED'
                  ? t('submission.testFailed')
                  : check.status

            return (
              <Accordion
                key={`${gi}-${ci}`}
                disableGutters
                variant="outlined"
                sx={{ '&:before': { display: 'none' } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1,
                      width: '100%',
                    }}
                  >
                    {statusIcon}
                    <Typography variant="body2">
                      Test {check.point_nr}
                    </Typography>
                    <Typography
                      variant="body2"
                      color="text.secondary"
                      sx={{ ml: 'auto', mr: 1 }}
                    >
                      {label}
                    </Typography>
                  </Box>
                </AccordionSummary>
                <AccordionDetails>
                  {check.feedback && (
                    <Typography variant="body2" sx={{ mb: 1 }}>
                      {check.feedback}
                    </Typography>
                  )}
                  {check.stdout && (
                    <Box sx={{ mb: 1 }}>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                      >
                        {t('submission.stdout')}
                      </Typography>
                      <Paper
                        variant="outlined"
                        sx={{
                          p: 1,
                          bgcolor: 'action.hover',
                          whiteSpace: 'pre-wrap',
                          fontFamily: 'monospace',
                          fontSize: '0.8rem',
                          maxHeight: 200,
                          overflow: 'auto',
                        }}
                      >
                        {check.stdout}
                      </Paper>
                    </Box>
                  )}
                  {check.stderr && (
                    <Box>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                      >
                        {t('submission.stderr')}
                      </Typography>
                      <Paper
                        variant="outlined"
                        sx={{
                          p: 1,
                          bgcolor: 'action.hover',
                          whiteSpace: 'pre-wrap',
                          fontFamily: 'monospace',
                          fontSize: '0.8rem',
                          maxHeight: 200,
                          overflow: 'auto',
                        }}
                      >
                        {check.stderr}
                      </Paper>
                    </Box>
                  )}
                </AccordionDetails>
              </Accordion>
            )
          }),
        )
      ) : autoAssessment.feedback ? (
        <Paper
          variant="outlined"
          sx={{
            p: 1.5,
            bgcolor: 'action.hover',
            whiteSpace: 'pre-wrap',
            fontFamily: 'monospace',
            fontSize: '0.85rem',
          }}
        >
          {autoAssessment.feedback}
        </Paper>
      ) : null}
    </Box>
  )
}
