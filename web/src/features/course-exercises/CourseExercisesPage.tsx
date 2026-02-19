import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Chip,
  List,
  ListItemButton,
  ListItemText,
  ListItemIcon,
  IconButton,
} from '@mui/material'
import {
  CheckCircle,
  RadioButtonUnchecked,
  HourglassEmptyOutlined,
  CircleOutlined,
  ArrowBackOutlined,
} from '@mui/icons-material'
import RobotPlaceholder from '../../components/RobotPlaceholder.tsx'
import { useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { format, isPast, differenceInHours } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import { useAuth } from '../../auth/AuthContext.tsx'
import usePageTitle from '../../hooks/usePageTitle.ts'
import {
  useCourseExercises,
  useTeacherCourseExercises,
} from '../../api/exercises.ts'
import { useUpdateLastAccess } from '../../api/courses.ts'
import type {
  CourseExercise,
  StudentExerciseStatus,
  TeacherCourseExercise,
} from '../../api/types.ts'

function statusIcon(status: StudentExerciseStatus) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle color="success" />
    case 'STARTED':
      return <CircleOutlined color="warning" />
    case 'UNGRADED':
      return <HourglassEmptyOutlined color="info" />
    case 'UNSTARTED':
      return <RadioButtonUnchecked color="disabled" />
  }
}

function statusLabel(status: StudentExerciseStatus, t: (k: string) => string) {
  switch (status) {
    case 'COMPLETED':
      return t('exercises.completed')
    case 'STARTED':
      return t('exercises.started')
    case 'UNGRADED':
      return t('exercises.ungraded')
    case 'UNSTARTED':
      return t('exercises.unstarted')
  }
}

function statusColor(
  status: StudentExerciseStatus,
): 'success' | 'warning' | 'info' | 'default' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'STARTED':
      return 'warning'
    case 'UNGRADED':
      return 'info'
    case 'UNSTARTED':
      return 'default'
  }
}

export default function CourseExercisesPage() {
  const { courseId } = useParams<{ courseId: string }>()
  const { t } = useTranslation()
  const { activeRole } = useAuth()
  usePageTitle(t('exercises.title'))

  const updateAccess = useUpdateLastAccess(activeRole!, courseId!)
  useEffect(() => { updateAccess.mutate() }, [courseId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (activeRole === 'student') {
    return <StudentExercises />
  }
  return <TeacherExercises />
}

function StudentExercises() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const { data: exercises, isLoading, error } = useCourseExercises(courseId!)
  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  return (
    <>
      <Header />

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {exercises && exercises.length === 0 && (
        <RobotPlaceholder message={t('exercises.noExercises')} />
      )}

      {exercises && exercises.length > 0 && (
        <List disablePadding>
          {exercises
            .sort((a, b) => a.ordering_idx - b.ordering_idx)
            .map((ex: CourseExercise) => (
              <StudentExerciseRow
                key={ex.id}
                exercise={ex}
                dateFnsLocale={dateFnsLocale}
                onClick={() =>
                  navigate(`/courses/${courseId}/exercises/${ex.id}`)
                }
              />
            ))}
        </List>
      )}
    </>
  )
}

function TeacherExercises() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const {
    data: exercises,
    isLoading,
    error,
  } = useTeacherCourseExercises(courseId!)
  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  return (
    <>
      <Header />

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {exercises && exercises.length === 0 && (
        <RobotPlaceholder message={t('exercises.noExercises')} />
      )}

      {exercises && exercises.length > 0 && (
        <List disablePadding>
          {exercises
            .sort((a, b) => a.ordering_idx - b.ordering_idx)
            .map((ex: TeacherCourseExercise) => (
              <TeacherExerciseRow
                key={ex.course_exercise_id}
                exercise={ex}
                dateFnsLocale={dateFnsLocale}
                onClick={() =>
                  navigate(
                    `/courses/${courseId}/exercises/${ex.course_exercise_id}`,
                  )
                }
              />
            ))}
        </List>
      )}
    </>
  )
}

function Header() {
  const navigate = useNavigate()
  const { t } = useTranslation()

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
      <IconButton onClick={() => navigate('/courses')} size="small">
        <ArrowBackOutlined />
      </IconButton>
      <Typography variant="h5">{t('exercises.title')}</Typography>
    </Box>
  )
}

function StudentExerciseRow({
  exercise,
  dateFnsLocale,
  onClick,
}: {
  exercise: CourseExercise
  dateFnsLocale: Locale
  onClick: () => void
}) {
  const { t } = useTranslation()
  const deadline = exercise.deadline ? new Date(exercise.deadline) : null
  const isPastDeadline = deadline ? isPast(deadline) : false
  const isApproaching = deadline ? !isPastDeadline && differenceInHours(deadline, new Date()) < 24 : false

  return (
    <ListItemButton onClick={onClick} sx={{ borderRadius: 1, mb: 0.5 }}>
      <ListItemIcon sx={{ minWidth: 40 }}>
        {statusIcon(exercise.status)}
      </ListItemIcon>
      <ListItemText
        primary={exercise.effective_title}
        secondary={
          deadline
            ? `${t('exercises.deadline')}: ${format(deadline, 'PPp', { locale: dateFnsLocale })}`
            : undefined
        }
        secondaryTypographyProps={{
          color: exercise.status === 'COMPLETED' || !exercise.is_open ? 'text.secondary' : isPastDeadline ? 'error' : isApproaching ? 'warning.main' : 'text.secondary',
        }}
      />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        {exercise.grade && (
          <Typography variant="body2" fontWeight={500}>
            {exercise.grade.grade} / 100
          </Typography>
        )}
        {exercise.status !== 'UNSTARTED' && (
          <Chip
            label={statusLabel(exercise.status, t)}
            color={statusColor(exercise.status)}
            size="small"
            variant="outlined"
            sx={{ display: { xs: 'none', sm: 'flex' } }}
          />
        )}
      </Box>
    </ListItemButton>
  )
}

function TeacherExerciseRow({
  exercise,
  dateFnsLocale,
  onClick,
}: {
  exercise: TeacherCourseExercise
  dateFnsLocale: Locale
  onClick: () => void
}) {
  const { t } = useTranslation()
  const deadline = exercise.soft_deadline
    ? new Date(exercise.soft_deadline)
    : null
  const total =
    exercise.unstarted_count +
    exercise.ungraded_count +
    exercise.started_count +
    exercise.completed_count

  return (
    <ListItemButton onClick={onClick} sx={{ borderRadius: 1, mb: 0.5 }}>
      <ListItemText
        primary={
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="body1">
              {exercise.effective_title}
            </Typography>
            {!exercise.student_visible && (
              <Chip
                label={t('exercises.hidden')}
                size="small"
                color="default"
              />
            )}
          </Box>
        }
        secondary={
          deadline
            ? `${t('exercises.deadline')}: ${format(deadline, 'PPp', { locale: dateFnsLocale })}`
            : undefined
        }
      />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Chip
          label={`${exercise.completed_count}/${total}`}
          color="success"
          size="small"
          variant="outlined"
          title={t('exercises.completed')}
        />
        {exercise.ungraded_count > 0 && (
          <Chip
            label={`${exercise.ungraded_count} ${t('exercises.ungraded')}`}
            color="info"
            size="small"
          />
        )}
        <Chip
          label={
            exercise.grader_type === 'AUTO'
              ? t('exercises.gradedAutomatically')
              : t('exercises.gradedByTeacher')
          }
          size="small"
          variant="outlined"
        />
      </Box>
    </ListItemButton>
  )
}