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
  Button,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Snackbar,
} from '@mui/material'
import { GridViewOutlined, ViewListOutlined, LinkOutlined, AddOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext.tsx'
import { useStudentCourses, useTeacherCourses, useCreateCourse } from '../../api/courses.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'

export const COLOR_PALETTE = [
  '#e57373', // red
  '#f06292', // pink
  '#9575cd', // purple
  '#7986cb', // indigo
  '#4fc3f7', // blue
  '#4db6ac', // teal
  '#aed581', // green
  '#dce775', // lime
  '#ffd54f', // amber
  '#ff8a65', // orange
  '#a1887f', // brown
  '#90a4ae', // grey
]

function randomColor() {
  return COLOR_PALETTE[Math.floor(Math.random() * COLOR_PALETTE.length)]
}

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
              <CardContent sx={{ py: 2, px: 2.5, '&:last-child': { pb: 2 }, height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                <Typography variant="subtitle1" sx={{ lineHeight: 1.4 }}>
                  {title}
                </Typography>
                {course.course_code && (
                  <Typography variant="caption" color="text.secondary">
                    {course.course_code}
                  </Typography>
                )}
              </CardContent>
            </Card>
          )
        })}
      </Box>
    </>
  )
}

function CreateCourseDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const createCourse = useCreateCourse()
  const [title, setTitle] = useState('')
  const [courseCode, setCourseCode] = useState('')
  const [color, setColor] = useState(randomColor)
  const [snackOpen, setSnackOpen] = useState(false)

  const handleSubmit = () => {
    createCourse.mutate(
      { title: title.trim(), color, ...(courseCode.trim() && { course_code: courseCode.trim() }) },
      {
        onSuccess: (data) => {
          setSnackOpen(true)
          onClose()
          setTitle('')
          setCourseCode('')
          setColor(randomColor())
          navigate(`/courses/${data.id}/exercises`)
        },
      },
    )
  }

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
        <DialogTitle>{t('courses.newCourse')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          <TextField
            label={t('courses.courseTitle')}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
            autoFocus
            inputProps={{ maxLength: 100 }}
          />
          <TextField
            label={t('courses.courseCode')}
            value={courseCode}
            onChange={(e) => setCourseCode(e.target.value)}
            inputProps={{ maxLength: 100 }}
          />
          <Box>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {t('courses.courseColor')}
            </Typography>
            <Box sx={{ display: 'flex', gap: '2%' }}>
              {COLOR_PALETTE.map((c) => (
                <Box
                  key={c}
                  onClick={() => setColor(c)}
                  sx={{
                    flex: 1,
                    aspectRatio: '1',
                    borderRadius: '50%',
                    backgroundColor: c,
                    cursor: 'pointer',
                    outline: color === c ? '2px solid' : 'none',
                    outlineColor: 'text.primary',
                    outlineOffset: 2,
                    transition: 'outline 0.15s',
                  }}
                />
              ))}
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{t('general.cancel')}</Button>
          <Button
            onClick={handleSubmit}
            variant="contained"
            disabled={!title.trim() || createCourse.isPending}
          >
            {createCourse.isPending ? t('general.adding') : t('general.add')}
          </Button>
        </DialogActions>
      </Dialog>
      <Snackbar
        open={snackOpen}
        autoHideDuration={3000}
        onClose={() => setSnackOpen(false)}
        message={t('courses.courseCreated')}
      />
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
  const [dialogOpen, setDialogOpen] = useState(false)

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Typography variant="h5">
          {isAdmin ? t('courses.titleAdmin') : t('courses.title')}
        </Typography>
        <ViewToggle mode={viewMode} onChange={setViewMode} />
        {isAdmin && (
          <Button
            startIcon={<AddOutlined />}
            size="small"
            onClick={() => setDialogOpen(true)}
            sx={{ ml: 'auto' }}
          >
            {t('courses.newCourse')}
          </Button>
        )}
      </Box>
      {isAdmin && <CreateCourseDialog open={dialogOpen} onClose={() => setDialogOpen(false)} />}

      {isLoading && <CircularProgress />}
      {error && <Alert severity="error">{t('general.somethingWentWrong')}</Alert>}

      <Box sx={viewMode === 'grid' ? gridSx : listSx}>
        {courses?.map((course) => {
          const title = isAdmin ? course.title : (course.alias ?? course.title)
          const color = viewMode === 'grid' ? course.color : null
          const activity = getActivityLevel(course.last_submission_at)
          const secondaryCode = course.moodle_short_name ?? course.course_code
          const secondaryParts = [secondaryCode, isAdmin && course.alias].filter(Boolean)
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
              <CardContent sx={{ py: 2, px: 2.5, '&:last-child': { pb: 2 }, height: '100%', display: 'flex', alignItems: 'center' }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 2, width: '100%' }}>
                  <Box sx={{ minWidth: 0, overflow: 'visible' }}>
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
                    {secondaryParts.length > 0 && (
                      <Typography variant="caption" color="text.secondary" sx={{ ml: 2.5, display: 'flex', alignItems: 'center', gap: 0.5 }}>
                        {course.moodle_short_name && <LinkOutlined sx={{ fontSize: 14 }} />}
                        {secondaryParts.join(' · ')}
                      </Typography>
                    )}
                  </Box>
                  <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 0.5, flexShrink: 0 }}>
                    <Chip
                      label={`${course.student_count} ${course.student_count === 1 ? t('courses.studentSingular') : t('courses.studentPlural')}`}
                      size="small"
                      variant="outlined"
                    />
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