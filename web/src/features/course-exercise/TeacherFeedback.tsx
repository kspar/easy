import {
  Box,
  CircularProgress,
  Alert,
  Divider,
  Paper,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { format } from 'date-fns'
import { et, enUS } from 'date-fns/locale'
import { useTeacherActivities } from '../../api/exercises.ts'

export default function TeacherFeedback({
  courseId,
  courseExerciseId,
}: {
  courseId: string
  courseExerciseId: string
}) {
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enUS

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

      {activities.map((activity) => (
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
              {format(new Date(activity.created_at), 'PPp', {
                locale: dateFnsLocale,
              })}
            </Typography>
          </Box>

          {activity.grade != null && (
            <Typography variant="body2" sx={{ mb: 1 }} color="primary">
              {t('submission.gradedPoints', { points: activity.grade })}
            </Typography>
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

      <Tooltip title={t('submission.replyComing')}>
        <TextField
          fullWidth
          size="small"
          placeholder={t('submission.replyPlaceholder')}
          disabled
          sx={{ mt: 1 }}
        />
      </Tooltip>
    </Box>
  )
}
