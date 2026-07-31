import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  Typography,
  Box,
  Snackbar,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/AuthContext.tsx'
import { useCourse, useUpdateCourse } from '../../api/courses.ts'
import { COLOR_PALETTE } from '../courses/CoursesPage.tsx'

export default function EditCourseDialog({
  courseId,
  open,
  onClose,
}: {
  courseId: string
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { activeRole } = useAuth()
  const isAdmin = activeRole === 'admin'
  const { data: course } = useCourse(courseId)
  const updateCourse = useUpdateCourse(courseId)

  const [title, setTitle] = useState('')
  const [alias, setAlias] = useState('')
  const [courseCode, setCourseCode] = useState('')
  const [color, setColor] = useState('')
  const [snackOpen, setSnackOpen] = useState(false)

  useEffect(() => {
    if (course && open) {
      setTitle(course.title)
      setAlias(course.alias ?? '')
      setCourseCode(course.course_code ?? '')
      setColor(course.color)
    }
  }, [course, open])

  function handleSave() {
    updateCourse.mutate(
      {
        title: title.trim(),
        alias: alias.trim() || null,
        color,
        course_code: courseCode.trim() || null,
      },
      {
        onSuccess: () => {
          onClose()
          setSnackOpen(true)
        },
      },
    )
  }

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
        <DialogTitle>{t('courses.courseSettings')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          <TextField
            label={t('courses.courseIdentifier')}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
            disabled={!isAdmin}
            inputProps={{ maxLength: 100 }}
          />
          {isAdmin && (
            <TextField
              label={t('courses.courseCode')}
              value={courseCode}
              onChange={(e) => setCourseCode(e.target.value)}
              inputProps={{ maxLength: 100 }}
            />
          )}
          <TextField
            label={t('courses.courseName')}
            value={alias}
            onChange={(e) => setAlias(e.target.value)}
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
            onClick={handleSave}
            variant="contained"
            disabled={!title.trim() || updateCourse.isPending}
          >
            {updateCourse.isPending ? t('general.saving') : t('general.save')}
          </Button>
        </DialogActions>
      </Dialog>
      <Snackbar
        open={snackOpen}
        autoHideDuration={3000}
        onClose={() => setSnackOpen(false)}
        message={t('courses.courseSaved')}
      />
    </>
  )
}
