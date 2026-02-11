import { useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Tab,
  Tabs,
  Chip,
  IconButton,
} from '@mui/material'
import { ArrowBack } from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { format } from 'date-fns'
import { et, enUS } from 'date-fns/locale'
import { useExerciseDetails } from '../../api/exercises.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import SubmitTab from './SubmitTab.tsx'
import SubmissionsTab from './SubmissionsTab.tsx'

export default function ExerciseSummaryPage() {
  const { courseId, courseExerciseId } = useParams<{
    courseId: string
    courseExerciseId: string
  }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const [tab, setTab] = useState(0)
  const dateFnsLocale = i18n.language === 'et' ? et : enUS

  const {
    data: exercise,
    isLoading,
    error,
  } = useExerciseDetails(courseId!, courseExerciseId!)

  usePageTitle(exercise?.effective_title)

  if (isLoading) return <CircularProgress />
  if (error)
    return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if (!exercise) return null

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBack />
        </IconButton>
        <Typography variant="h5">{exercise.effective_title}</Typography>
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
        <Chip
          label={
            exercise.grader_type === 'AUTO'
              ? t('exercises.gradedAutomatically')
              : t('exercises.gradedByTeacher')
          }
          size="small"
          variant="outlined"
        />
        {exercise.deadline && (
          <Chip
            label={`${t('exercises.deadline')}: ${format(new Date(exercise.deadline), 'PPp', { locale: dateFnsLocale })}`}
            size="small"
            variant="outlined"
          />
        )}
        {!exercise.is_open && (
          <Chip
            label={t('submission.exerciseClosed')}
            size="small"
            color="error"
            variant="outlined"
          />
        )}
      </Box>

      {exercise.text_html && (
        <Box
          sx={{ mb: 3 }}
          dangerouslySetInnerHTML={{ __html: exercise.text_html }}
        />
      )}

      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab label={t('submission.tabSubmit')} />
        <Tab label={t('submission.tabMySubmissions')} />
      </Tabs>

      {tab === 0 && (
        <SubmitTab
          courseId={courseId!}
          courseExerciseId={courseExerciseId!}
          exercise={exercise}
        />
      )}
      {tab === 1 && (
        <SubmissionsTab
          courseId={courseId!}
          courseExerciseId={courseExerciseId!}
        />
      )}
    </>
  )
}
