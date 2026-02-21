import { useEffect, useRef, useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Chip,
  CircularProgress,
  Alert,
  Button,
  Divider,
  Typography,
} from '@mui/material'
import { ExpandMoreOutlined, ContentCopyOutlined } from '@mui/icons-material'
import { useTheme } from '@mui/material/styles'
import { EditorView } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { languageFromFilename } from './editorLanguage.ts'
import { oneDark } from '@codemirror/theme-one-dark'
import { basicSetup } from 'codemirror'
import { useTranslation } from 'react-i18next'
import RelativeTime from '../../components/RelativeTime.tsx'
import AutoTestResults from './AutoTestResults.tsx'
import { useSubmissions } from '../../api/exercises.ts'
import type { AutomaticAssessmentResp } from '../../api/types.ts'

export default function PreviousSubmissions({
  courseId,
  courseExerciseId,
  solutionFileName,
  onRestore,
}: {
  courseId: string
  courseExerciseId: string
  solutionFileName: string
  onRestore?: (solution: string) => void
}) {
  const { t } = useTranslation()
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
        <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
          <Typography variant="h6">
            {t('submission.previousSubmissions')} ({submissions.length})
          </Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ p: 0 }}>
          {submissions.map((sub) => (
            <SubmissionItem
              key={sub.id}
              submission={sub}
              solutionFileName={solutionFileName}
              onRestore={onRestore}
            />
          ))}
        </AccordionDetails>
      </Accordion>
    </Box>
  )
}

function SubmissionItem({
  submission,
  solutionFileName,
  onRestore,
}: {
  submission: {
    id: string
    number: number
    solution: string
    submission_time: string
    autograde_status: string
    auto_assessment: AutomaticAssessmentResp | null
  }
  solutionFileName: string
  onRestore?: (solution: string) => void
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
        <Typography variant="caption" color="text.secondary" component="span">
          <RelativeTime date={submission.submission_time} />
        </Typography>
        <Box sx={{ ml: 'auto', display: 'flex', alignItems: 'center', gap: 1 }}>
          {submission.auto_assessment != null && (
            <Chip
              label={`${submission.auto_assessment.grade} / 100`}
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
        <>
          <ReadOnlyEditor code={submission.solution} solutionFileName={solutionFileName} />
          {onRestore && (
            <Button
              size="small"
              startIcon={<ContentCopyOutlined />}
              onClick={() => {
                onRestore(submission.solution)
                document.querySelector('.cm-editor')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
              }}
              sx={{ mx: 2, mt: 1, mb: 1 }}
            >
              {t('submission.restore')}
            </Button>
          )}
          {submission.auto_assessment && (
            <Box sx={{ mx: 2, mt: 1, mb: 2 }}>
              <AutoTestResults autoAssessment={submission.auto_assessment} />
            </Box>
          )}
        </>
      )}
    </Box>
  )
}

function ReadOnlyEditor({ code, solutionFileName }: { code: string; solutionFileName: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const theme = useTheme()

  useEffect(() => {
    if (!ref.current) return
    let cancelled = false
    let view: EditorView | null = null

    languageFromFilename(solutionFileName).then((lang) => {
      if (cancelled || !ref.current) return

      const extensions = [
        basicSetup,
        lang,
        EditorView.editable.of(false),
        EditorState.readOnly.of(true),
        EditorView.lineWrapping,
      ]
      if (theme.palette.mode === 'dark') {
        extensions.push(oneDark)
      }

      view = new EditorView({
        state: EditorState.create({ doc: code, extensions }),
        parent: ref.current,
      })
    })

    return () => {
      cancelled = true
      view?.destroy()
    }
  }, [code, solutionFileName, theme.palette.mode])

  return (
    <Box
      ref={ref}
      sx={{
        mx: 2,
        mt: 0.5,
        border: 1,
        borderColor: 'divider',
        borderRadius: 1,
        overflow: 'hidden',
        '& .cm-editor': { maxHeight: 300, fontSize: '0.85rem' },
        '& .cm-focused': { outline: 'none' },
      }}
    />
  )
}
