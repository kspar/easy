import { useEffect, useMemo, useRef, useState } from 'react'
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

// --- Typewriter reveal ---

const CHAR_SPEED = 25   // ms per character
const STATUS_PAUSE = 300 // ms after title typed before status icon
const NEXT_TEST_PAUSE = 350 // ms after status before next test starts
const HEADER_DELAY = 100

interface TypewriterState {
  headerVisible: boolean
  revealedCount: number  // tests fully done (title + status)
  typingIndex: number    // currently typing this test (-1 = none)
  typedChars: number
  statusShown: boolean   // status icon visible for typingIndex
}

function useTypewriterReveal(tests: OkV3Test[], active: boolean): TypewriterState {
  const [state, setState] = useState<TypewriterState>(() =>
    active
      ? { headerVisible: false, revealedCount: 0, typingIndex: -1, typedChars: 0, statusShown: false }
      : { headerVisible: true, revealedCount: tests.length, typingIndex: -1, typedChars: 0, statusShown: false },
  )
  const testsRef = useRef(tests)
  testsRef.current = tests

  useEffect(() => {
    if (!active) {
      setState({ headerVisible: true, revealedCount: testsRef.current.length, typingIndex: -1, typedChars: 0, statusShown: false })
      return
    }

    setState({ headerVisible: false, revealedCount: 0, typingIndex: -1, typedChars: 0, statusShown: false })
    const timers: ReturnType<typeof setTimeout>[] = []
    const schedule = (ms: number, fn: () => void) => { timers.push(setTimeout(fn, ms)) }

    let t = HEADER_DELAY
    schedule(t, () => setState(s => ({ ...s, headerVisible: true })))
    t += 400

    for (let i = 0; i < testsRef.current.length; i++) {
      const title = testsRef.current[i].title

      // Start typing this test
      schedule(t, () => setState(s => ({ ...s, typingIndex: i, typedChars: 0, statusShown: false })))

      // Type each char
      for (let c = 1; c <= title.length; c++) {
        t += CHAR_SPEED
        const chars = c
        schedule(t, () => setState(s => ({ ...s, typedChars: chars })))
      }

      // Reveal status icon
      t += STATUS_PAUSE
      schedule(t, () => setState(s => ({ ...s, statusShown: true })))

      // Finish this test
      t += NEXT_TEST_PAUSE
      const done = i + 1
      schedule(t, () => setState(s => ({ ...s, revealedCount: done, typingIndex: -1 })))
    }

    return () => timers.forEach(clearTimeout)
  }, [active])

  return state
}

export default function AutoTestResults({
  autoAssessment,
  staggerReveal = false,
  onStaggerDone,
}: {
  autoAssessment: AutomaticAssessmentResp
  staggerReveal?: boolean
  onStaggerDone?: () => void
}) {
  const { t } = useTranslation()

  const v3 = useMemo(
    () => parseOkV3(autoAssessment.feedback),
    [autoAssessment.feedback],
  )

  const tests = v3?.tests ?? []
  const tw = useTypewriterReveal(tests, staggerReveal)

  const firstFailIndex = useMemo(
    () => v3?.tests.findIndex(t => t.status === 'FAIL') ?? -1,
    [v3],
  )

  const [expanded, setExpanded] = useState<number | false>(() =>
    staggerReveal ? false : (firstFailIndex >= 0 ? firstFailIndex : false),
  )

  // Auto-expand first fail after all tests have been revealed
  const autoExpandedRef = useRef(false)
  useEffect(() => {
    if (!staggerReveal) {
      setExpanded(firstFailIndex >= 0 ? firstFailIndex : false)
      return
    }
    if (firstFailIndex >= 0 && tw.revealedCount >= tests.length && !autoExpandedRef.current) {
      autoExpandedRef.current = true
      const timer = setTimeout(() => setExpanded(firstFailIndex), 300)
      return () => clearTimeout(timer)
    }
  }, [firstFailIndex, staggerReveal, tw.revealedCount, tests.length])

  // Notify parent when typewriter is fully done
  const staggerDoneCalledRef = useRef(false)
  const onStaggerDoneRef = useRef(onStaggerDone)
  onStaggerDoneRef.current = onStaggerDone
  useEffect(() => {
    if (staggerReveal && tw.headerVisible && tw.revealedCount >= tests.length && !staggerDoneCalledRef.current) {
      staggerDoneCalledRef.current = true
      onStaggerDoneRef.current?.()
    }
  }, [staggerReveal, tw.headerVisible, tw.revealedCount, tests.length])

  // Per-test reveal helpers
  const isVisible = (i: number) => !staggerReveal || i < tw.revealedCount || i === tw.typingIndex
  const isInteractive = (i: number) => !staggerReveal || i < tw.revealedCount || (i === tw.typingIndex && tw.statusShown)
  const statusVisible = (i: number) => !staggerReveal || i < tw.revealedCount || (i === tw.typingIndex && tw.statusShown)
  const isStatusPopping = (i: number) => staggerReveal && i === tw.typingIndex && tw.statusShown
  const displayTitle = (i: number, full: string) => {
    if (!staggerReveal || i < tw.revealedCount) return full
    if (i === tw.typingIndex) return full.slice(0, tw.typedChars)
    return ''
  }

  const headerSx = staggerReveal ? {
    opacity: tw.headerVisible ? 1 : 0,
    transform: tw.headerVisible ? 'translateY(0)' : 'translateY(10px)',
    transition: 'opacity 0.4s ease-out, transform 0.4s ease-out',
  } : {}

  const allRevealed = !staggerReveal || tw.revealedCount >= tests.length
  const gradeSx = staggerReveal ? {
    opacity: allRevealed ? 1 : 0,
    transform: allRevealed ? 'translateY(0)' : 'translateY(8px)',
    transition: 'opacity 0.4s ease-out, transform 0.4s ease-out',
  } : {}

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', mb: 1, ...headerSx }}>
        <Typography variant="h6">
          {t('submission.autoTests')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ ml: 'auto', ...gradeSx }}>
          {autoAssessment.grade} / 100
        </Typography>
      </Box>

      {v3 ? (
        <>
          {v3.pre_evaluate_error && (
            <Alert severity="error" sx={{ mb: 2, ...headerSx }}>
              <Box component="pre" sx={{ m: 0, fontFamily: 'monospace', fontSize: '0.8rem', whiteSpace: 'pre-wrap' }}>
                {v3.pre_evaluate_error}
              </Box>
            </Alert>
          )}

          {v3.tests.map((test, i) => {
            if (!isVisible(i)) return null
            const interactive = isInteractive(i)

            return (
              <Accordion
                key={i}
                disableGutters
                variant="outlined"
                expanded={expanded === i}
                onChange={(_, isExpanded) => {
                  if (interactive) setExpanded(isExpanded ? i : false)
                }}
                sx={{ '&:before': { display: 'none' } }}
              >
                <AccordionSummary
                  expandIcon={interactive ? <ExpandMoreOutlined /> : <Box sx={{ width: 24, height: 24 }} />}
                  sx={{
                    transition: 'background-color 0.15s',
                    '&:hover': { bgcolor: interactive ? 'action.hover' : 'transparent' },
                    '&.Mui-expanded': { bgcolor: 'action.hover' },
                    ...(!interactive && { cursor: 'default', '& .MuiAccordionSummary-content': { cursor: 'default' } }),
                  }}
                >
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    {statusVisible(i) ? (
                      <Box sx={{
                        display: 'flex',
                        ...(isStatusPopping(i) && { animation: 'atrStatusPop 0.3s cubic-bezier(0.34,1.56,0.64,1)' }),
                      }}>
                        <StatusIcon status={test.status} />
                      </Box>
                    ) : (
                      <Box sx={{ width: 20, height: 20 }} />
                    )}
                    <Typography variant="body2" fontWeight={expanded === i ? 600 : undefined}>
                      {displayTitle(i, test.title)}
                    </Typography>
                  </Box>
                </AccordionSummary>
                <AccordionDetails>
                  <TestDetails test={test} t={t} />
                </AccordionDetails>
              </Accordion>
            )
          })}

          {staggerReveal && (
            <style>{`
              @keyframes atrStatusPop {
                from { transform: scale(0); opacity: 0; }
                50% { transform: scale(1.2); opacity: 1; }
                to { transform: scale(1); opacity: 1; }
              }
            `}</style>
          )}
        </>
      ) : autoAssessment.feedback ? (
        <Paper
          variant="outlined"
          sx={{ ...monoSx, maxHeight: 'none', ...headerSx }}
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
