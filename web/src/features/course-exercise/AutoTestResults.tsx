import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Collapse,
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
import { parseOkV3, type OkV3Test, type V3Status } from './okV3.ts'
import usePrefersReducedMotion from '../../hooks/usePrefersReducedMotion.ts'

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

/**
 * `resetKey` identifies the assessment being revealed — pass the raw feedback. Without it the
 * effect keys on `active` alone, and a reveal that starts before the graded submission has landed
 * (the completion hold is short now, so the two can race) carries `revealedCount` and the
 * done-callback latch over onto the new, different list: tests past the old count render blank and
 * `onStaggerDone` never fires again.
 */
function useTypewriterReveal(tests: OkV3Test[], active: boolean, resetKey: string): TypewriterState {
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
  }, [active, resetKey])

  return state
}

export default function AutoTestResults({
  autoAssessment,
  staggerReveal = false,
  onStaggerDone,
  collapsible = false,
  defaultExpanded = true,
  headerAction,
}: {
  autoAssessment: AutomaticAssessmentResp
  staggerReveal?: boolean
  onStaggerDone?: () => void
  collapsible?: boolean
  defaultExpanded?: boolean
  /** Rendered at the right end of the header row. Clicks on it do not toggle the section. */
  headerAction?: ReactNode
}) {
  const { t } = useTranslation()
  const reduced = usePrefersReducedMotion()

  // `staggerReveal` is the parent asking for the reveal sequence; `animate` is whether the viewer
  // gets one. They are deliberately different: a reduced-motion viewer skips straight to the
  // finished list, but the parent still needs `onStaggerDone` to fire — it is what unfreezes the
  // sidebar and refetches — so that callback stays keyed on the request, not on the animation.
  const animate = staggerReveal && !reduced

  const v3 = useMemo(
    () => parseOkV3(autoAssessment.feedback),
    [autoAssessment.feedback],
  )

  const tests = v3?.tests ?? []
  const tw = useTypewriterReveal(tests, animate, autoAssessment.feedback ?? '')

  const firstFailIndex = useMemo(
    () => v3?.tests.findIndex(t => t.status === 'FAIL') ?? -1,
    [v3],
  )

  const [expanded, setExpanded] = useState<number | false>(() =>
    animate ? false : (firstFailIndex >= 0 ? firstFailIndex : false),
  )

  // Auto-expand first fail after all tests have been revealed
  const autoExpandedRef = useRef(false)
  useEffect(() => {
    if (!animate) {
      setExpanded(firstFailIndex >= 0 ? firstFailIndex : false)
      return
    }
    if (firstFailIndex >= 0 && tw.revealedCount >= tests.length && !autoExpandedRef.current) {
      autoExpandedRef.current = true
      const timer = setTimeout(() => setExpanded(firstFailIndex), 300)
      return () => clearTimeout(timer)
    }
  }, [firstFailIndex, animate, tw.revealedCount, tests.length])

  // Notify parent when typewriter is fully done
  const staggerDoneCalledRef = useRef(false)

  // A different assessment is a different reveal: the once-only latches belong to the old one.
  // Declared above the effects that read them so it runs first on the render that swaps them.
  useEffect(() => {
    staggerDoneCalledRef.current = false
    autoExpandedRef.current = false
  }, [autoAssessment.feedback])

  const onStaggerDoneRef = useRef(onStaggerDone)
  onStaggerDoneRef.current = onStaggerDone
  useEffect(() => {
    if (staggerReveal && tw.headerVisible && tw.revealedCount >= tests.length && !staggerDoneCalledRef.current) {
      staggerDoneCalledRef.current = true
      onStaggerDoneRef.current?.()
    }
  }, [staggerReveal, tw.headerVisible, tw.revealedCount, tests.length])

  // Per-test reveal helpers
  const isVisible = (i: number) => !animate || i < tw.revealedCount || i === tw.typingIndex
  const isInteractive = (i: number) => !animate || i < tw.revealedCount || (i === tw.typingIndex && tw.statusShown)
  const statusVisible = (i: number) => !animate || i < tw.revealedCount || (i === tw.typingIndex && tw.statusShown)
  const isStatusPopping = (i: number) => animate && i === tw.typingIndex && tw.statusShown
  const displayTitle = (i: number, full: string) => {
    if (!animate || i < tw.revealedCount) return full
    if (i === tw.typingIndex) return full.slice(0, tw.typedChars)
    return ''
  }

  const headerSx = animate ? {
    opacity: tw.headerVisible ? 1 : 0,
    transform: tw.headerVisible ? 'translateY(0)' : 'translateY(10px)',
    transition: 'opacity 0.4s ease-out, transform 0.4s ease-out',
  } : {}

  const allRevealed = !animate || tw.revealedCount >= tests.length
  const gradeSx = animate ? {
    opacity: allRevealed ? 1 : 0,
    transform: allRevealed ? 'translateY(0)' : 'translateY(8px)',
    transition: 'opacity 0.4s ease-out, transform 0.4s ease-out',
  } : {}

  const STORAGE_KEY = 'autoTestResultsExpanded'
  const [sectionOpen, setSectionOpen] = useState(() => {
    if (!collapsible) return defaultExpanded
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored !== null) return stored === '1'
    } catch { /* ignore */ }
    return defaultExpanded
  })

  return (
    <Box>
      <Box
        onClick={collapsible ? () => setSectionOpen((v) => {
          const next = !v
          try { localStorage.setItem(STORAGE_KEY, next ? '1' : '0') } catch { /* ignore */ }
          return next
        }) : undefined}
        sx={{
          display: 'flex',
          alignItems: 'center',
          mb: sectionOpen ? 1 : 0,
          ...headerSx,
          ...(collapsible && {
            cursor: 'pointer',
            userSelect: 'none',
          }),
        }}
      >
        {collapsible && (
          <ExpandMoreOutlined
            sx={{
              fontSize: 18,
              color: 'text.secondary',
              mr: 0.5,
              transform: sectionOpen ? 'rotate(0deg)' : 'rotate(-90deg)',
              transition: 'transform 0.2s',
            }}
          />
        )}
        <Typography variant="h6" sx={{ flexShrink: 0 }}>
          {t('submission.autoTests')}
        </Typography>

        {collapsible && tests.length > 0 ? (
          <>
            {/* Segmented test bar */}
            <Box sx={{ display: 'flex', height: 4, borderRadius: 2, overflow: 'hidden', ml: 2, gap: '2px' }}>
              {tests.map((test, i) => (
                <Box
                  key={i}
                  sx={{
                    width: 12,
                    bgcolor: test.status === 'PASS' ? 'success.main'
                      : test.status === 'FAIL' ? 'error.main'
                        : 'action.disabled',
                  }}
                />
              ))}
            </Box>
            <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0, ml: 1.5 }}>
              {tests.filter(t => t.status === 'PASS').length}/{tests.length}
            </Typography>
          </>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ ml: 'auto', ...gradeSx }}>
            {autoAssessment.grade} / 100
          </Typography>
        )}

        {headerAction && (
          <Box
            // The header itself toggles the section, so a click on the action has to stop there or
            // using it would also collapse the thing you wanted to look at.
            onClick={(e) => e.stopPropagation()}
            sx={{
              display: 'flex',
              alignItems: 'center',
              // The branch above either fills the row (test bar, left-packed) or already pushes its
              // score to the right. Only in the first case does this need to claim the gap itself.
              ml: collapsible && tests.length > 0 ? 'auto' : 0.5,
            }}
          >
            {headerAction}
          </Box>
        )}
      </Box>

      <Collapse in={!collapsible || sectionOpen}>
      {collapsible && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {t('general.points')}: {autoAssessment.grade} / 100
        </Typography>
      )}
      {v3 ? (
        <>
          {/* The student's own pre-check failure — a missing, empty or unparseable solution
              file, reported by tiivad before any test ran. Their error, shown verbatim: a
              SyntaxError naming their own file is exactly what they need to fix it. */}
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
                <AccordionDetails
                  sx={{
                    bgcolor: (th) =>
                      th.palette.mode === 'dark'
                        ? 'rgba(255,255,255,0.02)'
                        : 'rgba(0,0,0,0.02)',
                  }}
                >
                  <TestDetails test={test} t={t} />
                </AccordionDetails>
              </Accordion>
            )
          })}

          {animate && (
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
        // Not OK_V3, and legitimately so: the legacy graders (pygrader, imgrec, silmused) and
        // aae's own verdicts (time/memory exceeded) answer in plain text. Render it as the
        // assessment it is — a genuine infrastructure failure never reaches this component,
        // because core records it as autograde_status FAILED with no assessment at all.
        <Paper
          variant="outlined"
          sx={{ ...monoSx, maxHeight: 'none', ...headerSx }}
        >
          {autoAssessment.feedback}
        </Paper>
      ) : null}
      </Collapse>
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
