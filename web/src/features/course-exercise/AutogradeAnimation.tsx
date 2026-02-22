import { useEffect, useRef, useState, useCallback } from 'react'
import { Box, Typography } from '@mui/material'
import { useTheme } from '@mui/material/styles'
import { green } from '@mui/material/colors'
import { useTranslation } from 'react-i18next'

type Phase = 'compile' | 'test' | 'analyze'

const PHASES: Phase[] = ['compile', 'test', 'analyze']
const PHASE_DURATIONS: Record<Phase, number> = { compile: 3000, test: 4000, analyze: 3000 }
const CYCLE_DURATION = 10000

function useAutogradePhase(active: boolean) {
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
  }, [active])

  return { phase, progress }
}

// --- SVG sub-animations ---

function PrepareAnimation({ dark }: { dark: boolean }) {
  const fill = dark ? green[300] : green[600]

  // Exact Lahendus logo shapes from logo.svg (24x24 viewBox),
  // scaled 3.2x and centered in the 200x100 animation viewBox.
  // s=3.2, ox=62, oy=12
  return (
    <svg viewBox="0 0 200 100" width="100%" height="100%" key="compile">
      {/* Bottom-left block */}
      <rect
        x="62" y="56.5" width="32.3" height="32.3"
        fill={fill} opacity="0"
        style={{ animation: 'logoDrop 0.45s cubic-bezier(0.34,1.56,0.64,1) 0s forwards' }}
      />

      {/* Bottom-right block */}
      <rect
        x="106.5" y="56.5" width="32" height="32.3"
        fill={fill} opacity="0"
        style={{ animation: 'logoDrop 0.45s cubic-bezier(0.34,1.56,0.64,1) 0.25s forwards' }}
      />

      {/* Top block (pentagon — page body with fold cutout) */}
      <polygon
        points="84.4,12 84.4,44.3 116.4,44.3 116.4,34.4 88.9,12"
        fill={fill} opacity="0"
        style={{ animation: 'logoDrop 0.45s cubic-bezier(0.34,1.56,0.64,1) 0.5s forwards' }}
      />

      {/* Fold triangle (drops in separately) */}
      <polygon
        points="97.2,12 116.4,27.4 116.4,12"
        fill={fill} opacity="0"
        style={{ animation: 'logoDrop 0.45s cubic-bezier(0.34,1.56,0.64,1) 0.75s forwards' }}
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

function TestAnimation({ dark, t }: { dark: boolean; t: (k: string, opts?: Record<string, unknown>) => string }) {
  const bgColor = dark ? '#1a1a2e' : '#1b2631'
  const textColor = dark ? green[300] : green[400]
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
          opacity="0"
        >
          {line.text}
          <animate attributeName="opacity" from="0" to="1" dur="0.3s" begin={line.delay} fill="freeze" />
        </text>
      ))}
    </svg>
  )
}

function AnalyzeAnimation({ dark }: { dark: boolean }) {
  const classA = dark ? green[300] : green[600]
  const classB = dark ? green[200] : green[400]
  const lineColor = dark ? green[100] : green[800]

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
          opacity="0"
          style={{ animation: `dotPop 0.3s ease-out ${i * 0.05}s forwards` }}
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
          strokeDashoffset: boundaryLen,
          animation: `boundaryDraw 1.5s ease-out 1.1s forwards`,
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

function CompletionCheckmark({ dark }: { dark: boolean }) {
  const strokeColor = dark ? green[300] : green[600]

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
        strokeDashoffset="201"
        style={{ animation: 'circDraw 0.8s ease-out forwards' }}
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
        strokeDashoffset="60"
        style={{ animation: 'checkDraw 0.5s ease-out 0.4s forwards' }}
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
}: {
  activePhase: Phase
  completed: boolean
  dark: boolean
}) {
  const { t } = useTranslation()
  const labels: Record<Phase, string> = {
    compile: t('submission.autogradePhaseCompile'),
    test: t('submission.autogradePhaseTest'),
    analyze: t('submission.autogradePhaseAnalyze'),
  }
  const activeIdx = completed ? PHASES.length : PHASES.indexOf(activePhase)

  const filledColor = dark ? green[400] : green[600]
  const activeColor = dark ? green[300] : green[500]
  const inactiveColor = dark ? '#555' : '#ccc'
  const textActive = dark ? green[200] : green[800]
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
                  ...(isActive && {
                    boxShadow: `0 0 0 4px ${dark ? 'rgba(76,175,80,0.25)' : 'rgba(76,175,80,0.2)'}`,
                    animation: 'pulseRing 1.5s ease-in-out infinite',
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
          0%, 100% { box-shadow: 0 0 0 4px ${dark ? 'rgba(76,175,80,0.25)' : 'rgba(76,175,80,0.2)'}; }
          50% { box-shadow: 0 0 0 8px ${dark ? 'rgba(76,175,80,0.08)' : 'rgba(76,175,80,0.08)'}; }
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
  const { phase, progress } = useAutogradePhase(status === 'grading')
  const revealCalledRef = useRef(false)

  // Completion timeout: after 1.5s in 'completed', fire onRevealReady
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
    }, 1500)
    return () => clearTimeout(timer)
  }, [status, onRevealReady])

  const statusMessages: Record<Phase, string> = {
    compile: t('submission.autogradeCompiling'),
    test: t('submission.autogradeTesting'),
    analyze: t('submission.autogradeAnalyzing'),
  }

  const isCompleted = status === 'completed'
  const displayProgress = isCompleted ? 1 : progress

  const bgColor = dark ? 'rgba(76,175,80,0.06)' : 'rgba(76,175,80,0.04)'
  const borderColor = dark ? 'rgba(76,175,80,0.2)' : 'rgba(76,175,80,0.25)'
  const progressBarColor = dark ? green[400] : green[500]

  return (
    <Box
      sx={{
        border: `1px solid ${borderColor}`,
        borderRadius: 2,
        bgcolor: bgColor,
        overflow: 'hidden',
        position: 'relative',
        minHeight: 200,
        animation: 'fadeInAnim 0.3s ease-out',
      }}
    >
      {/* Phase stepper */}
      <Box sx={{ px: 2, pt: 2 }}>
        <PhaseStepper activePhase={phase} completed={isCompleted} dark={dark} />
      </Box>

      {/* Central SVG animation */}
      <Box sx={{ height: 100, px: 3, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {isCompleted ? (
          <CompletionCheckmark dark={dark} />
        ) : phase === 'compile' ? (
          <PrepareAnimation dark={dark} />
        ) : phase === 'test' ? (
          <TestAnimation dark={dark} t={t} />
        ) : (
          <AnalyzeAnimation dark={dark} />
        )}
      </Box>

      {/* Status text */}
      <Box sx={{ px: 2, pb: 1.5, textAlign: 'center' }}>
        <Typography
          variant="body2"
          sx={{
            fontFamily: 'monospace',
            fontSize: '0.8rem',
            color: dark ? green[300] : green[800],
            minHeight: '1.4em',
          }}
        >
          {isCompleted ? t('submission.autogradeDone') : (
            <>
              {statusMessages[phase]}
              <Box
                component="span"
                sx={{ animation: 'blink 1s step-end infinite', ml: 0.25 }}
              >
                |
              </Box>
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
          bgcolor: dark ? 'rgba(76,175,80,0.1)' : 'rgba(76,175,80,0.1)',
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
