import { useMemo, useState } from 'react'
import {
  Typography,
  Card,
  CardContent,
  CircularProgress,
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
  Menu,
  MenuItem,
} from '@mui/material'
import {
  GridViewOutlined,
  ViewListOutlined,
  LinkOutlined,
  AddOutlined,
  SortOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth.ts'
import { useStudentCourses, useTeacherCourses, useCreateCourse } from '../../api/courses.ts'
import type { StudentCourse, TeacherCourse } from '../../api/types.ts'
import { readString, writeString } from '../../api/localStorage.ts'
import { spaLinkProps } from '../../components/spaLink.ts'
import { alpha } from '@mui/material/styles'
import { GREEN } from '../../theme/theme.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { COLOR_PALETTE, randomColor } from './course-colors.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'


type ViewMode = 'grid' | 'list'

const VIEW_MODE_KEY = 'courses.viewMode'

function useViewMode(): [ViewMode, (mode: ViewMode) => void] {
  const [mode, setMode] = useState<ViewMode>(() =>
    readString(VIEW_MODE_KEY) === 'list' ? 'list' : 'grid',
  )
  const set = (m: ViewMode) => {
    // Guarded, via the shared helpers: a bare `setItem` here throws from inside a click handler in
    // Safari private browsing and on a full quota, on the first screen of every session.
    writeString(VIEW_MODE_KEY, m)
    setMode(m)
  }
  return [mode, set]
}

// 'recent' is the order the page has always had: the courses you opened most recently, first.
// 'activity' reads the same field as the activity dot, so the pulsing courses come to the top —
// teachers and admins only, because GET /v2/student/courses returns no activity field (EZ-1856).
type SortMode = 'recent' | 'activity' | 'name'

const SORT_MODE_KEY = 'courses.sortMode'

const STUDENT_SORT_MODES: SortMode[] = ['recent', 'name']
const TEACHER_SORT_MODES: SortMode[] = ['recent', 'activity', 'name']

const sortLabelKeys: Record<SortMode, string> = {
  recent: 'courses.sortByRecent',
  activity: 'courses.sortByActivity',
  name: 'courses.sortByName',
}

function useSortMode(allowed: SortMode[]): [SortMode, (mode: SortMode) => void] {
  const [mode, setMode] = useState<SortMode>(() => {
    const stored = readString(SORT_MODE_KEY) as SortMode | null
    // A teacher who picked 'activity' and then switches to the student role would otherwise land
    // on a mode this list cannot offer, so an unavailable stored mode falls back to the default.
    return stored && allowed.includes(stored) ? stored : 'recent'
  })
  const set = (m: SortMode) => {
    writeString(SORT_MODE_KEY, m)
    setMode(m)
  }
  return [mode, set]
}

// The title on the card: a teacher sees their own alias for the course, an admin the real title.
const studentTitle = (course: StudentCourse) => course.alias ?? course.title
const teacherTitle = (course: TeacherCourse, isAdmin: boolean) =>
  isAdmin ? course.title : (course.alias ?? course.title)

type SortableCourse = {
  last_accessed: string
  last_submission_at?: string | null
}

function sortCourses<T extends SortableCourse>(
  courses: T[],
  mode: SortMode,
  displayTitle: (course: T) => string,
): T[] {
  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
  // Sorting by the title on the card, not by `title`: for a teacher the card shows the alias.
  const byName = (a: T, b: T) => collator.compare(displayTitle(a), displayTitle(b))
  const time = (at: string | null | undefined) => (at ? new Date(at).getTime() : 0)
  return [...courses].sort((a, b) => {
    if (mode === 'name') return byName(a, b)
    if (mode === 'activity') {
      return time(b.last_submission_at) - time(a.last_submission_at) || byName(a, b)
    }
    return time(b.last_accessed) - time(a.last_accessed) || byName(a, b)
  })
}

function SortMenu({
  mode,
  modes,
  onChange,
}: {
  mode: SortMode
  modes: SortMode[]
  onChange: (mode: SortMode) => void
}) {
  const { t } = useTranslation()
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  return (
    <>
      <Button
        variant="outlined"
        size="small"
        startIcon={<SortOutlined />}
        onClick={(e) => setAnchor(e.currentTarget)}
        sx={{ height: 32 }}
      >
        {t(sortLabelKeys[mode])}
      </Button>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        {modes.map((m) => (
          <MenuItem
            key={m}
            selected={mode === m}
            onClick={() => {
              onChange(m)
              setAnchor(null)
            }}
          >
            {t(sortLabelKeys[m])}
          </MenuItem>
        ))}
      </Menu>
    </>
  )
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
  // The card *is* the anchor, so the browser's default link underline was being drawn under the
  // course title and its code — the only place in the app where a link sat underlined at rest,
  // because every other `component="a"` sets this and these two never did. Nothing is lost: the
  // card already announces itself with the pointer, the lift and the shadow on hover, and it
  // carries a real `href`, so the status bar, middle-click and the context menu all still work.
  textDecoration: 'none',
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

// The GREEN ramp's own steps (EZ-1798, one green): the Material greens that used to sit here
// were a separate family from the brand colour two pixels away. Taken from the exported ramp so
// the next retune cannot strand a copy.
const activityColors: Record<ActivityLevel, { color: string; glow?: string }> = {
  active: { color: GREEN[600], glow: `0 0 6px 2px ${alpha(GREEN[600], 0.45)}` },
  recent: { color: GREEN[300] },
  dormant: { color: '#bdbdbd' },
}

const pulseKeyframes = {
  '@keyframes pulse': {
    '0%': { boxShadow: `0 0 6px 2px ${alpha(GREEN[600], 0.45)}` },
    '50%': { boxShadow: `0 0 10px 4px ${alpha(GREEN[600], 0.25)}` },
    '100%': { boxShadow: `0 0 6px 2px ${alpha(GREEN[600], 0.45)}` },
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
  const [sortMode, setSortMode] = useSortMode(STUDENT_SORT_MODES)

  const sorted = useMemo(
    () => sortCourses(courses ?? [], sortMode, studentTitle),
    [courses, sortMode],
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Typography variant="h5">{t('courses.title')}</Typography>
        <ViewToggle mode={viewMode} onChange={setViewMode} />
        <Box sx={{ ml: 'auto' }}>
          <SortMenu mode={sortMode} modes={STUDENT_SORT_MODES} onChange={setSortMode} />
        </Box>
      </Box>

      {isLoading && <CircularProgress />}
      {error && <ErrorAlert error={error} />}

      <Box sx={viewMode === 'grid' ? gridSx : listSx}>
        {sorted.map((course) => {
          const title = studentTitle(course)
          const color = viewMode === 'grid' ? course.color : null
          return (
            <Card
              key={course.id}
              component="a"
              {...spaLinkProps(`/courses/${course.id}/exercises`, navigate)}
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
  const [sortMode, setSortMode] = useSortMode(TEACHER_SORT_MODES)
  const [dialogOpen, setDialogOpen] = useState(false)

  const sorted = useMemo(
    () => sortCourses(courses ?? [], sortMode, (c) => teacherTitle(c, isAdmin)),
    [courses, sortMode, isAdmin],
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <Typography variant="h5">
          {isAdmin ? t('courses.titleAdmin') : t('courses.title')}
        </Typography>
        <ViewToggle mode={viewMode} onChange={setViewMode} />
        <Box sx={{ ml: 'auto', display: 'flex', alignItems: 'center', gap: 1 }}>
          <SortMenu mode={sortMode} modes={TEACHER_SORT_MODES} onChange={setSortMode} />
          {isAdmin && (
            <Button startIcon={<AddOutlined />} size="small" onClick={() => setDialogOpen(true)}>
              {t('courses.newCourse')}
            </Button>
          )}
        </Box>
      </Box>
      {isAdmin && <CreateCourseDialog open={dialogOpen} onClose={() => setDialogOpen(false)} />}

      {isLoading && <CircularProgress />}
      {error && <ErrorAlert error={error} />}

      <Box sx={viewMode === 'grid' ? gridSx : listSx}>
        {sorted.map((course) => {
          const title = teacherTitle(course, isAdmin)
          const color = viewMode === 'grid' ? course.color : null
          const activity = getActivityLevel(course.last_submission_at)
          const secondaryCode = course.moodle_short_name ?? course.course_code
          const secondaryParts = [secondaryCode, isAdmin && course.alias].filter(Boolean)
          return (
            <Card
              key={course.id}
              component="a"
              {...spaLinkProps(`/courses/${course.id}/exercises`, navigate)}
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