import { useCallback, useMemo, useState, type MouseEvent } from 'react'
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
  TableSortLabel,
  Paper,
  Chip,
  Menu,
  MenuItem,
  Button,
  Tooltip,
} from '@mui/material'
import {
  ArrowBackOutlined,
  ArrowDropDownOutlined,
  DoneOutlined,
  FileDownloadOutlined,
  FaceOutlined,
} from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useTeacherCourseExercises, useCourseGroups } from '../../api/exercises.ts'
import { spaLinkProps } from '../../components/spaLink.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import type { StudentExerciseStatus, TeacherCourseExercise } from '../../api/types.ts'
import {
  buildRows,
  compareStudents,
  csvFilename,
  toCsv,
  type SortDir,
} from './gradeTable.ts'

function statusColor(status: StudentExerciseStatus): string | undefined {
  switch (status) {
    case 'COMPLETED':
      return 'success.main'
    case 'STARTED':
      return 'warning.main'
    case 'UNGRADED':
      return 'info.main'
    default:
      return undefined
  }
}

function defaultSortDir(key: string): SortDir {
  return key === 'name' ? 'asc' : 'desc'
}

/** Offset sort label so text is centered despite the arrow icon (18px icon + 8px margins = 26px) */
const sortLabelSx = {
  ml: '13px',
  mr: '-13px',
} as const

/** Same offset + hide arrow when not active/hovered */
const sortLabelInactiveSx = {
  ...sortLabelSx,
  '& .MuiTableSortLabel-icon': { opacity: 0 },
  '&:hover .MuiTableSortLabel-icon': { opacity: 0.5 },
} as const

const sortedColBg = 'action.hover'
const sortedColHoverBg = 'action.selected'

const stickyColSx = {
  position: 'sticky',
  left: 0,
  bgcolor: 'background.paper',
  zIndex: 1,
} as const

const headerStickyColSx = {
  ...stickyColSx,
  zIndex: 3,
} as const

export default function GradeTablePage() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  usePageTitle(t('grades.title'))

  // Filter & sort state
  const [filterGroup, setFilterGroup] = useSavedGroup(courseId!)
  const [filterGroupAnchor, setFilterGroupAnchor] = useState<Element | null>(null)
  const [showSubCount, setShowSubCount] = useState(false)
  // sortKey: 'name' | 'completion' | courseExerciseId
  const [sortKey, setSortKey] = useState('name')
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const handleSort = (key: string) => {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir(defaultSortDir(key))
    }
  }

  // Data fetching
  const { data: groups } = useCourseGroups(courseId!)
  const {
    data: exercises,
    isLoading,
    error,
  } = useTeacherCourseExercises(courseId!, filterGroup || undefined)

  const { students, sortedExercises, exerciseFinishedCounts } = useMemo(() => {
    const model = buildRows(exercises)
    return {
      ...model,
      students: [...model.students].sort(
        compareStudents(sortKey, sortDir, model.sortedExercises),
      ),
    }
  }, [exercises, sortKey, sortDir])

  // CSV export
  const handleExport = useCallback(() => {
    if (!sortedExercises.length || !students.length) return

    const csv = toCsv(students, sortedExercises, showSubCount, {
      name: t('general.name'),
      submissionCount: t('grades.showSubmissionCount'),
    })

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = csvFilename(courseId, Date.now())
    a.click()
    URL.revokeObjectURL(url)
  }, [sortedExercises, students, showSubCount, courseId, t])

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('grades.title')}</Typography>
      </Box>

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {!isLoading && !error && sortedExercises.length === 0 && (
        <Typography color="text.secondary">
          {t('grades.emptyPlaceholder')}
        </Typography>
      )}

      {sortedExercises.length > 0 && (
        <>
          {/* Filter bar */}
          <Box sx={{ display: 'flex', gap: 0.75, mb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
            {/* Group filter */}
            {groups && groups.length > 0 && (
              <>
                <Chip
                  label={filterGroup
                    ? groups.find((g) => g.id === filterGroup)?.name
                    : t('participants.groups')
                  }
                  deleteIcon={<ArrowDropDownOutlined />}
                  onDelete={(e) => setFilterGroupAnchor(e.currentTarget.closest('div'))}
                  onClick={(e) => setFilterGroupAnchor(e.currentTarget)}
                  variant={filterGroup ? 'filled' : 'outlined'}
                  color={filterGroup ? 'primary' : 'default'}
                />
                <Menu
                  anchorEl={filterGroupAnchor}
                  open={!!filterGroupAnchor}
                  onClose={() => setFilterGroupAnchor(null)}
                >
                  <MenuItem
                    selected={!filterGroup}
                    onClick={() => {
                      setFilterGroup('')
                      setFilterGroupAnchor(null)
                    }}
                  >
                    {t('participants.allGroups')}
                  </MenuItem>
                  {groups.map((g) => (
                    <MenuItem
                      key={g.id}
                      selected={filterGroup === g.id}
                      onClick={() => {
                        setFilterGroup(g.id)
                        setFilterGroupAnchor(null)
                      }}
                    >
                      {g.name}
                    </MenuItem>
                  ))}
                </Menu>
              </>
            )}

            {/* Submission count toggle */}
            <Chip
              icon={showSubCount ? <DoneOutlined /> : undefined}
              label={t('grades.showSubmissionCount')}
              variant={showSubCount ? 'filled' : 'outlined'}
              color={showSubCount ? 'primary' : 'default'}
              onClick={() => setShowSubCount((v) => !v)}
            />

            {/* Spacer */}
            <Box sx={{ flex: 1 }} />

            {/* Export button */}
            <Button
              variant="outlined"
              size="small"
              startIcon={<FileDownloadOutlined />}
              onClick={handleExport}
              sx={{ height: 32 }}
            >
              {t('grades.exportGrades')}
            </Button>
          </Box>

          {/* Grade table */}
          <TableContainer component={Paper} variant="outlined">
            <Table size="small" stickyHeader sx={{
              '& .MuiTableCell-sizeSmall': { px: 0.75 },
              '& .MuiTableCell-sizeSmall:last-child': { pr: 2 },
              '& .MuiTableBody-root .MuiTableRow-root:hover .MuiTableCell-root': { bgcolor: sortedColBg },
              '& .MuiTableBody-root .MuiTableRow-root:hover .sorted-col': { bgcolor: sortedColHoverBg },
            }}>
              <TableHead>
                <TableRow>
                  {/* Name column — sortable */}
                  <TableCell sx={{ ...headerStickyColSx, ...(sortKey === 'name' && { bgcolor: sortedColBg }) }}>
                    <TableSortLabel
                      active={sortKey === 'name'}
                      direction={sortKey === 'name' ? sortDir : 'asc'}
                      onClick={() => handleSort('name')}
                      sx={sortKey !== 'name' ? sortLabelInactiveSx : sortLabelSx}
                    >
                      {t('general.name')}
                    </TableSortLabel>
                  </TableCell>

                  {/* Σ column — sortable by completion */}
                  <TableCell align="center" sx={{ whiteSpace: 'nowrap', ...(sortKey === 'completion' && { bgcolor: sortedColBg }) }}>
                    <TableSortLabel
                      active={sortKey === 'completion'}
                      direction={sortKey === 'completion' ? sortDir : 'desc'}
                      onClick={() => handleSort('completion')}
                      sx={sortKey !== 'completion' ? sortLabelInactiveSx : sortLabelSx}
                    >
                      {'Σ (' + sortedExercises.length + ')'}
                    </TableSortLabel>
                  </TableCell>

                  {/* Exercise columns — sortable by grade */}
                  {sortedExercises.map((ex: TeacherCourseExercise) => {
                    const isActive = sortKey === ex.course_exercise_id
                    return (
                      <TableCell key={ex.course_exercise_id} align="center" sx={isActive ? { bgcolor: sortedColBg } : undefined}>
                        <TableSortLabel
                          active={isActive}
                          direction={isActive ? sortDir : 'desc'}
                          onClick={() => handleSort(ex.course_exercise_id)}
                          sx={!isActive ? sortLabelInactiveSx : sortLabelSx}
                        >
                          <Typography
                            variant="caption"
                            component="a"
                            {...spaLinkProps(`/courses/${courseId}/exercises/${ex.course_exercise_id}`, navigate)}
                            onClick={(e: MouseEvent) => {
                              e.stopPropagation()
                              if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
                              e.preventDefault()
                              navigate(`/courses/${courseId}/exercises/${ex.course_exercise_id}`)
                            }}
                            sx={{
                              display: 'inline-block',
                              maxWidth: 100,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              verticalAlign: 'middle',
                              color: ex.student_visible ? 'text.primary' : 'text.disabled',
                              textDecoration: 'none',
                              '&:hover': { textDecoration: 'underline' },
                            }}
                            title={ex.effective_title}
                          >
                            {ex.effective_title}
                          </Typography>
                        </TableSortLabel>
                      </TableCell>
                    )
                  })}
                </TableRow>
              </TableHead>
              <TableBody>
                {/* Summary row */}
                <TableRow>
                  <TableCell className={sortKey === 'name' ? 'sorted-col' : undefined} sx={{ ...stickyColSx, whiteSpace: 'nowrap', color: 'text.secondary', ...(sortKey === 'name' && { bgcolor: sortedColBg }) }}>
                    {'Σ (' + students.length + ')'}
                  </TableCell>
                  <TableCell className={sortKey === 'completion' ? 'sorted-col' : undefined} sx={sortKey === 'completion' ? { bgcolor: sortedColBg } : undefined} />
                  {exerciseFinishedCounts.map((count, i) => (
                    <TableCell key={i} align="center" className={sortKey === sortedExercises[i]?.course_exercise_id ? 'sorted-col' : undefined} sx={{ color: 'text.secondary', ...(sortKey === sortedExercises[i]?.course_exercise_id && { bgcolor: sortedColBg }) }}>
                      {count}
                    </TableCell>
                  ))}
                </TableRow>

                {/* Student rows */}
                {students.map((student) => (
                  <TableRow key={student.id}>
                    <Tooltip title={`${student.givenName} ${student.familyName}`}>
                      <TableCell
                        className={sortKey === 'name' ? 'sorted-col' : undefined}
                        sx={{
                          ...stickyColSx,
                          whiteSpace: 'nowrap',
                          maxWidth: 180,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          ...(sortKey === 'name' && { bgcolor: sortedColBg }),
                        }}
                      >
                        {student.givenName} {student.familyName}
                      </TableCell>
                    </Tooltip>
                    <TableCell align="center" className={sortKey === 'completion' ? 'sorted-col' : undefined} sx={{ color: 'text.secondary', ...(sortKey === 'completion' && { bgcolor: sortedColBg }) }}>
                      {student.finishedCount}
                    </TableCell>
                    {student.grades.map((g) => {
                      // Every cell links to that student's submission for that exercise — the WUI did
                      // this and it is the whole point of reading the table: you spot a number and
                      // want the work behind it. Unstarted cells link too, deliberately: a cell that
                      // is not clickable for reasons the reader has to infer is worse than one that
                      // opens an empty submission view.
                      const href =
                        `/courses/${courseId}/exercises/${g.courseExerciseId}` +
                        `?student=${encodeURIComponent(student.id)}`
                      const exerciseTitle = sortedExercises.find(
                        (ex: TeacherCourseExercise) => ex.course_exercise_id === g.courseExerciseId,
                      )?.effective_title
                      return (
                        <TableCell
                          key={g.courseExerciseId}
                          align="center"
                          className={sortKey === g.courseExerciseId ? 'sorted-col' : undefined}
                          sx={{
                            color: statusColor(g.status),
                            fontWeight: g.grade !== null ? 500 : undefined,
                            whiteSpace: 'nowrap',
                            ...(sortKey === g.courseExerciseId && { bgcolor: sortedColBg }),
                          }}
                        >
                          <Typography
                            component="a"
                            variant="inherit"
                            {...spaLinkProps(href, navigate)}
                            // Without this every link in the table is named "100" or "-", which is
                            // useless to anyone reading it through the accessibility tree — and that
                            // tree is what the browser tests query by.
                            aria-label={[
                              `${student.givenName} ${student.familyName}`,
                              exerciseTitle,
                              g.grade !== null ? String(g.grade) : t('grades.noGrade'),
                            ]
                              .filter(Boolean)
                              .join(' — ')}
                            sx={{
                              display: 'block',
                              color: 'inherit',
                              textDecoration: 'none',
                              '&:hover': { textDecoration: 'underline' },
                            }}
                          >
                            {g.grade !== null ? g.grade : '-'}
                            {showSubCount && g.submissionNumber !== null && (
                              <Typography
                                component="span"
                                variant="caption"
                                sx={{ ml: 0.5, color: 'text.secondary' }}
                              >
                                {'· #' + g.submissionNumber}
                              </Typography>
                            )}
                            {g.isAutograde === false && (
                              <FaceOutlined
                                sx={{
                                  fontSize: 14,
                                  color: 'text.secondary',
                                  ml: 0.75,
                                  verticalAlign: 'middle',
                                }}
                              />
                            )}
                          </Typography>
                        </TableCell>
                      )
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </>
      )}
    </>
  )
}
