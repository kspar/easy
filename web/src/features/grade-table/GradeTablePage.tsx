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
import usePageTitle from '../../hooks/usePageTitle.ts'
import type {
  TeacherCourseExercise,
  SubmissionRow,
  StudentExerciseStatus,
} from '../../api/types.ts'

type SortDir = 'asc' | 'desc'

interface GradeCell {
  grade: number | null
  submissionNumber: number | null
  status: StudentExerciseStatus
  isAutograde: boolean | null
  courseExerciseId: string
}

interface StudentRow {
  id: string
  givenName: string
  familyName: string
  finishedCount: number
  grades: GradeCell[]
}

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

/** Navigate via react-router on normal click, but allow ctrl/cmd+click to open in new tab */
function spaLinkProps(href: string, navigate: (to: string) => void) {
  return {
    href,
    onClick: (e: MouseEvent) => {
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
      e.preventDefault()
      navigate(href)
    },
  } as const
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
  const [filterGroup, setFilterGroup] = useState('')
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
    if (!exercises) return { students: [], sortedExercises: [], exerciseFinishedCounts: [] }

    const sorted = [...exercises].sort(
      (a, b) => a.ordering_idx - b.ordering_idx,
    )

    const firstEx = sorted[0]
    if (!firstEx) return { students: [], sortedExercises: sorted, exerciseFinishedCounts: [] }

    const studentRows: StudentRow[] = firstEx.latest_submissions.map(
      (sub: SubmissionRow) => {
        const grades: GradeCell[] = sorted.map((ex) => {
          const row = ex.latest_submissions.find(
            (s) => s.student_id === sub.student_id,
          )!
          return {
            grade: row.submission?.grade?.grade ?? null,
            submissionNumber: row.submission?.submission_number ?? null,
            status: row.status,
            isAutograde: row.submission?.grade?.is_autograde ?? null,
            courseExerciseId: ex.course_exercise_id,
          }
        })
        return {
          id: sub.student_id,
          givenName: sub.given_name,
          familyName: sub.family_name,
          finishedCount: grades.filter((g) => g.status === 'COMPLETED').length,
          grades,
        }
      },
    )

    // Sort students
    const dir = sortDir === 'asc' ? 1 : -1
    const nameFallback = (a: StudentRow, b: StudentRow): number => {
      const lastCmp = a.familyName.localeCompare(b.familyName)
      if (lastCmp !== 0) return lastCmp
      return a.givenName.localeCompare(b.givenName)
    }

    const compare = (a: StudentRow, b: StudentRow): number => {
      if (sortKey === 'name') {
        return nameFallback(a, b) * dir
      }

      if (sortKey === 'completion') {
        const diff = a.finishedCount - b.finishedCount
        if (diff !== 0) return diff * dir
        return nameFallback(a, b)
      }

      // Sort by specific exercise grade
      const exIdx = sorted.findIndex((ex) => ex.course_exercise_id === sortKey)
      if (exIdx >= 0) {
        const gradeA = a.grades[exIdx]?.grade
        const gradeB = b.grades[exIdx]?.grade
        if (gradeA === null && gradeB === null) return nameFallback(a, b)
        // nulls first when asc, nulls last (below 0) when desc
        if (gradeA === null) return -1 * dir
        if (gradeB === null) return 1 * dir
        const diff = gradeA - gradeB
        if (diff !== 0) return diff * dir
        return nameFallback(a, b)
      }

      return 0
    }
    studentRows.sort(compare)

    const finishedCounts = sorted.map((ex) =>
      ex.latest_submissions.filter((s) => s.status === 'COMPLETED').length,
    )

    return {
      students: studentRows,
      sortedExercises: sorted,
      exerciseFinishedCounts: finishedCounts,
    }
  }, [exercises, sortKey, sortDir])

  // CSV export
  const handleExport = useCallback(() => {
    if (!sortedExercises.length || !students.length) return

    const sep = ';'
    const headers = [
      t('general.name'),
      ...sortedExercises.flatMap((ex) => {
        const cols = [ex.effective_title]
        if (showSubCount) cols.push(`${t('grades.showSubmissionCount')} - ${ex.effective_title}`)
        return cols
      }),
    ]

    const rows = students.map((student) => {
      const cells = [
        `${student.givenName} ${student.familyName}`,
        ...student.grades.flatMap((g) => {
          const cols = [g.grade !== null ? String(g.grade) : '']
          if (showSubCount) cols.push(g.submissionNumber !== null ? String(g.submissionNumber) : '')
          return cols
        }),
      ]
      return cells.map((c) => `"${c.replace(/"/g, '""')}"`).join(sep)
    })

    const csv = headers.map((h) => `"${h.replace(/"/g, '""')}"`).join(sep) + '\n' + rows.join('\n')

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `grades-${courseId}-${Date.now()}.csv`
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
                    : t('participants.allGroups')
                  }
                  deleteIcon={<ArrowDropDownOutlined />}
                  onDelete={(e) => setFilterGroupAnchor(e.currentTarget.closest('div'))}
                  onClick={(e) => setFilterGroupAnchor(e.currentTarget)}
                  size="small"
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
              size="small"
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
                    {student.grades.map((g) => (
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
                      </TableCell>
                    ))}
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
