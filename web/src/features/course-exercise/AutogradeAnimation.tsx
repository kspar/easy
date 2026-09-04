import { useEffect, useRef, useState } from 'react'
import { Box, Typography } from '@mui/material'
import { useTheme } from '@mui/material/styles'
import { alpha } from '@mui/material/styles'
// The brand ramp, not Material green (EZ-1798, one green): this animation sat right beside
// GREEN[700] buttons while glowing in a different family. Steps map by role, not by number —
// Material's 300/600 midpoints land on the ramp's like-for-like lightness.
import { GREEN } from '../../theme/theme.ts'
import { useTranslation } from 'react-i18next'
import usePrefersReducedMotion from '../../hooks/usePrefersReducedMotion.ts'
import SafeText from '../../components/SafeText.tsx'

type Phase = 'compile' | 'test' | 'analyze'

const PHASES: Phase[] = ['compile', 'test', 'analyze']
const PHASE_DURATIONS: Record<Phase, number> = { compile: 3000, test: 4000, analyze: 3000 }
const CYCLE_DURATION = 10000

function useAutogradePhase(active: boolean, reduced: boolean) {
  const [phase, setPhase] = useState<Phase>('compile')
  const [progress, setProgress] = useState(0)
  const startRef = useRef(0)
  const rafRef = useRef(0)

  useEffect(() => {
    if (!active) {
      setPhase('compile')
      setProgress(0)
      return
    }

    // Reduced motion: the phase label still steps, because it is the only thing telling the student
    // the grader is working and a status that goes silent is worse than one that moves. What goes is
    // the continuous part — a requestAnimationFrame loop re-rendering at 60 Hz to slide a progress
    // bar. The bar now steps once per phase instead, three moves rather than six hundred.
    if (reduced) {
      let i = 0
      setPhase(PHASES[0])
      setProgress(0)
      const timers: ReturnType<typeof setTimeout>[] = []
      const step = () => {
        i = (i + 1) % PHASES.length
        setPhase(PHASES[i])
        setProgress(i / PHASES.length)
        timers.push(setTimeout(step, PHASE_DURATIONS[PHASES[i]]))
      }
      timers.push(setTimeout(step, PHASE_DURATIONS[PHASES[0]]))
      return () => timers.forEach(clearTimeout)
    }

    startRef.current = performance.now()

    const tick = (now: number) => {
      const elapsed = (now - startRef.current) % CYCLE_DURATION
      let acc = 0
      for (const p of PHASES) {
        acc += PHASE_DURATIONS[p]
        if (elapsed < acc) {
          setPhase(p)
          const phaseStart = acc - PHASE_DURATIONS[p]
          const phaseProgress = (elapsed - phaseStart) / PHASE_DURATIONS[p]
          const phaseIndex = PHASES.indexOf(p)
          setProgress((phaseIndex + phaseProgress) / PHASES.length)
          break
        }
      }
      rafRef.current = requestAnimationFrame(tick)
    }

    rafRef.current = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(rafRef.current)
  }, [active, reduced])

  return { phase, progress }
}

// --- SVG sub-animations ---

// Every SVG below animates from an invisible first frame — opacity 0, or a stroke dashed entirely
// out of view — to a visible last one. So `reduced` cannot simply drop the animation: that would
// leave the frame it starts on, which is nothing at all. Each takes the flag and renders its own
// finished state instead, which is the picture the animation was travelling towards anyway.
function PrepareAnimation({ dark, reduced }: { dark: boolean; reduced: boolean }) {
  const fill = dark ? GREEN[300] : GREEN[600]
  const drop = (delay: string) =>
    reduced ? undefined : { animation: `logoDrop 0.45s cubic-bezier(0.34,1.56,0.64,1) ${delay} forwards` }
  const restOpacity = reduced ? 0.8 : 0

  // Exact Lahendus logo shapes from logo.svg (24x24 viewBox),
  // scaled 3.2x and centered in the 200x100 animation viewBox.
  // s=3.2, ox=62, oy=12
  return (
    <svg viewBox="0 0 200 100" width="100%" height="100%" key="compile">
      {/* Bottom-left block */}
      <rect
        x="62" y="56.5" width="32.3" height="32.3"
        fill={fill} opacity={restOpacity}
        style={drop('0s')}
      />

      {/* Bottom-right block */}
      <rect
        x="106.5" y="56.5" width="32" height="32.3"
        fill={fill} opacity={restOpacity}
        style={drop('0.25s')}
      />

      {/* Top block (pentagon — page body with fold cutout) */}
      <polygon
        points="84.4,12 84.4,44.3 116.4,44.3 116.4,34.4 88.9,12"
        fill={fill} opacity={restOpacity}
        style={drop('0.5s')}
      />

      {/* Fold triangle (drops in separately) */}
      <polygon
        points="97.2,12 116.4,27.4 116.4,12"
        fill={fill} opacity={restOpacity}
        style={drop('0.75s')}
      />

      <style>{`
        @keyframes logoDrop {
          from { opacity: 0; transform: translateY(-20px); }
          to { opacity: 0.8; transform: translateY(0); }
        }
      `}</style>
    </svg>
  )
}

function TestAnimation({ dark, reduced, t }: { dark: boolean; reduced: boolean; t: (k: string, opts?: Record<string, unknown>) => string }) {
  const bgColor = dark ? '#1a1a2e' : '#1b2631'
  const textColor = dark ? GREEN[300] : GREEN[400]
  const dimColor = dark ? '#667' : '#8899aa'

  const lines = [
    { text: `> ${t('submission.autogradeRunTest', { nr: 1 })}`, delay: '0s', color: dimColor },
    { text: '  OK', delay: '0.6s', color: textColor },
    { text: `> ${t('submission.autogradeRunTest', { nr: 2 })}`, delay: '1.2s', color: dimColor },
    { text: '  OK', delay: '1.8s', color: textColor },
    { text: `> ${t('submission.autogradeRunTest', { nr: 3 })}`, delay: '2.4s', color: dimColor },
    { text: '  OK', delay: '3.0s', color: textColor },
  ]

  return (
    <svg viewBox="0 0 200 100" width="100%" height="100%" key="test">
      {/* Terminal card */}
      <rect x="15" y="5" width="170" height="90" rx="5" fill={bgColor} />
      {/* Title bar dots */}
      <circle cx="27" cy="14" r="3" fill="#e74c3c" opacity="0.7" />
      <circle cx="37" cy="14" r="3" fill="#f39c12" opacity="0.7" />
      <circle cx="47" cy="14" r="3" fill="#2ecc71" opacity="0.7" />

      {lines.map((line, i) => (
        <text
          key={i}
          x="24"
          y={32 + i * 11}
          fontFamily="monospace"
          fontSize="8"
          fill={line.color}
          opacity={reduced ? 1 : 0}
        >
          {line.text}
          {!reduced && (
            <animate attributeName="opacity" from="0" to="1" dur="0.3s" begin={line.delay} fill="freeze" />
          )}
        </text>
      ))}
    </svg>
  )
}

function AnalyzeAnimation({ dark, reduced }: { dark: boolean; reduced: boolean }) {
  const classA = dark ? GREEN[300] : GREEN[600]
  const classB = dark ? GREEN[200] : GREEN[400]
  const lineColor = dark ? GREEN[100] : GREEN[800]

  // Two clusters with overlap near the boundary — a couple of points
  // are deliberately on the "wrong" side for realism
  const clusterA = [
    { x: 40, y: 25 }, { x: 55, y: 18 }, { x: 48, y: 38 },
    { x: 65, y: 30 }, { x: 35, y: 48 }, { x: 72, y: 42 },
    { x: 52, y: 12 }, { x: 82, y: 35 },
    { x: 90, y: 55 },  // outlier on B's side
  ]
  const clusterB = [
    { x: 110, y: 55 }, { x: 128, y: 68 }, { x: 142, y: 52 },
    { x: 118, y: 78 }, { x: 152, y: 65 }, { x: 135, y: 82 },
    { x: 158, y: 55 }, { x: 100, y: 70 },
    { x: 78, y: 50 },  // outlier on A's side
  ]
  const allDots = [
    ...clusterA.map((p) => ({ ...p, color: classA })),
    ...clusterB.map((p) => ({ ...p, color: classB })),
  ]

  // S-curve decision boundary — separates clusters imperfectly
  const boundaryD = 'M 22,10 C 55,25 75,55 95,50 S 130,40 178,92'
  const boundaryLen = 280

  return (
    <svg viewBox="0 0 200 100" width="100%" height="100%" key="analyze">
      {/* Scatter dots */}
      {allDots.map((p, i) => (
        <circle
          key={i}
          cx={p.x}
          cy={p.y}
          r="4"
          fill={p.color}
          opacity={reduced ? 0.8 : 0}
          style={reduced ? undefined : { animation: `dotPop 0.3s ease-out ${i * 0.05}s forwards` }}
        />
      ))}

      {/* Curved classification boundary drawing in */}
      <path
        d={boundaryD}
        fill="none"
        stroke={lineColor}
        strokeWidth="2"
        strokeDasharray={`4 3`}
        strokeLinecap="round"
        opacity="0.6"
        style={{
          strokeDasharray: boundaryLen,
          strokeDashoffset: reduced ? 0 : boundaryLen,
          ...(reduced ? {} : { animation: `boundaryDraw 1.5s ease-out 1.1s forwards` }),
        }}
      />

      <style>{`
        @keyframes dotPop {
          from { opacity: 0; r: 0; }
          to { opacity: 0.8; r: 4; }
        }
        @keyframes boundaryDraw {
          to { stroke-dashoffset: 0; }
        }
      `}</style>
    </svg>
  )
}

// Drawing time matters here, because `hold` below unmounts the whole panel when it expires: the
// circle and tick have to be finished before that happens or the student watches a checkmark get
// cut off mid-stroke. Circle 0.4s, tick 0.3s starting at 0.25s — done at 550ms, inside a 700ms hold.
function CompletionCheckmark({ dark, reduced }: { dark: boolean; reduced: boolean }) {
  const strokeColor = dark ? GREEN[300] : GREEN[600]
  const drawn = (len: number, anim: string) =>
    reduced
      ? { strokeDashoffset: 0 }
      : { strokeDashoffset: len, animation: anim }

  return (
    <svg viewBox="0 0 200 100" width="100%" height="100%" key="done">
      {/* Circle outline draws in */}
      <circle
        cx="100"
        cy="50"
        r="32"
        fill="none"
        stroke={strokeColor}
        strokeWidth="3"
        strokeDasharray="201"
        style={drawn(201, 'circDraw 0.4s ease-out forwards')}
      />
      {/* Checkmark draws after delay */}
      <path
        d="M82 50 L95 63 L120 38"
        fill="none"
        stroke={strokeColor}
        strokeWidth="3.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeDasharray="60"
        style={drawn(60, 'checkDraw 0.3s ease-out 0.25s forwards')}
      />
      <style>{`
        @keyframes circDraw {
          to { stroke-dashoffset: 0; }
        }
        @keyframes checkDraw {
          to { stroke-dashoffset: 0; }
        }
      `}</style>
    </svg>
  )
}

// --- Phase stepper ---

function PhaseStepper({
  activePhase,
  completed,
  dark,
  reduced,
}: {
  activePhase: Phase
  completed: boolean
  dark: boolean
  reduced: boolean
}) {
  const { t } = useTranslation()
  const labels: Record<Phase, string> = {
    compile: t('submission.autogradePhaseCompile'),
    test: t('submission.autogradePhaseTest'),
    analyze: t('submission.autogradePhaseAnalyze'),
  }
  const activeIdx = completed ? PHASES.length : PHASES.indexOf(activePhase)

  const filledColor = dark ? GREEN[400] : GREEN[600]
  const activeColor = dark ? GREEN[300] : GREEN[500]
  const inactiveColor = dark ? '#555' : '#ccc'
  const textActive = dark ? GREEN[200] : GREEN[800]
  const textInactive = dark ? '#888' : '#999'

  // Progress line: fraction of the track that should be filled
  const progressFrac = completed ? 1 : activeIdx / (PHASES.length - 1)

  return (
    <Box sx={{ position: 'relative', py: 1 }}>
      {/* Horizontal track line behind circles, vertically centered on the 28px circles */}
      {/* Track spans from center of first circle to center of last circle */}
      <Box sx={{ position: 'absolute', top: 'calc(8px + 14px - 1px)', left: 0, right: 0, display: 'flex', justifyContent: 'center' }}>
        <Box sx={{ width: 100 * (PHASES.length - 1), position: 'relative', height: 2 }}>
          {/* Background track */}
          <Box sx={{ position: 'absolute', inset: 0, bgcolor: inactiveColor, borderRadius: 1 }} />
          {/* Filled portion */}
          <Box sx={{ position: 'absolute', top: 0, bottom: 0, left: 0, width: `${progressFrac * 100}%`, bgcolor: filledColor, borderRadius: 1, transition: 'width 0.3s, background-color 0.3s' }} />
        </Box>
      </Box>

      {/* Circles + labels — fixed-width columns so all phases are equal */}
      <Box sx={{ position: 'relative', display: 'flex', justifyContent: 'center' }}>
        {PHASES.map((p, i) => {
          const isPast = i < activeIdx
          const isActive = !completed && i === activeIdx
          const color = isPast || completed ? filledColor : isActive ? activeColor : inactiveColor

          return (
            <Box key={p} sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0.5, width: 100 }}>
              <Box
                sx={{
                  width: 28,
                  height: 28,
                  borderRadius: '50%',
                  bgcolor: isPast || completed ? color : dark ? '#1a1a2e' : '#fff',
                  border: `2.5px solid ${color}`,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  transition: 'all 0.3s',
                  zIndex: 1,
                  // The ring's resting state is its own first keyframe, so dropping the animation
                  // leaves the step still marked as the active one — no JS branch needed here.
                  ...(isActive && {
                    boxShadow: `0 0 0 4px ${dark ? alpha(GREEN[500], 0.25) : alpha(GREEN[500], 0.2)}`,
                    ...(reduced ? {} : { animation: 'pulseRing 1.5s ease-in-out infinite' }),
                  }),
                }}
              >
                {(isPast || completed) && (
                  <svg width="14" height="14" viewBox="0 0 14 14">
                    <path
                      d="M3 7 L6 10 L11 4"
                      fill="none"
                      stroke={dark ? '#1a1a2e' : '#fff'}
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                )}
              </Box>
              <Typography
                variant="caption"
                sx={{
                  fontSize: '0.65rem',
                  fontWeight: isActive ? 600 : 400,
                  color: isPast || isActive || completed ? textActive : textInactive,
                  transition: 'color 0.3s',
                  textTransform: 'uppercase',
                  letterSpacing: '0.03em',
                }}
              >
                {labels[p]}
              </Typography>
            </Box>
          )
        })}
      </Box>
      <style>{`
        @keyframes pulseRing {
          0%, 100% { box-shadow: 0 0 0 4px ${dark ? alpha(GREEN[500], 0.25) : alpha(GREEN[500], 0.2)}; }
          50% { box-shadow: 0 0 0 8px ${alpha(GREEN[500], 0.08)}; }
        }
      `}</style>
    </Box>
  )
}

// --- Main component ---

export default function AutogradeAnimation({
  status,
  onRevealReady,
}: {
  status: 'grading' | 'completed'
  onRevealReady: () => void
}) {
  const { t } = useTranslation()
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'
  const reduced = usePrefersReducedMotion()
  const { phase, progress } = useAutogradePhase(status === 'grading', reduced)
  const revealCalledRef = useRef(false)

  // How long the finished checkmark holds the screen before the test list takes over. It used to be
  // 1500 ms, which was also how long the student waited to learn their grade — the page now shows
  // the grade the moment the grader answers (audit X-007), so this hold only paces the *detail*,
  // and it can be short.
  //
  // It cannot be zero, even for reduced motion. The parent unmounts this panel the moment the hold
  // expires, and the submissions refetch that carries the results is fired without being awaited
  // (`SolutionEditor`'s awaitAutograde effect) — so expiring immediately collapses a 200px panel and
  // re-expands it a round trip later. That is a lurch, delivered specifically to the people who
  // asked for less movement. 300ms covers the refetch and still reads as instant.
  const hold = reduced ? 300 : 700

  useEffect(() => {
    if (status !== 'completed') {
      revealCalledRef.current = false
      return
    }
    const timer = setTimeout(() => {
      if (!revealCalledRef.current) {
        revealCalledRef.current = true
        onRevealReady()
      }
    }, hold)
    return () => clearTimeout(timer)
  }, [status, onRevealReady, hold])

  const statusMessages: Record<Phase, string> = {
    compile: t('submission.autogradeCompiling'),
    test: t('submission.autogradeTesting'),
    analyze: t('submission.autogradeAnalyzing'),
  }

  const isCompleted = status === 'completed'
  const displayProgress = isCompleted ? 1 : progress

  const bgColor = dark ? alpha(GREEN[500], 0.06) : alpha(GREEN[500], 0.04)
  const borderColor = dark ? alpha(GREEN[500], 0.2) : alpha(GREEN[500], 0.25)
  const progressBarColor = dark ? GREEN[400] : GREEN[500]

  return (
    <Box
      sx={{
        border: `1px solid ${borderColor}`,
        borderRadius: 2,
        bgcolor: bgColor,
        overflow: 'hidden',
        position: 'relative',
        minHeight: 200,
        ...(reduced ? {} : { animation: 'fadeInAnim 0.3s ease-out' }),
      }}
    >
      {/* Phase stepper */}
      <Box sx={{ px: 2, pt: 2 }}>
        <PhaseStepper activePhase={phase} completed={isCompleted} dark={dark} reduced={reduced} />
      </Box>

      {/* Central SVG animation. Under reduced motion the three scenes stop taking turns — swapping
          a logo for a terminal for a scatter plot every few seconds is the largest movement on the
          screen, and it is illustration, not information: the stepper and the status line below
          already say which phase is running. The logo holds the space instead, so the panel keeps
          its shape and nothing jumps when the checkmark replaces it. */}
      <Box sx={{ height: 100, px: 3, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {isCompleted ? (
          <CompletionCheckmark dark={dark} reduced={reduced} />
        ) : reduced || phase === 'compile' ? (
          <PrepareAnimation dark={dark} reduced={reduced} />
        ) : phase === 'test' ? (
          <TestAnimation dark={dark} reduced={reduced} t={t} />
        ) : (
          <AnalyzeAnimation dark={dark} reduced={reduced} />
        )}
      </Box>

      {/* Status text */}
      <Box sx={{ px: 2, pb: 1.5, textAlign: 'center' }}>
        <Typography
          variant="body2"
          sx={{
            fontFamily: 'monospace',
            fontSize: '0.8rem',
            color: dark ? GREEN[300] : GREEN[800],
            minHeight: '1.4em',
          }}
        >
          {/* Both branches keep their text inside a `SafeText` span, and that is load-bearing
              rather than tidy: this is the line whose swap crashed the route for three students
              reading the page through Chrome's translator (EZ-1884 and friends). A bare text node
              here is one React has to delete out of a surviving `<p>` the moment grading finishes,
              and under translation that node is no longer there to delete. See SafeText. */}
          {isCompleted ? <SafeText>{t('submission.autogradeDone')}</SafeText> : (
            <>
              <SafeText>{statusMessages[phase]}</SafeText>
              {/* The blinking caret is decoration; the phase label beside it is the actual signal,
                  and it keeps stepping either way — a progress indicator is the one thing that
                  should not go silent when motion is reduced. */}
              {!reduced && (
                <Box
                  component="span"
                  sx={{ animation: 'blink 1s step-end infinite', ml: 0.25 }}
                >
                  |
                </Box>
              )}
            </>
          )}
        </Typography>
      </Box>

      {/* Progress bar */}
      <Box
        sx={{
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          height: 3,
          bgcolor: alpha(GREEN[500], 0.1),
        }}
      >
        <Box
          sx={{
            height: '100%',
            bgcolor: progressBarColor,
            width: `${displayProgress * 100}%`,
            transition: isCompleted ? 'width 0.3s ease-out' : 'width 0.1s linear',
            borderRadius: '0 1px 1px 0',
          }}
        />
      </Box>

      <style>{`
        @keyframes fadeInAnim {
          from { opacity: 0; transform: translateY(8px); }
          to { opacity: 1; transform: translateY(0); }
        }
        @keyframes blink {
          50% { opacity: 0; }
        }
      `}</style>
    </Box>
  )
}
