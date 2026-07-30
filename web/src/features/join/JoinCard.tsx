import { Box, Button, Paper, Typography, keyframes } from '@mui/material'
import { ArrowForwardOutlined, CheckOutlined } from '@mui/icons-material'
import RobotFace from '../../components/RobotFace.tsx'

const rise = keyframes`
  from { opacity: 0; transform: translateY(10px) }
  to { opacity: 1; transform: none }
`

const drop = keyframes`
  from { opacity: 0; transform: translateY(-18px) }
  to { opacity: 1; transform: none }
`

/** One staggered entrance step, skipped entirely when the visitor asked for less motion */
const enter = (delay: number) => ({
  animation: `${rise} 0.45s cubic-bezier(0.2, 0.7, 0.3, 1) ${delay}ms both`,
  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
})

interface Props {
  /** Robot only, antenna dark — it powers on once the course resolves */
  loading?: boolean
  /** The course name, or the heading of a dead end */
  title?: string
  eyebrow?: string
  /** Shown instead of the join button when there's nothing to join */
  body?: string
  inviteId?: string
  inviteLabel?: string
  joinLabel?: string
  joiningLabel?: string
  joinedLabel?: string
  joining?: boolean
  joined?: boolean
  onJoin?: () => void
}

export default function JoinCard({
  loading = false,
  title,
  eyebrow,
  body,
  inviteId,
  inviteLabel,
  joinLabel,
  joiningLabel,
  joinedLabel,
  joining = false,
  joined = false,
  onJoin,
}: Props) {
  // The antenna only lights up for a course you can actually get into
  const lit = !loading && !!onJoin

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        pt: { xs: 3, sm: 7 },
        pb: 6,
      }}
    >
      <Box
        sx={{
          zIndex: 0,
          animation: `${drop} 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) both`,
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}
      >
        <RobotFace
          scale={1.3}
          lit={lit}
          celebrate={joined}
          eyes={joined ? 'happy' : 'looking'}
        />
      </Box>

      {!loading && (
        <Paper
          variant="outlined"
          sx={{
            zIndex: 1,
            mt: '-14px',
            width: '100%',
            maxWidth: 430,
            px: { xs: 3, sm: 5 },
            pt: 4,
            pb: 3.5,
            textAlign: 'center',
            borderRadius: '20px',
            // Holds on the happy robot for a beat, then lifts away as the student
            // is handed over to the course. Delay outlasts the smile + antenna flare.
            opacity: joined ? 0 : 1,
            transform: joined ? 'translateY(-10px)' : 'none',
            transition: 'opacity 0.4s ease 1.15s, transform 0.4s ease 1.15s',
          }}
        >
          {eyebrow && (
            <Typography
              variant="overline"
              sx={{ display: 'block', color: 'text.secondary', ...enter(0) }}
            >
              {eyebrow}
            </Typography>
          )}

          <Typography
            component="h1"
            sx={{
              mt: 0.5,
              fontFamily: "'Fraunces', Georgia, serif",
              fontOpticalSizing: 'auto',
              fontWeight: 600,
              fontSize: { xs: '1.75rem', sm: '2.1rem' },
              lineHeight: 1.15,
              letterSpacing: '-0.01em',
              ...enter(80),
            }}
          >
            {title}
          </Typography>

          {body && (
            <Typography sx={{ mt: 2, color: 'text.secondary', ...enter(160) }}>
              {body}
            </Typography>
          )}

          {onJoin && (
            <Button
              variant="contained"
              size="large"
              endIcon={joined ? <CheckOutlined /> : <ArrowForwardOutlined />}
              onClick={onJoin}
              disabled={joining || joined}
              sx={{ mt: 3.5, minWidth: 168, ...enter(200) }}
            >
              {joined ? joinedLabel : joining ? joiningLabel : joinLabel}
            </Button>
          )}
        </Paper>
      )}

      {!loading && inviteId && (
        <Typography
          variant="caption"
          sx={{
            mt: 2.5,
            color: 'text.secondary',
            opacity: joined ? 0 : 1,
            transition: 'opacity 0.4s ease 1.15s',
            // The entrance animation's fill mode would pin opacity at 1 and win over the
            // transition above, so it has to step aside for the exit
            ...(joined ? {} : enter(280)),
          }}
        >
          {inviteLabel}{' '}
          <Box
            component="span"
            sx={{
              fontFamily: "'JetBrains Mono', monospace",
              fontWeight: 500,
              letterSpacing: '0.18em',
              color: 'text.primary',
            }}
          >
            {inviteId}
          </Box>
        </Typography>
      )}
    </Box>
  )
}
