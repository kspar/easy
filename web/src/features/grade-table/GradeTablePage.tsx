import { useCallback, useMemo, useState } from 'react'
import {
  Typography,
  CircularProgress,
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
import type { Theme } from '@mui/material'
import {
  ArrowBackOutlined,
  ArrowDropDownOutlined,
  CheckOutlined,
  FileDownloadOutlined,
  FaceOutlined,
} from '@mui/icons-material'
import { useParams, useNavigate, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useTeacherCourseExercises, useCourseGroups } from '../../api/exercises.ts'
import { spaLinkProps } from '../../components/spaLink.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import useFrameHeight from '../../hooks/useFrameHeight.ts'
import type { StudentExerciseStatus, TeacherCourseExercise } from '../../api/types.ts'
import {
  buildRows,
  compareStudents,
  csvFilename,
  toCsv,
  type SortDir,
} from './gradeTable.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

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

/**
 * Wraps an exercise column's link and its sort arrow, the arrow *under* the title.
 *
 * Stacked, because the header is what sets the column's width and the number below it needs very
 * little (EZ-1918). Side by side on one unbroken line a column was ~137px wide to hold "100", and
 * the title was cut at 100px regardless — a 2560 monitor showed 7 of a course's 24 exercises, every
 * one of them ending in an ellipsis. With the title wrapped onto two lines and the arrow out of the
 * row, a column is `EXERCISE_TITLE_WIDTH` plus padding, and the title gets twice the characters.
 *
 * The hover rule lives here rather than on the sort control because the control has no text of its
 * own any more: `sortLabelInactiveSx` keeps an inactive arrow at `opacity: 0` until hover, and with
 * nothing inside it there would be nothing to hover over. Hovering anywhere in the header reveals
 * it, which is what it did when the title was a child.
 */
const sortHeaderSx = {
  display: 'inline-flex',
  flexDirection: 'column',
  alignItems: 'center',
  '&:hover .MuiTableSortLabel-icon': { opacity: 0.5 },
  // The centring offsets in `sortLabelSx` are for an arrow beside text. Under it there is nothing
  // to balance against, and the icon's own side margins would only widen the column.
  '& .MuiTableSortLabel-root': { ml: 0, mr: 0 },
  '& .MuiTableSortLabel-icon': { mx: 0 },
} as const

const EXERCISE_TITLE_WIDTH = 72

/**
 * The sorted column's tint and the hovered row's, as a layer *over* the cell's background rather
 * than as the background itself.
 *
 * Both colours are translucent. As a `bgcolor` they replaced the opaque background of the sticky
 * cells, which nobody could see while nothing scrolled underneath them — and the moment the table
 * scrolled inside itself (EZ-1919) the rows showed through the header, a student's name printed
 * across "NAME". A gradient of one colour is a tint that leaves whatever is beneath it in place.
 */
const tint = (key: 'hover' | 'selected') => (theme: Theme) =>
  `linear-gradient(${theme.palette.action[key]}, ${theme.palette.action[key]})`
const sortedColTint = tint('hover')
const sortedColHoverTint = tint('selected')

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
  const { frameRef, frameHeight } = useFrameHeight(true)

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
          component={RouterLink}
          to={`/courses/${courseId}/exercises`}
          size="small"
          // Icon-only, so without this a screen reader announces it as "link" and nothing else.
          // It is the only way back from this page.
          aria-label={t('general.back')}
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('grades.title')}</Typography>
      </Box>

      {isLoading && <CircularProgress />}
      {error && (
        <ErrorAlert error={error} />
      )}

      {!isLoading && !error && sortedExercises.length === 0 && (
        <Typography color="text.secondary">
          {t('grades.emptyPlaceholder')}
        </Typography>
      )}

      {sortedExercises.length > 0 && (
        // As wide as the table needs and no wider. The table fills its container, so in a wide
        // window a course with three exercises had three columns 400px apart and a row was a
        // number, a gap, a number. Shrunk to its content the columns sit together, and the toolbar
        // — inside the same box — keeps its export button over the table instead of a monitor away.
        <Box sx={{ width: 'fit-content', minWidth: 'min(100%, 640px)', maxWidth: '100%' }}>
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
              icon={showSubCount ? <CheckOutlined /> : undefined}
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

          {/*
          Grade table — limited to the window's remaining height, so it scrolls inside itself.

          `stickyHeader` pins the header to the nearest scrolling ancestor, which is this container.
          Without a height it only ever scrolled sideways, the window did the vertical scrolling,
          and the header left with the page: 35 students down, a grid of numbers with no exercise
          names over it (EZ-1919). A limit rather than a height, so a short roster is not stretched
          to the bottom of the window; the floor keeps a landscape phone from getting a slit.
          */}
          <TableContainer
            component={Paper}
            variant="outlined"
            ref={frameRef}
            sx={{ maxHeight: frameHeight ? `max(320px, ${frameHeight})` : 'none' }}
          >
            <Table size="small" stickyHeader sx={{
              '& .MuiTableCell-sizeSmall': { px: 0.75 },
              '& .MuiTableCell-sizeSmall:last-child': { pr: 2 },
              '& .MuiTableBody-root .MuiTableRow-root:hover .MuiTableCell-root': { backgroundImage: sortedColTint },
              '& .MuiTableBody-root .MuiTableRow-root:hover .sorted-col': { backgroundImage: sortedColHoverTint },
            }}>
              <TableHead>
                <TableRow>
                  {/* Name column — sortable */}
                  <TableCell sx={{ ...headerStickyColSx, ...(sortKey === 'name' && { backgroundImage: sortedColTint }) }}>
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
                  <TableCell align="center" sx={{ whiteSpace: 'nowrap', ...(sortKey === 'completion' && { backgroundImage: sortedColTint }) }}>
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
                      <TableCell key={ex.course_exercise_id} align="center" sx={{ verticalAlign: 'bottom', ...(isActive && { backgroundImage: sortedColTint }) }}>
                        {/*
                        The link sits *beside* the sort control, not inside it.

                        These were always two separate targets — clicking the words navigates,
                        clicking the arrow sorts, and the spec says so in as many words — but the
                        link used to be a child of the `TableSortLabel`, which renders a button. A
                        focusable element inside a button is `nested-interactive`: focus order, what
                        a screen reader announces and what activation does are all
                        browser-dependent. Unnesting changes no behaviour and makes both reachable
                        by keyboard as themselves.

                        The sort control then has no text of its own, so it needs an explicit name.
                        */}
                        <Box sx={sortHeaderSx}>
                          <Typography
                            variant="caption"
                            component="a"
                            // The spread already supplies `onClick`; a second one here overrode it
                            // with an identical hand-rolled copy, so this site only looked like it
                            // followed spaLinkProps. Editing the helper would not have reached it.
                            {...spaLinkProps(`/courses/${courseId}/exercises/${ex.course_exercise_id}`, navigate)}
                            sx={{
                              // Two lines, then an ellipsis; the whole title stays in `title`.
                              display: '-webkit-box',
                              WebkitBoxOrient: 'vertical',
                              WebkitLineClamp: 2,
                              width: EXERCISE_TITLE_WIDTH,
                              overflow: 'hidden',
                              // Estonian compounds ("kahemõõtmelised") are wider than the column
                              // on their own, and a word that cannot break is clipped mid-letter.
                              overflowWrap: 'anywhere',
                              hyphens: 'auto',
                              lineHeight: 1.25,
                              // The theme sets table headers in tracked capitals, which suits a
                              // label like "NAME". A title is the teacher's own text: capitals
                              // cost it a quarter of the column and "Tsükkel while" its meaning.
                              textTransform: 'none',
                              letterSpacing: 0,
                              color: ex.student_visible ? 'text.primary' : 'text.disabled',
                              textDecoration: 'none',
                              '&:hover': { textDecoration: 'underline' },
                            }}
                            title={ex.effective_title}
                          >
                            {ex.effective_title}
                          </Typography>
                          <TableSortLabel
                            active={isActive}
                            direction={isActive ? sortDir : 'desc'}
                            onClick={() => handleSort(ex.course_exercise_id)}
                            aria-label={t('general.sortByColumn', { title: ex.effective_title })}
                            sx={!isActive ? sortLabelInactiveSx : sortLabelSx}
                          />
                        </Box>
                      </TableCell>
                    )
                  })}
                </TableRow>
              </TableHead>
              <TableBody>
                {/* Summary row */}
                <TableRow>
                  <TableCell className={sortKey === 'name' ? 'sorted-col' : undefined} sx={{ ...stickyColSx, whiteSpace: 'nowrap', color: 'text.secondary', ...(sortKey === 'name' && { backgroundImage: sortedColTint }) }}>
                    {'Σ (' + students.length + ')'}
                  </TableCell>
                  <TableCell className={sortKey === 'completion' ? 'sorted-col' : undefined} sx={sortKey === 'completion' ? { backgroundImage: sortedColTint } : undefined} />
                  {exerciseFinishedCounts.map((count, i) => (
                    <TableCell key={i} align="center" className={sortKey === sortedExercises[i]?.course_exercise_id ? 'sorted-col' : undefined} sx={{ color: 'text.secondary', ...(sortKey === sortedExercises[i]?.course_exercise_id && { backgroundImage: sortedColTint }) }}>
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
                          ...(sortKey === 'name' && { backgroundImage: sortedColTint }),
                        }}
                      >
                        {student.givenName} {student.familyName}
                      </TableCell>
                    </Tooltip>
                    <TableCell align="center" className={sortKey === 'completion' ? 'sorted-col' : undefined} sx={{ color: 'text.secondary', ...(sortKey === 'completion' && { backgroundImage: sortedColTint }) }}>
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
                            ...(sortKey === g.courseExerciseId && { backgroundImage: sortedColTint }),
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
        </Box>
      )}
    </>
  )
}
