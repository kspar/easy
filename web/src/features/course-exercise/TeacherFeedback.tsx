import {
  Box,
  Chip,
  CircularProgress,
  Alert,
  Divider,
  Paper,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import RelativeTime from '../../components/RelativeTime.tsx'
import { useTeacherActivities } from '../../api/exercises.ts'

export default function TeacherFeedback({
  courseId,
  courseExerciseId,
}: {
  courseId: string
  courseExerciseId: string
}) {
  const { t } = useTranslation()

  const { data: activities, isLoading, error } = useTeacherActivities(
    courseId,
    courseExerciseId,
  )

  if (isLoading) return <CircularProgress size={24} />
  if (error) return <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
  if (!activities || activities.length === 0) return null

  return (
    <Box>
      <Divider sx={{ my: 3 }} />
      <Typography variant="h6" gutterBottom>
        {t('submission.teacherFeedback')}
      </Typography>

      {[...activities].reverse().map((activity) => (
        <Paper key={activity.id} variant="outlined" sx={{ p: 2, mb: 1.5 }}>
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'baseline',
              mb: 1,
            }}
          >
            <Typography variant="subtitle2">
              {activity.teacher.given_name} {activity.teacher.family_name}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              <RelativeTime date={activity.created_at} />
              {' · '}
              {t('submission.submissionNr', { nr: activity.submission_number })}
            </Typography>
          </Box>

          {activity.grade != null && (
            <Chip
              label={t('submission.gradedPoints', { points: activity.grade })}
              size="small"
              color="primary"
              variant="outlined"
              sx={{ mb: 1, fontWeight: 600 }}
            />
          )}

          {activity.feedback && (
            <Box
              sx={{ '& p:first-of-type': { mt: 0 }, '& p:last-of-type': { mb: 0 } }}
              dangerouslySetInnerHTML={{
                __html: activity.feedback.feedback_html,
              }}
            />
          )}
        </Paper>
      ))}
    </Box>
  )
}
