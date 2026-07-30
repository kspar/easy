import { Box, keyframes } from '@mui/material'
import { alpha, lighten, type Theme } from '@mui/material/styles'

const looking = keyframes`
  0%, 83.34%, 100% { transform: translate(0px, 0px) }
  16.67% { transform: translate(-2px, -1px) }
  33.34% { transform: translate(3px, 0px) }
  50% { transform: translate(-1px, 2px) }
  66.67% { transform: translate(2px, -1px) }
`

const bloom = keyframes`
  from { opacity: 0; transform: translate(-50%, -50%) scale(0.4) }
  to { opacity: 1; transform: translate(-50%, -50%) scale(1) }
`

const breathe = keyframes`
  0%, 100% { opacity: 0.6 }
  50% { opacity: 1 }
`

/** One-shot surge when the handshake completes, settling a little brighter than it started */
const flare = keyframes`
  0% { opacity: 1; transform: translate(-50%, -50%) scale(1) }
  40% { opacity: 1; transform: translate(-50%, -50%) scale(1.5) }
  100% { opacity: 1; transform: translate(-50%, -50%) scale(1.18) }
`

const drawArc = keyframes`
  from { stroke-dashoffset: 34 }
  to { stroke-dashoffset: 0 }
`

const SHELL = '#a8a8a8'

/** The lit green needs to be brighter on a dark background to still read as a light source */
const litColor = (t: Theme) =>
  t.palette.mode === 'dark' ? t.palette.primary.light : t.palette.primary.main

interface Props {
  /** Multiplies every dimension; 1 is the original 60x50 head */
  scale?: number
  /** Antenna ball on, with an ambient glow behind it */
  lit?: boolean
  /** Surges the lit antenna once — pair with `eyes="happy"` for a success beat */
  celebrate?: boolean
  /** The original easter egg: the ball lights up while hovered */
  hoverLight?: boolean
  /** 'happy' closes the eyes into a smile — the payoff when something went right */
  eyes?: 'looking' | 'happy'
}

export default function RobotFace({
  scale = 1,
  lit = false,
  celebrate = false,
  hoverLight = false,
  eyes = 'looking',
}: Props) {
  const s = (n: number) => n * scale

  return (
    <Box
      aria-hidden
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}
    >
      {/* Antenna ball, with the glow it throws when lit */}
      <Box
        sx={{
          position: 'relative',
          display: 'flex',
          transform: `translateY(${s(3)}px)`,
        }}
      >
        {lit && (
          <Box
            aria-hidden
            sx={{
              position: 'absolute',
              top: '50%',
              left: '50%',
              width: s(74),
              height: s(74),
              borderRadius: '50%',
              pointerEvents: 'none',
              background: (t) =>
                `radial-gradient(circle, ${alpha(litColor(t), 0.5)} 0%, ${alpha(litColor(t), 0.12)} 38%, transparent 58%)`,
              animation: celebrate
                ? `${flare} 0.7s cubic-bezier(0.25, 0.9, 0.3, 1) both`
                : `${bloom} 0.6s ease-out both, ${breathe} 4s ease-in-out 0.6s infinite`,
              '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
            }}
          />
        )}
        <Box
          sx={{
            width: s(16),
            height: s(16),
            borderRadius: '50%',
            backgroundColor: (t) =>
              celebrate ? lighten(litColor(t), 0.25) : lit ? litColor(t) : SHELL,
            boxShadow: (t) =>
              lit
                ? `0 0 ${s(celebrate ? 12 : 7)}px ${s(celebrate ? 4 : 2)}px ${alpha(litColor(t), celebrate ? 0.7 : 0.55)}`
                : 'none',
            transition: 'background-color 0.5s ease, box-shadow 0.5s ease',
            ...(hoverLight && {
              '&:hover': {
                backgroundColor: '#fbff00',
                boxShadow: '0 0 10px 10px rgba(255, 255, 190, 0.8)',
              },
            }),
          }}
        />
      </Box>

      {/* Antenna */}
      <Box sx={{ width: s(8), height: s(10), backgroundColor: SHELL, flexShrink: 0 }} />

      {/* Head */}
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          width: s(60),
          height: s(50),
          backgroundColor: SHELL,
          borderRadius: '20%',
          px: `${s(8)}px`,
        }}
      >
        <Eye size={s(14)} mode={eyes} delay={0} />
        <Eye size={s(14)} mode={eyes} delay={120} />
      </Box>
    </Box>
  )
}

function Eye({
  size,
  mode,
  delay,
}: {
  size: number
  mode: 'looking' | 'happy'
  delay: number
}) {
  if (mode === 'happy') {
    return (
      <Box
        component="svg"
        viewBox="0 0 24 16"
        aria-hidden
        sx={{ width: size, height: size * 0.75, overflow: 'visible' }}
      >
        <Box
          component="path"
          d="M2 13C6 3.5 18 3.5 22 13"
          fill="none"
          stroke="white"
          strokeWidth={4}
          strokeLinecap="round"
          sx={{
            strokeDasharray: 34,
            animation: `${drawArc} 0.4s cubic-bezier(0.65, 0, 0.35, 1) ${delay}ms both`,
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none',
              strokeDashoffset: 0,
            },
          }}
        />
      </Box>
    )
  }

  return (
    <Box
      sx={{
        width: size,
        height: size,
        borderRadius: '50%',
        backgroundColor: 'white',
        animation: `${looking} 5s 1s infinite`,
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      }}
    />
  )
}
