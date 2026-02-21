import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Box, Typography, Button, Container, CircularProgress } from '@mui/material'
import { keyframes } from '@emotion/react'
import {
  AutoFixHighOutlined,
  TableChartOutlined,
  LibraryBooksOutlined,
  SyncOutlined,
  ScheduleOutlined,
  RateReviewOutlined,
  CodeOutlined,
  BoltOutlined,
  TrendingUpOutlined,
  DevicesOutlined,
  GitHub,
  ArrowForwardOutlined,
} from '@mui/icons-material'
import { useAuth } from '../../auth/AuthContext.tsx'
import { useStatistics } from '../../api/statistics.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import logoSvg from '../../assets/logo.svg'
import harnoLogo from '../../assets/sponsors/harno.svg'
import mkmLogo from '../../assets/sponsors/mkm.png'
import itaLogo from '../../assets/sponsors/ita.png'
import config from '../../config.ts'

// ─── Design Tokens ──────────────────────────────────────────────────────────────

const DARK = '#060b06'
const GREEN = '#16a34a'
const GREEN_BRIGHT = '#4ade80'

const F = {
  display: "'Fraunces', Georgia, serif",
  body: "'Outfit', sans-serif",
  mono: "'JetBrains Mono', monospace",
  logo: "'Sniglet', cursive",
}

// ─── Keyframes ──────────────────────────────────────────────────────────────────

const fadeUp = keyframes`
  from { opacity: 0; transform: translateY(28px); }
  to { opacity: 1; transform: translateY(0); }
`

const fadeIn = keyframes`
  from { opacity: 0; }
  to { opacity: 1; }
`

const pulse = keyframes`
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
`

const blink = keyframes`
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
`

// ─── Helpers ────────────────────────────────────────────────────────────────────

function Reveal({ children, delay = 0 }: { children: ReactNode; delay?: number }) {
  const ref = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { threshold: 0.1 },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  return (
    <Box
      ref={ref}
      sx={{
        opacity: visible ? 1 : 0,
        transform: visible ? 'none' : 'translateY(28px)',
        transition: `opacity 0.8s cubic-bezier(0.16, 1, 0.3, 1) ${delay}s, transform 0.8s cubic-bezier(0.16, 1, 0.3, 1) ${delay}s`,
      }}
    >
      {children}
    </Box>
  )
}

function formatNumber(n: number): string {
  if (n < 1000) return String(n)
  const s = String(n)
  let result = ''
  for (let i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 === 0) result += '\u2009'
    result += s[i]
  }
  return result
}

// ─── Terminal Animation ─────────────────────────────────────────────────────────

interface TerminalLine {
  text: string
  delay: number
  color: string
  bold?: boolean
}

const TERMINAL_LINES: TerminalLine[] = [
  { text: '$ submit solution.py', delay: 0.8, color: '#e0e0e0' },
  { text: '  Running tests...', delay: 1.4, color: '#6b8a6b' },
  { text: '', delay: 0, color: '' },
  { text: '  \u2713 test_basic_input          passed', delay: 2.0, color: GREEN_BRIGHT },
  { text: '  \u2713 test_edge_cases           passed', delay: 2.4, color: GREEN_BRIGHT },
  { text: '  \u2713 test_large_input          passed', delay: 2.8, color: GREEN_BRIGHT },
  { text: '', delay: 0, color: '' },
  { text: '  All tests passed!  Grade: 100/100', delay: 3.4, color: '#fff', bold: true },
]

function TerminalWindow() {
  return (
    <Box
      sx={{
        bgcolor: '#0a0f0a',
        borderRadius: '12px',
        border: '1px solid rgba(74, 222, 128, 0.12)',
        overflow: 'hidden',
        boxShadow: '0 0 80px rgba(74, 222, 128, 0.06), 0 24px 48px rgba(0, 0, 0, 0.5)',
        maxWidth: 520,
        width: '100%',
      }}
    >
      {/* Title bar */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.75,
          px: 2,
          py: 1.25,
          borderBottom: '1px solid rgba(255,255,255,0.06)',
        }}
      >
        <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#ff5f57' }} />
        <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#febc2e' }} />
        <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#28c840' }} />
        <Typography
          sx={{
            fontFamily: F.mono,
            fontSize: '0.7rem',
            color: 'rgba(255,255,255,0.3)',
            ml: 1.5,
            letterSpacing: '0.03em',
          }}
        >
          lahendus &mdash; auto-grader
        </Typography>
      </Box>

      {/* Terminal body */}
      <Box sx={{ p: 2.5, minHeight: 220 }}>
        {TERMINAL_LINES.map((line, i) =>
          line.text === '' ? (
            <Box key={i} sx={{ height: 12 }} />
          ) : (
            <Typography
              key={i}
              sx={{
                fontFamily: F.mono,
                fontSize: { xs: '0.75rem', sm: '0.85rem' },
                color: line.color,
                fontWeight: line.bold ? 600 : 400,
                lineHeight: 2,
                whiteSpace: 'pre',
                opacity: 0,
                animation: `${fadeIn} 0.4s ease forwards`,
                animationDelay: `${line.delay}s`,
              }}
            >
              {line.text}
              {i === 0 && (
                <Box
                  component="span"
                  sx={{
                    display: 'inline-block',
                    width: 8,
                    height: '1.1em',
                    bgcolor: GREEN_BRIGHT,
                    ml: 0.5,
                    verticalAlign: 'text-bottom',
                    opacity: 0,
                    animation: `${fadeIn} 0.01s ease forwards, ${blink} 1s step-end infinite 0.8s`,
                    animationDelay: '0.8s',
                  }}
                />
              )}
            </Typography>
          ),
        )}
      </Box>
    </Box>
  )
}

// ─── Feature Card ───────────────────────────────────────────────────────────────

function FeatureCard({
  icon,
  title,
  description,
  dark,
}: {
  icon: ReactNode
  title: string
  description: string
  dark?: boolean
}) {
  return (
    <Box
      sx={{
        p: 3.5,
        borderRadius: '14px',
        border: dark ? '1px solid rgba(74, 222, 128, 0.1)' : '1px solid #e8e8e8',
        bgcolor: dark ? 'rgba(74, 222, 128, 0.04)' : '#fff',
        transition: 'transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease',
        height: '100%',
        '&:hover': {
          transform: 'translateY(-2px)',
          boxShadow: dark
            ? '0 8px 32px rgba(74, 222, 128, 0.08)'
            : '0 8px 32px rgba(0, 0, 0, 0.06)',
          borderColor: dark ? 'rgba(74, 222, 128, 0.2)' : '#d0d0d0',
        },
      }}
    >
      <Box
        sx={{
          width: 44,
          height: 44,
          borderRadius: '10px',
          bgcolor: dark ? 'rgba(74, 222, 128, 0.1)' : '#dcfce7',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          mb: 2,
          color: dark ? GREEN_BRIGHT : GREEN,
        }}
      >
        {icon}
      </Box>
      <Typography
        sx={{
          fontFamily: F.body,
          fontWeight: 600,
          fontSize: '1.05rem',
          mb: 0.75,
          color: dark ? '#e0e0e0' : '#1a1a1a',
        }}
      >
        {title}
      </Typography>
      <Typography
        sx={{
          fontFamily: F.body,
          fontSize: '0.9rem',
          lineHeight: 1.6,
          color: dark ? '#8a9e8a' : '#666',
        }}
      >
        {description}
      </Typography>
    </Box>
  )
}

// ─── Stats Strip ────────────────────────────────────────────────────────────────

function StatItem({
  value,
  label,
  isLoading,
  live,
}: {
  value: number
  label: string
  isLoading: boolean
  live?: boolean
}) {
  return (
    <Box sx={{ textAlign: 'center' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 1 }}>
        {live && (
          <Box
            sx={{
              width: 6,
              height: 6,
              borderRadius: '50%',
              bgcolor: GREEN_BRIGHT,
              animation: `${pulse} 2s ease-in-out infinite`,
            }}
          />
        )}
        <Typography
          sx={{
            fontFamily: F.display,
            fontSize: { xs: '1.8rem', md: '2.2rem' },
            fontWeight: 400,
            color: '#e0e0e0',
            fontVariationSettings: "'opsz' 48",
            minWidth: 80,
          }}
        >
          {isLoading ? <CircularProgress size={24} sx={{ color: '#4a6a4a' }} /> : formatNumber(value)}
        </Typography>
      </Box>
      <Typography
        sx={{
          fontFamily: F.body,
          fontSize: '0.8rem',
          color: '#6b8a6b',
          letterSpacing: '0.03em',
        }}
      >
        {label}
      </Typography>
    </Box>
  )
}

function StatsStrip() {
  const stats = useStatistics()

  return (
    <Box
      sx={{
        py: 4,
        bgcolor: '#0a140a',
        borderTop: '1px solid rgba(74, 222, 128, 0.06)',
        borderBottom: '1px solid rgba(74, 222, 128, 0.06)',
      }}
    >
      <Container maxWidth="lg">
        <Box
          sx={{
            display: 'flex',
            flexDirection: { xs: 'column', sm: 'row' },
            justifyContent: 'center',
            alignItems: 'center',
            gap: { xs: 3, sm: 6, md: 10 },
          }}
        >
          <StatItem
            value={stats.totalSubmissions}
            label="Submissions graded"
            isLoading={stats.isLoading}
          />
          <Box
            sx={{
              width: 1,
              height: 32,
              bgcolor: 'rgba(74, 222, 128, 0.1)',
              display: { xs: 'none', sm: 'block' },
            }}
          />
          <StatItem
            value={stats.totalUsers}
            label="User accounts"
            isLoading={stats.isLoading}
          />
          <Box
            sx={{
              width: 1,
              height: 32,
              bgcolor: 'rgba(74, 222, 128, 0.1)',
              display: { xs: 'none', sm: 'block' },
            }}
          />
          <StatItem
            value={stats.inAutoAssessing}
            label="Grading right now"
            isLoading={stats.isLoading}
            live
          />
        </Box>
      </Container>
    </Box>
  )
}

// ─── Teacher Features ───────────────────────────────────────────────────────────

const TEACHER_FEATURES = [
  {
    icon: <AutoFixHighOutlined />,
    title: 'Automatic grading',
    description:
      'Solutions are tested against your test cases and graded instantly. Students get immediate feedback without any manual work.',
  },
  {
    icon: <TableChartOutlined />,
    title: 'Grade table',
    description:
      "See every student's progress at a glance in a spreadsheet-style view. Sort, filter by group, and export to CSV.",
  },
  {
    icon: <LibraryBooksOutlined />,
    title: 'Exercise library',
    description:
      'Build exercises once, reuse across courses. Organize in directories and share with colleagues.',
  },
  {
    icon: <SyncOutlined />,
    title: 'Moodle integration',
    description:
      'Link your course to Moodle and student lists sync automatically. Grades push back to Moodle too.',
  },
  {
    icon: <ScheduleOutlined />,
    title: 'Flexible deadlines',
    description:
      'Set soft and hard deadlines with per-student and per-group exceptions. Control exercise visibility with scheduling.',
  },
  {
    icon: <RateReviewOutlined />,
    title: 'Feedback snippets',
    description:
      'Save common feedback as reusable snippets. Insert with one click while grading to speed up reviews.',
  },
]

// ─── Student Features ───────────────────────────────────────────────────────────

const STUDENT_FEATURES = [
  {
    icon: <CodeOutlined />,
    title: 'In-browser code editor',
    description:
      'Write code directly in the browser with syntax highlighting. No setup needed — just open and code.',
  },
  {
    icon: <BoltOutlined />,
    title: 'Instant automated feedback',
    description:
      'See which tests pass and which fail immediately after submitting. Fix and resubmit as many times as needed.',
  },
  {
    icon: <TrendingUpOutlined />,
    title: 'Progress tracking',
    description:
      'Visual indicators show your completion status across all exercises. Always know where you stand.',
  },
  {
    icon: <DevicesOutlined />,
    title: 'Submit from anywhere',
    description:
      'Use the web editor, upload files, submit from Thonny IDE, or use the command-line tool.',
  },
]

// ─── Integration Cards ──────────────────────────────────────────────────────────

const INTEGRATIONS = [
  { name: 'Moodle', desc: 'Sync students & grades' },
  { name: 'Thonny', desc: 'Submit from IDE' },
  { name: 'CLI', desc: 'Command-line tool' },
  { name: 'Python SDK', desc: 'Programmatic access' },
]

// ─── Main Component ─────────────────────────────────────────────────────────────

export default function LandingPage() {
  usePageTitle('Lahendus \u2014 Automated Programming Assessment')

  const navigate = useNavigate()
  const { authenticated, login } = useAuth()
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const handler = () => setScrolled(window.scrollY > 40)
    window.addEventListener('scroll', handler, { passive: true })
    return () => window.removeEventListener('scroll', handler)
  }, [])

  const handleCta = () => {
    if (authenticated) {
      navigate('/courses')
    } else {
      login()
    }
  }

  const scrollTo = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
  }

  return (
    <Box sx={{ fontFamily: F.body, overflowX: 'hidden' }}>
      {/* ─── Navbar ──────────────────────────────────────────────────────── */}
      <Box
        sx={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          zIndex: 1000,
          transition: 'all 0.35s ease',
          bgcolor: scrolled ? 'rgba(6, 11, 6, 0.92)' : 'transparent',
          backdropFilter: scrolled ? 'blur(16px)' : 'none',
          borderBottom: scrolled
            ? '1px solid rgba(74, 222, 128, 0.08)'
            : '1px solid transparent',
        }}
      >
        <Container maxWidth="lg">
          <Box sx={{ display: 'flex', alignItems: 'center', height: 64 }}>
            {/* Logo */}
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Box
                component="img"
                src={logoSvg}
                alt=""
                sx={{
                  width: 26,
                  height: 26,
                  filter:
                    'invert(70%) sepia(30%) saturate(500%) hue-rotate(84deg) brightness(95%)',
                }}
              />
              <Typography
                sx={{
                  fontFamily: F.logo,
                  fontSize: '1.25rem',
                  color: GREEN_BRIGHT,
                  letterSpacing: '0.01em',
                }}
              >
                LAHENDUS
              </Typography>
            </Box>

            <Box sx={{ flexGrow: 1 }} />

            {/* Nav links */}
            <Box
              sx={{
                display: { xs: 'none', md: 'flex' },
                alignItems: 'center',
                gap: 3,
                mr: 3,
              }}
            >
              {[
                { label: 'Features', target: 'teachers' },
                { label: 'Open source', target: 'open-source' },
              ].map((link) => (
                <Box
                  key={link.target}
                  component="a"
                  href={`#${link.target}`}
                  onClick={(e: React.MouseEvent) => {
                    e.preventDefault()
                    scrollTo(link.target)
                  }}
                  sx={{
                    fontFamily: F.body,
                    fontSize: '0.88rem',
                    color: 'rgba(255,255,255,0.6)',
                    textDecoration: 'none',
                    cursor: 'pointer',
                    transition: 'color 0.2s',
                    '&:hover': { color: '#fff' },
                  }}
                >
                  {link.label}
                </Box>
              ))}
            </Box>

            {/* CTA */}
            <Button
              variant="outlined"
              size="small"
              onClick={handleCta}
              sx={{
                fontFamily: F.body,
                fontWeight: 500,
                color: GREEN_BRIGHT,
                borderColor: 'rgba(74, 222, 128, 0.3)',
                borderRadius: '8px',
                textTransform: 'none',
                px: 2.5,
                '&:hover': {
                  borderColor: GREEN_BRIGHT,
                  bgcolor: 'rgba(74, 222, 128, 0.08)',
                },
              }}
            >
              {authenticated ? 'Open Lahendus' : 'Log in'}
            </Button>
          </Box>
        </Container>
      </Box>

      {/* ─── Hero ────────────────────────────────────────────────────────── */}
      <Box
        sx={{
          bgcolor: DARK,
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          position: 'relative',
          overflow: 'hidden',
          backgroundImage: `
            radial-gradient(ellipse 70% 50% at 50% -5%, rgba(56, 125, 20, 0.12), transparent 70%),
            repeating-linear-gradient(0deg, transparent, transparent 64px, rgba(74, 222, 128, 0.025) 64px, rgba(74, 222, 128, 0.025) 65px),
            repeating-linear-gradient(90deg, transparent, transparent 64px, rgba(74, 222, 128, 0.025) 64px, rgba(74, 222, 128, 0.025) 65px)
          `,
        }}
      >
        <Container maxWidth="lg" sx={{ py: { xs: 14, md: 8 } }}>
          <Box
            sx={{
              display: 'flex',
              flexDirection: { xs: 'column', md: 'row' },
              alignItems: { xs: 'flex-start', md: 'center' },
              gap: { xs: 6, md: 8 },
            }}
          >
            {/* Left: Text */}
            <Box sx={{ flex: 1, maxWidth: { md: 560 } }}>
              <Typography
                sx={{
                  fontFamily: F.display,
                  fontWeight: 500,
                  fontSize: { xs: '2.4rem', sm: '3rem', md: '3.5rem' },
                  lineHeight: 1.15,
                  color: '#f0f4f0',
                  letterSpacing: '-0.02em',
                  mb: 2.5,
                  animation: `${fadeUp} 0.9s cubic-bezier(0.16, 1, 0.3, 1) forwards`,
                  fontVariationSettings: "'opsz' 72",
                }}
              >
                Grading code is a{' '}
                <Box
                  component="span"
                  sx={{
                    color: GREEN_BRIGHT,
                    fontStyle: 'italic',
                  }}
                >
                  solved
                </Box>{' '}
                problem.
              </Typography>

              <Typography
                sx={{
                  fontFamily: F.body,
                  fontSize: { xs: '1.05rem', md: '1.15rem' },
                  lineHeight: 1.65,
                  color: '#8a9e8a',
                  mb: 4,
                  maxWidth: 480,
                  opacity: 0,
                  animation: `${fadeUp} 0.9s cubic-bezier(0.16, 1, 0.3, 1) 0.15s forwards`,
                }}
              >
                Lahendus automatically tests and grades programming solutions, so teachers can
                focus on teaching and students get instant feedback.
              </Typography>

              <Box
                sx={{
                  display: 'flex',
                  gap: 2,
                  flexWrap: 'wrap',
                  opacity: 0,
                  animation: `${fadeUp} 0.9s cubic-bezier(0.16, 1, 0.3, 1) 0.3s forwards`,
                }}
              >
                <Button
                  variant="contained"
                  size="large"
                  onClick={handleCta}
                  endIcon={<ArrowForwardOutlined />}
                  sx={{
                    fontFamily: F.body,
                    fontWeight: 600,
                    fontSize: '0.95rem',
                    bgcolor: GREEN,
                    color: '#fff',
                    textTransform: 'none',
                    borderRadius: '10px',
                    px: 3.5,
                    py: 1.5,
                    boxShadow: '0 0 24px rgba(56, 125, 20, 0.3)',
                    '&:hover': { bgcolor: '#2d6a11' },
                  }}
                >
                  {authenticated ? 'Go to courses' : 'Get started'}
                </Button>
                <Button
                  variant="outlined"
                  size="large"
                  onClick={() => scrollTo('teachers')}
                  sx={{
                    fontFamily: F.body,
                    fontWeight: 500,
                    fontSize: '0.95rem',
                    color: '#c0cec0',
                    borderColor: 'rgba(255,255,255,0.15)',
                    textTransform: 'none',
                    borderRadius: '10px',
                    px: 3.5,
                    py: 1.5,
                    '&:hover': {
                      borderColor: 'rgba(255,255,255,0.3)',
                      bgcolor: 'rgba(255,255,255,0.04)',
                    },
                  }}
                >
                  See features
                </Button>
              </Box>
            </Box>

            {/* Right: Terminal */}
            <Box
              sx={{
                flex: 1,
                display: 'flex',
                justifyContent: { xs: 'flex-start', md: 'flex-end' },
                width: '100%',
                opacity: 0,
                animation: `${fadeUp} 1s cubic-bezier(0.16, 1, 0.3, 1) 0.5s forwards`,
              }}
            >
              <TerminalWindow />
            </Box>
          </Box>
        </Container>
      </Box>

      {/* ─── Stats Strip ─────────────────────────────────────────────────── */}
      <StatsStrip />

      {/* ─── Teacher Features ────────────────────────────────────────────── */}
      <Box
        id="teachers"
        sx={{
          py: { xs: 10, md: 14 },
          bgcolor: '#fff',
        }}
      >
        <Container maxWidth="lg">
          <Reveal>
            <Box sx={{ mb: 8, maxWidth: 600 }}>
              <Typography
                sx={{
                  fontFamily: F.body,
                  fontWeight: 600,
                  fontSize: '0.75rem',
                  letterSpacing: '0.1em',
                  textTransform: 'uppercase',
                  color: GREEN,
                  mb: 1.5,
                }}
              >
                For teachers
              </Typography>
              <Typography
                sx={{
                  fontFamily: F.display,
                  fontWeight: 400,
                  fontSize: { xs: '1.8rem', md: '2.4rem' },
                  lineHeight: 1.2,
                  color: '#1a1a1a',
                  mb: 2,
                  fontVariationSettings: "'opsz' 48",
                }}
              >
                Everything you need to run a programming course
              </Typography>
              <Typography
                sx={{
                  fontFamily: F.body,
                  fontSize: '1.05rem',
                  lineHeight: 1.6,
                  color: '#666',
                }}
              >
                From automatic assessment to grade management, Lahendus handles the tedious parts
                so you can focus on curriculum and mentoring.
              </Typography>
            </Box>
          </Reveal>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, 1fr)',
                md: 'repeat(3, 1fr)',
              },
              gap: 3,
            }}
          >
            {TEACHER_FEATURES.map((feature, i) => (
              <Reveal key={feature.title} delay={0.06 * i}>
                <FeatureCard {...feature} />
              </Reveal>
            ))}
          </Box>
        </Container>
      </Box>

      {/* ─── Student Features ────────────────────────────────────────────── */}
      <Box
        id="students"
        sx={{
          py: { xs: 10, md: 14 },
          bgcolor: '#f8f9f7',
        }}
      >
        <Container maxWidth="lg">
          <Reveal>
            <Box sx={{ mb: 8, maxWidth: 600 }}>
              <Typography
                sx={{
                  fontFamily: F.body,
                  fontWeight: 600,
                  fontSize: '0.75rem',
                  letterSpacing: '0.1em',
                  textTransform: 'uppercase',
                  color: GREEN,
                  mb: 1.5,
                }}
              >
                For students
              </Typography>
              <Typography
                sx={{
                  fontFamily: F.display,
                  fontWeight: 400,
                  fontSize: { xs: '1.8rem', md: '2.4rem' },
                  lineHeight: 1.2,
                  color: '#1a1a1a',
                  mb: 2,
                  fontVariationSettings: "'opsz' 48",
                }}
              >
                Submit, test, improve &mdash; without waiting
              </Typography>
            </Box>
          </Reveal>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, 1fr)',
              },
              gap: 3,
              maxWidth: 820,
            }}
          >
            {STUDENT_FEATURES.map((feature, i) => (
              <Reveal key={feature.title} delay={0.06 * i}>
                <FeatureCard {...feature} />
              </Reveal>
            ))}
          </Box>
        </Container>
      </Box>

      {/* ─── Open Source & Integrations ───────────────────────────────────── */}
      <Box
        id="open-source"
        sx={{
          py: { xs: 10, md: 14 },
          bgcolor: DARK,
          backgroundImage:
            'radial-gradient(ellipse 60% 40% at 50% 100%, rgba(56, 125, 20, 0.08), transparent)',
        }}
      >
        <Container maxWidth="lg">
          <Reveal>
            <Box
              sx={{
                display: 'flex',
                flexDirection: { xs: 'column', md: 'row' },
                alignItems: { xs: 'flex-start', md: 'center' },
                gap: { xs: 5, md: 10 },
              }}
            >
              <Box sx={{ flex: 1 }}>
                <Typography
                  sx={{
                    fontFamily: F.display,
                    fontWeight: 400,
                    fontSize: { xs: '1.8rem', md: '2.4rem' },
                    lineHeight: 1.2,
                    color: '#f0f4f0',
                    mb: 2,
                    fontVariationSettings: "'opsz' 48",
                  }}
                >
                  Open source, built at the University of Tartu
                </Typography>
                <Typography
                  sx={{
                    fontFamily: F.body,
                    fontSize: '1.05rem',
                    lineHeight: 1.6,
                    color: '#8a9e8a',
                    mb: 4,
                    maxWidth: 500,
                  }}
                >
                  Lahendus is developed by the Institute of Computer Science and is built on an
                  open-source application called easy. Contributions and feedback are welcome.
                </Typography>
                <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                  <Button
                    variant="outlined"
                    href={config.repoUrl}
                    target="_blank"
                    rel="noopener"
                    startIcon={<GitHub />}
                    sx={{
                      fontFamily: F.body,
                      color: '#e0e0e0',
                      borderColor: 'rgba(255,255,255,0.15)',
                      textTransform: 'none',
                      borderRadius: '10px',
                      px: 3,
                      '&:hover': {
                        borderColor: 'rgba(255,255,255,0.3)',
                        bgcolor: 'rgba(255,255,255,0.04)',
                      },
                    }}
                  >
                    GitHub
                  </Button>
                  <Button
                    variant="outlined"
                    href={config.discordInviteUrl}
                    target="_blank"
                    rel="noopener"
                    sx={{
                      fontFamily: F.body,
                      color: '#e0e0e0',
                      borderColor: 'rgba(255,255,255,0.15)',
                      textTransform: 'none',
                      borderRadius: '10px',
                      px: 3,
                      '&:hover': {
                        borderColor: 'rgba(255,255,255,0.3)',
                        bgcolor: 'rgba(255,255,255,0.04)',
                      },
                    }}
                  >
                    Discord
                  </Button>
                </Box>
              </Box>

              {/* Integration cards */}
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(2, 1fr)',
                  gap: 2,
                  flex: '0 0 auto',
                  width: { xs: '100%', md: 'auto' },
                }}
              >
                {INTEGRATIONS.map((item) => (
                  <Box
                    key={item.name}
                    sx={{
                      p: 2.5,
                      borderRadius: '12px',
                      border: '1px solid rgba(74, 222, 128, 0.1)',
                      bgcolor: 'rgba(74, 222, 128, 0.03)',
                      minWidth: { xs: 0, md: 150 },
                      transition: 'border-color 0.2s, background-color 0.2s',
                      '&:hover': {
                        borderColor: 'rgba(74, 222, 128, 0.2)',
                        bgcolor: 'rgba(74, 222, 128, 0.06)',
                      },
                    }}
                  >
                    <Typography
                      sx={{
                        fontFamily: F.mono,
                        fontSize: '0.85rem',
                        fontWeight: 500,
                        color: GREEN_BRIGHT,
                        mb: 0.5,
                      }}
                    >
                      {item.name}
                    </Typography>
                    <Typography
                      sx={{
                        fontFamily: F.body,
                        fontSize: '0.8rem',
                        color: '#6b8a6b',
                      }}
                    >
                      {item.desc}
                    </Typography>
                  </Box>
                ))}
              </Box>
            </Box>
          </Reveal>
        </Container>
      </Box>

      {/* ─── Footer ──────────────────────────────────────────────────────── */}
      <Box
        sx={{
          py: 6,
          bgcolor: '#050905',
          borderTop: '1px solid rgba(74, 222, 128, 0.06)',
        }}
      >
        <Container maxWidth="lg">
          {/* Sponsors */}
          <Box sx={{ mb: 4 }}>
            <Typography
              sx={{
                fontFamily: F.body,
                fontSize: '0.85rem',
                color: '#6b8a6b',
                mb: 2,
              }}
            >
              Supported by
            </Typography>
            <Box sx={{ display: 'flex', gap: 2.5, flexWrap: 'wrap', alignItems: 'center' }}>
              {[
                { src: harnoLogo, alt: 'Harno', h: '2.5rem' },
                { src: mkmLogo, alt: 'MKM', h: '2.5rem' },
                { src: itaLogo, alt: 'ITA', h: '2rem' },
              ].map((sponsor) => (
                <Box
                  key={sponsor.alt}
                  sx={{ p: 1.5, borderRadius: 1, bgcolor: '#fff' }}
                >
                  <img
                    src={sponsor.src}
                    alt={sponsor.alt}
                    style={{ height: sponsor.h, display: 'block' }}
                  />
                </Box>
              ))}
            </Box>
          </Box>

          {/* Bottom bar */}
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: 2,
              pt: 3,
              borderTop: '1px solid rgba(255,255,255,0.06)',
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Box
                component="img"
                src={logoSvg}
                alt=""
                sx={{
                  width: 18,
                  height: 18,
                  filter:
                    'invert(70%) sepia(30%) saturate(500%) hue-rotate(84deg) brightness(95%)',
                  opacity: 0.5,
                }}
              />
              <Typography
                sx={{
                  fontFamily: F.body,
                  fontSize: '0.8rem',
                  color: 'rgba(255,255,255,0.25)',
                }}
              >
                University of Tartu {new Date().getFullYear()}
              </Typography>
            </Box>
            <Box sx={{ display: 'flex', gap: 2.5 }}>
              {[
                { label: 'About', href: '/about' },
                { label: 'GitHub', href: config.repoUrl, external: true },
                { label: 'Discord', href: config.discordInviteUrl, external: true },
              ].map((link) => (
                <Box
                  key={link.label}
                  component="a"
                  href={link.href}
                  {...(link.external ? { target: '_blank', rel: 'noopener' } : {})}
                  sx={{
                    fontFamily: F.body,
                    fontSize: '0.8rem',
                    color: 'rgba(255,255,255,0.3)',
                    textDecoration: 'none',
                    '&:hover': { color: 'rgba(255,255,255,0.6)' },
                  }}
                >
                  {link.label}
                </Box>
              ))}
            </Box>
          </Box>
        </Container>
      </Box>
    </Box>
  )
}
