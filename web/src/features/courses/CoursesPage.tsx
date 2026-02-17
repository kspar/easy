import { useState } from 'react'
import {
  Typography,
  Card,
  CardContent,
  CircularProgress,
  Alert,
  Box,
  Chip,
  IconButton,
  Tooltip,
  LinearProgress,
} from '@mui/material'
import { GridViewOutlined, ViewListOutlined, LinkOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext.tsx'
import { useStudentCourses, useTeacherCourses } from '../../api/courses.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'

export const COLOR_PALETTE = [
  '#7986cb', // indigo
  '#4db6ac', // teal
  '#ff8a65', // deep orange
  '#9575cd', // deep purple
  '#4fc3f7', // light blue
  '#aed581', // light green
  '#f06292', // pink
  '#ffd54f', // amber
  '#a1887f', // brown
  '#90a4ae', // blue grey
]

export function stringToColor(str: string): string {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  return COLOR_PALETTE[Math.abs(hash) % COLOR_PALETTE.length]
}

type ViewMode = 'grid' | 'list'

const VIEW_MODE_KEY = 'courses.viewMode'

function useViewMode(): [ViewMode, (mode: ViewMode) => void] {
  const [mode, setMode] = useState<ViewMode>(() => {
    const stored = localStorage.getItem(VIEW_MODE_KEY)
    return stored === 'list' ? 'list' : 'grid'
  })
  const set = (m: ViewMode) => {
    localStorage.setItem(VIEW_MODE_KEY, m)
    setMode(m)
  }
  return [mode, set]
}

const gridSx = {
  display: 'grid',
  gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(3, 1fr)' },
  gap: 1.5,
}

const listSx = {
  display: 'flex',
  flexDirection: 'column' as const,
  gap: 1.5,
}

const cardSx = {
  cursor: 'pointer',
  transition: 'box-shadow 0.2s, transform 0.2s, background-color 0.2s',
  '&:hover': {
    transform: 'translateY(-2px)',
    backgroundColor: 'action.hover',
  },
}

function hoverShadow(color: string | null) {
  // MUI theme shadow 3
  const elevation =
    '0px 3px 3px -2px rgba(0,0,0,0.2), 0px 3px 4px 0px rgba(0,0,0,0.14), 0px 1px 8px 0px rgba(0,0,0,0.12)'
  const inset = color ? `inset 4px 0 0 0 ${color}` : ''
  return inset ? `${inset}, ${elevation}` : elevation
}

// TODO: Replace with real API data once backend endpoints are extended
function mockStudentProgress(courseId: string): { completed: number; total: number } {
  const hash = parseInt(courseId, 10) || courseId.charCodeAt(0)
  const total = 5 + (hash % 15)
  const completed = hash % (total + 1)
  return { completed, total }
}

// Activity level: 'active' = submissions in last 24h, 'recent' = last 7 days, 'dormant' = older/none
type ActivityLevel = 'active' | 'recent' | 'dormant'

const activityColors: Record<ActivityLevel, { color: string; glow?: string }> = {
  active: { color: '#43a047', glow: '0 0 6px 2px rgba(67,160,71,0.45)' },
  recent: { color: '#81c784' },
  dormant: { color: '#bdbdbd' },
}

const pulseKeyframes = {
  '@keyframes pulse': {
    '0%': { boxShadow: '0 0 6px 2px rgba(67,160,71,0.45)' },
    '50%': { boxShadow: '0 0 10px 4px rgba(67,160,71,0.25)' },
    '100%': { boxShadow: '0 0 6px 2px rgba(67,160,71,0.45)' },
  },
}

function getActivityLevel(lastSubmissionAt: string | null): ActivityLevel {
  if (!lastSubmissionAt) return 'dormant'
  const hoursAgo = (Date.now() - new Date(lastSubmissionAt).getTime()) / 3600000
  if (hoursAgo < 24) return 'active'
  if (hoursAgo < 168) return 'recent'
  return 'dormant'
}

export default function CoursesPage() {
  const { t } = useTranslation()
  const { activeRole } = useAuth()
  usePageTitle(t('courses.title'))

  if (activeRole === 'student') {
    return <StudentCourses />
  }
  return <TeacherCourses />
}

function ViewToggle({ mode, onChange }: { mode: ViewMode; onChange: (m: ViewMode) => void }) {
  const { t } = useTranslation()
  return (
    <>
      <Tooltip title={t('courses.viewGrid')}>
        <IconButton
          size="small"
          onClick={() => onChange('grid')}
          color={mode === 'grid' ? 'primary' : 'default'}
        >
          <GridViewOutlined fontSize="small" />
        </IconButton>
      </Tooltip>
      <Tooltip title={t('courses.viewList')}>
        <IconButton
          size="small"
          onClick={() => onChange('list')}
          color={mode === 'list' ? 'primary' : 'default'}
        >
          <ViewListOutlined fontSize="small" />
        </IconButton>
      </Tooltip>
    </>
  )
}

function StudentCourses() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { data: courses, isLoading, error } = useStudentCourses()
  const [viewMode, setViewMode] = useViewMode()

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Typography variant="h5">{t('courses.title')}</Typography>
        <ViewToggle mode={viewMode} onChange={setViewMode} />
      </Box>

      {isLoading && <CircularProgress />}
      {error && <Alert severity="error">{t('general.somethingWentWrong')}</Alert>}

      <Box sx={viewMode === 'grid' ? gridSx : listSx}>
        {courses?.map((course) => {
          const title = course.alias ?? course.title
          const color = viewMode === 'grid' ? course.color : null
          const progress = mockStudentProgress(course.id)
          return (
            <Card
              key={course.id}
              onClick={() => navigate(`/courses/${course.id}/exercises`)}
              sx={{
                ...cardSx,
                ...(color && { boxShadow: `inset 4px 0 0 0 ${color}` }),
                '&:hover': { ...cardSx['&:hover'], boxShadow: hoverShadow(color) },
              }}
            >
              <CardContent sx={{ py: 2, px: 2.5, '&:last-child': { pb: 2 } }}>
                <Typography variant="subtitle1" sx={{ lineHeight: 1.4 }}>
                  {title}
                </Typography>
                {progress.total > 0 && (
                  <Box sx={{ mt: 0.75, display: 'flex', alignItems: 'center', gap: 1 }}>
                    <LinearProgress
                      variant="determinate"
                      value={(progress.completed / progress.total) * 100}
                      sx={{ flexGrow: 1, height: 4, borderRadius: 2, opacity: 0.6 }}
                    />
                    <Typography variant="caption" color="text.disabled" sx={{ whiteSpace: 'nowrap', fontSize: '0.7rem' }}>
                      {progress.completed}/{progress.total}
                    </Typography>
                  </Box>
                )}
              </CardContent>
            </Card>
          )
        })}
      </Box>
    </>
  )
}

function TeacherCourses() {
  const { t } = useTranslation()
  const { activeRole } = useAuth()
  const isAdmin = activeRole === 'admin'
  const navigate = useNavigate()
  const { data: courses, isLoading, error } = useTeacherCourses()
  const [viewMode, setViewMode] = useViewMode()

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Typography variant="h5">
          {isAdmin ? t('courses.titleAdmin') : t('courses.title')}
        </Typography>
        <ViewToggle mode={viewMode} onChange={setViewMode} />
      </Box>

      {isLoading && <CircularProgress />}
      {error && <Alert severity="error">{t('general.somethingWentWrong')}</Alert>}

      <Box sx={viewMode === 'grid' ? gridSx : listSx}>
        {courses?.map((course) => {
          const title = isAdmin ? course.title : (course.alias ?? course.title)
          const color = viewMode === 'grid' ? course.color : null
          const activity = getActivityLevel(course.last_submission_at)
          return (
            <Card
              key={course.id}
              onClick={() => navigate(`/courses/${course.id}/exercises`)}
              sx={{
                ...cardSx,
                ...(color && { boxShadow: `inset 4px 0 0 0 ${color}` }),
                '&:hover': { ...cardSx['&:hover'], boxShadow: hoverShadow(color) },
              }}
            >
              <CardContent sx={{ py: 2, px: 2.5, '&:last-child': { pb: 2 } }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 2 }}>
                  <Box sx={{ minWidth: 0, overflow: 'hidden' }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
                      <Box
                        sx={{
                          width: 8,
                          height: 8,
                          borderRadius: '50%',
                          backgroundColor: activityColors[activity].color,
                          boxShadow: activityColors[activity].glow,
                          flexShrink: 0,
                          ...(activity === 'active' && {
                            ...pulseKeyframes,
                            animation: 'pulse 2s ease-in-out infinite',
                          }),
                        }}
                      />
                      <Typography variant="subtitle1" sx={{ lineHeight: 1.4, overflowWrap: 'break-word', minWidth: 0 }}>
                        {title}
                      </Typography>
                    </Box>
                    {isAdmin && course.alias && (
                      <Typography variant="caption" color="text.secondary" sx={{ ml: 2.5, display: 'block' }}>
                        {course.alias}
                      </Typography>
                    )}
                  </Box>
                  <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 0.5, flexShrink: 0 }}>
                    <Chip
                      label={`${course.student_count} ${course.student_count === 1 ? t('courses.studentSingular') : t('courses.studentPlural')}`}
                      size="small"
                      variant="outlined"
                    />
                    {course.moodle_short_name && (
                      <Chip
                        icon={<LinkOutlined />}
                        label="Moodle"
                        size="small"
                        variant="outlined"
                        color="info"
                      />
                    )}
                  </Box>
                </Box>
              </CardContent>
            </Card>
          )
        })}
      </Box>
    </>
  )
}