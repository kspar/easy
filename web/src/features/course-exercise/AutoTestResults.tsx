import { useEffect, useMemo, useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Paper,
  Typography,
} from '@mui/material'
import {
  ExpandMoreOutlined,
  CheckCircle,
  CheckCircleOutlined,
  Cancel,
  CancelOutlined,
  RemoveCircleOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { AutomaticAssessmentResp } from '../../api/types.ts'

type V3Status = 'PASS' | 'FAIL' | 'SKIP'

interface OkV3Check {
  title: string
  status: V3Status
  feedback: string | null
}

interface OkV3File {
  name: string
  content: string
}

interface OkV3Test {
  title: string
  status: V3Status
  user_inputs: string[]
  created_files: OkV3File[]
  converted_submission: string | null
  actual_output: string | null
  exception_message: string | null
  checks: OkV3Check[]
}

interface OkV3Feedback {
  result_type: 'OK_V3'
  producer: string
  pre_evaluate_error: string | null
  points: number
  tests: OkV3Test[]
}

function parseOkV3(feedback: string | null): OkV3Feedback | null {
  if (!feedback) return null
  try {
    const parsed = JSON.parse(feedback)
    if (parsed?.result_type === 'OK_V3' && Array.isArray(parsed.tests)) {
      return parsed as OkV3Feedback
    }
    return null
  } catch {
    return null
  }
}

function StatusIcon({ status }: { status: V3Status }) {
  if (status === 'PASS') return <CheckCircle color="success" fontSize="small" />
  if (status === 'FAIL') return <Cancel color="error" fontSize="small" />
  return <RemoveCircleOutlined color="disabled" fontSize="small" />
}

function CheckIcon({ status }: { status: V3Status }) {
  if (status === 'PASS') return <CheckCircleOutlined color="success" sx={{ fontSize: '1rem', opacity: 0.7 }} />
  if (status === 'FAIL') return <CancelOutlined color="error" sx={{ fontSize: '1rem', opacity: 0.7 }} />
  return <RemoveCircleOutlined color="disabled" sx={{ fontSize: '1rem', opacity: 0.7 }} />
}

const monoSx = {
  p: 1,
  bgcolor: 'action.hover',
  whiteSpace: 'pre-wrap' as const,
  fontFamily: 'monospace',
  fontSize: '0.8rem',
  maxHeight: 200,
  overflow: 'auto',
}

export default function AutoTestResults({
  autoAssessment,
}: {
  autoAssessment: AutomaticAssessmentResp
}) {
  const { t } = useTranslation()

  const v3 = useMemo(
    () => parseOkV3(autoAssessment.feedback),
    [autoAssessment.feedback],
  )

  const firstFailIndex = useMemo(
    () => v3?.tests.findIndex(t => t.status === 'FAIL') ?? -1,
    [v3],
  )

  const [expanded, setExpanded] = useState<number | false>(() =>
    firstFailIndex >= 0 ? firstFailIndex : false,
  )

  useEffect(() => {
    setExpanded(firstFailIndex >= 0 ? firstFailIndex : false)
  }, [firstFailIndex])

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('submission.autoTests')}
      </Typography>

      <Typography variant="body2" sx={{ mb: 2 }}>
        {t('submission.autoGrade')}: <strong>{autoAssessment.grade} / 100</strong>
      </Typography>

      {v3 ? (
        <>
          {v3.pre_evaluate_error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              <Box component="pre" sx={{ m: 0, fontFamily: 'monospace', fontSize: '0.8rem', whiteSpace: 'pre-wrap' }}>
                {v3.pre_evaluate_error}
              </Box>
            </Alert>
          )}

          {v3.tests.map((test, i) => (
            <Accordion
              key={i}
              disableGutters
              variant="outlined"
              expanded={expanded === i}
              onChange={(_, isExpanded) => setExpanded(isExpanded ? i : false)}
              sx={{ '&:before': { display: 'none' } }}
            >
              <AccordionSummary
                expandIcon={<ExpandMoreOutlined />}
                sx={{
                  transition: 'background-color 0.15s',
                  '&:hover': { bgcolor: 'action.hover' },
                  '&.Mui-expanded': { bgcolor: 'action.hover' },
                }}
              >
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <StatusIcon status={test.status} />
                  <Typography variant="body2" fontWeight={expanded === i ? 600 : undefined}>{test.title}</Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails>
                <TestDetails test={test} t={t} />
              </AccordionDetails>
            </Accordion>
          ))}
        </>
      ) : autoAssessment.feedback ? (
        <Paper
          variant="outlined"
          sx={{ ...monoSx, maxHeight: 'none' }}
        >
          {autoAssessment.feedback}
        </Paper>
      ) : null}
    </Box>
  )
}

function TestDetails({ test, t }: { test: OkV3Test; t: (k: string) => string }) {
  const checkFeedbacks = test.checks.filter(c => c.feedback)
  const hasOutput = test.actual_output && test.actual_output.trim()

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      {checkFeedbacks.length > 0 && (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
          {checkFeedbacks.map((check, i) => (
            <Box key={i} sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
              <Box sx={{ flexShrink: 0, mt: 0.15 }}>
                <CheckIcon status={check.status} />
              </Box>
              <Typography variant="body2">{check.feedback}</Typography>
            </Box>
          ))}
        </Box>
      )}

      {test.exception_message && (
        <Box>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
            {t('submission.exception')}
          </Typography>
          <Paper variant="outlined" sx={monoSx}>{test.exception_message}</Paper>
        </Box>
      )}

      {hasOutput && (
        <Box>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
            {t('submission.actualOutput')}
          </Typography>
          <Paper variant="outlined" sx={monoSx}>{test.actual_output}</Paper>
        </Box>
      )}

      {test.created_files?.map((file, i) => (
        <Box key={i}>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
            {file.name}
          </Typography>
          <Paper variant="outlined" sx={monoSx}>{file.content}</Paper>
        </Box>
      ))}
    </Box>
  )
}
