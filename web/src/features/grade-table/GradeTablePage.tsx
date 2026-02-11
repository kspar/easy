import { useMemo } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
} from '@mui/material'
import { ArrowBack } from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useTeacherCourseExercises } from '../../api/exercises.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import type { TeacherCourseExercise } from '../../api/types.ts'

interface StudentGrades {
  id: string
  name: string
  grades: Map<string, number | null>
}

function gradeColor(
  grade: number | null,
  threshold: number,
): string | undefined {
  if (grade === null) return undefined
  return grade >= threshold ? 'success.main' : 'warning.main'
}

export default function GradeTablePage() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const {
    data: exercises,
    isLoading,
    error,
  } = useTeacherCourseExercises(courseId!)
  usePageTitle(t('grades.title'))

  const { students, sortedExercises } = useMemo(() => {
    if (!exercises) return { students: [], sortedExercises: [] }

    const sorted = [...exercises].sort(
      (a, b) => a.ordering_idx - b.ordering_idx,
    )

    // Collect all unique students across exercises
    const studentMap = new Map<string, StudentGrades>()
    for (const ex of sorted) {
      for (const row of ex.latest_submissions) {
        if (!studentMap.has(row.student_id)) {
          studentMap.set(row.student_id, {
            id: row.student_id,
            name: `${row.given_name} ${row.family_name}`,
            grades: new Map(),
          })
        }
        const grade = row.submission?.grade?.grade ?? null
        studentMap
          .get(row.student_id)!
          .grades.set(ex.course_exercise_id, grade)
      }
    }

    const studentList = [...studentMap.values()].sort((a, b) =>
      a.name.localeCompare(b.name),
    )

    return { students: studentList, sortedExercises: sorted }
  }, [exercises])

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBack />
        </IconButton>
        <Typography variant="h5">{t('grades.title')}</Typography>
      </Box>

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {sortedExercises.length === 0 && !isLoading && (
        <Typography color="text.secondary">
          {t('grades.emptyPlaceholder')}
        </Typography>
      )}

      {sortedExercises.length > 0 && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                <TableCell
                  sx={{
                    position: 'sticky',
                    left: 0,
                    bgcolor: 'background.paper',
                    zIndex: 3,
                  }}
                >
                  {t('general.name')}
                </TableCell>
                {sortedExercises.map((ex: TeacherCourseExercise) => (
                  <TableCell key={ex.course_exercise_id} align="center">
                    <Typography
                      variant="caption"
                      sx={{
                        display: 'block',
                        maxWidth: 100,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                      title={ex.effective_title}
                    >
                      {ex.effective_title}
                    </Typography>
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {students.map((student) => (
                <TableRow key={student.id}>
                  <TableCell
                    sx={{
                      position: 'sticky',
                      left: 0,
                      bgcolor: 'background.paper',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {student.name}
                  </TableCell>
                  {sortedExercises.map((ex: TeacherCourseExercise) => {
                    const grade =
                      student.grades.get(ex.course_exercise_id) ?? null
                    return (
                      <TableCell
                        key={ex.course_exercise_id}
                        align="center"
                        sx={{
                          color: gradeColor(grade, ex.grade_threshold),
                          fontWeight: grade !== null ? 500 : undefined,
                        }}
                      >
                        {grade !== null ? grade : '-'}
                      </TableCell>
                    )
                  })}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </>
  )
}
