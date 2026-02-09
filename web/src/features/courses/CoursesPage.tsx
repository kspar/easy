import {
  Typography,
  Card,
  CardActionArea,
  CardContent,
  CircularProgress,
  Alert,
  Box,
  Chip,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext.tsx'
import { useStudentCourses, useTeacherCourses } from '../../api/courses.ts'
import { formatDistanceToNow } from 'date-fns'
import { et, enUS } from 'date-fns/locale'

export default function CoursesPage() {
  const { t, i18n } = useTranslation()
  const { activeRole } = useAuth()

  if (activeRole === 'student') {
    return <StudentCourses />
  }
  return <TeacherCourses />
}

function StudentCourses() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { data: courses, isLoading, error } = useStudentCourses()
  const dateFnsLocale = i18n.language === 'et' ? et : enUS

  return (
    <>
      <Typography variant="h5" gutterBottom>
        {t('courses.title')}
      </Typography>

      {isLoading && <CircularProgress />}
      {error && <Alert severity="error">{t('general.somethingWentWrong')}</Alert>}

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        {courses?.map((course) => (
          <Card key={course.id} variant="outlined">
            <CardActionArea
              onClick={() => navigate(`/courses/${course.id}/exercises`)}
            >
              <CardContent
                sx={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <Box>
                  <Typography variant="subtitle1">
                    {course.title}
                  </Typography>
                  {course.alias && (
                    <Typography variant="body2" color="text.secondary">
                      {course.alias}
                    </Typography>
                  )}
                </Box>
                <Typography variant="caption" color="text.secondary">
                  {formatDistanceToNow(new Date(course.last_accessed), {
                    addSuffix: true,
                    locale: dateFnsLocale,
                  })}
                </Typography>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Box>
    </>
  )
}

function TeacherCourses() {
  const { t, i18n } = useTranslation()
  const { activeRole } = useAuth()
  const navigate = useNavigate()
  const { data: courses, isLoading, error } = useTeacherCourses()
  const dateFnsLocale = i18n.language === 'et' ? et : enUS

  return (
    <>
      <Typography variant="h5" gutterBottom>
        {activeRole === 'admin' ? t('courses.titleAdmin') : t('courses.title')}
      </Typography>

      {isLoading && <CircularProgress />}
      {error && <Alert severity="error">{t('general.somethingWentWrong')}</Alert>}

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        {courses?.map((course) => (
          <Card key={course.id} variant="outlined">
            <CardActionArea
              onClick={() => navigate(`/courses/${course.id}/exercises`)}
            >
              <CardContent
                sx={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <Box>
                  <Typography variant="subtitle1">
                    {course.title}
                  </Typography>
                  {course.alias && (
                    <Typography variant="body2" color="text.secondary">
                      {course.alias}
                    </Typography>
                  )}
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <Chip
                    label={`${course.student_count} ${course.student_count === 1 ? t('courses.studentSingular') : t('courses.studentPlural')}`}
                    size="small"
                    variant="outlined"
                  />
                  <Typography variant="caption" color="text.secondary">
                    {formatDistanceToNow(new Date(course.last_accessed), {
                      addSuffix: true,
                      locale: dateFnsLocale,
                    })}
                  </Typography>
                </Box>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Box>
    </>
  )
}