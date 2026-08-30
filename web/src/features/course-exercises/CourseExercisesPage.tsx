import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Divider,
  List,
  ListItemButton,
  ListItemText,
  ListItemIcon,
  IconButton,
  Menu,
  MenuItem,
  Snackbar,
  Tooltip,
} from '@mui/material'
import {
  CheckCircle,
  RadioButtonUnchecked,
  HourglassEmptyOutlined,
  CircleOutlined,
  AddOutlined,
  ArrowBackOutlined,
  ArrowDownwardOutlined,
  ArrowDropDownOutlined,
  ArrowUpwardOutlined,
  DeleteOutlined,
  FilterAltOffOutlined,
  MoreVertOutlined,
  NoteAddOutlined,
  PlaylistAddOutlined,
  SettingsOutlined,
  SwapVertOutlined,
  VisibilityOffOutlined,
  VisibilityOutlined,
} from '@mui/icons-material'
import RobotPlaceholder from '../../components/RobotPlaceholder.tsx'
import ExerciseProgressBar from '../../components/ExerciseProgressBar.tsx'
import { RobotIcon, TeacherFaceIcon } from '../../components/icons.tsx'
import { useEffect, useMemo, useState, type MouseEvent } from 'react'
import { useParams, useNavigate, useLocation, Link as RouterLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { format, isPast, differenceInHours, type Locale } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import { useAuth } from '../../auth/useAuth.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import useSavedFilters, { oneOf } from '../../hooks/useSavedFilters.ts'
import {
  useCourseExercises,
  useTeacherCourseExercises,
  useCourseGroups,
  useUpdateCourseExercises,
  useRemoveExercisesFromCourse,
  useReorderCourseExercise,
} from '../../api/exercises.ts'
import { useUpdateLastAccess } from '../../api/courses.ts'
import ConfirmDialog from '../../components/ConfirmDialog.tsx'
import CourseExerciseSettingsDialog from './CourseExerciseSettingsDialog.tsx'
import ReorderExerciseDialog from './ReorderExerciseDialog.tsx'
import AddFromLibraryDialog from './AddFromLibraryDialog.tsx'
import NewCourseExerciseDialog from './NewCourseExerciseDialog.tsx'
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

  // Set by the join-by-link page when the student has just joined this course
  const location = useLocation()
  const navigate = useNavigate()
  const [welcome, setWelcome] = useState(
    !!(location.state as { joinedCourse?: boolean } | null)?.joinedCourse,
  )

  // Drop the history state, or the welcome would come back on every reload of this page
  useEffect(() => {
    if (welcome) {
      navigate(location.pathname, { replace: true })
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      {activeRole === 'student' ? <StudentExercises /> : <TeacherExercises />}
      <Snackbar
        open={welcome}
        autoHideDuration={4000}
        onClose={() => setWelcome(false)}
        message={t('join.welcome')}
      />
    </>
  )
}

function StudentExercises() {
  const { courseId } = useParams<{ courseId: string }>()
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
                href={`/courses/${courseId}/exercises/${ex.id}`}
              />
            ))}
        </List>
      )}
    </>
  )
}

const VIS_FILTERS = ['all', 'visible', 'hidden'] as const
const DEADLINE_FILTERS = ['all', 'upcoming', 'passed'] as const
type VisibilityFilter = (typeof VIS_FILTERS)[number]
type DeadlineFilter = (typeof DEADLINE_FILTERS)[number]

const FILTERS_KEY = 'teacherCourseExerciseFilters'

/** Three display states, derived the same way the WUI derived them. */
function visibilityOf(ex: TeacherCourseExercise): 'visible' | 'hidden' | 'scheduled' {
  if (ex.student_visible) return 'visible'
  return ex.student_visible_from ? 'scheduled' : 'hidden'
}

/**
 * Whether flipping this exercise to `studentVisible` would actually change
 * anything. Note that hiding a *scheduled* exercise does: it clears the opening
 * time, so the exercise never becomes visible on its own.
 */
function needsVisibilityChange(ex: TeacherCourseExercise, studentVisible: boolean): boolean {
  return studentVisible
    ? visibilityOf(ex) !== 'visible'
    : visibilityOf(ex) !== 'hidden'
}

function TeacherExercises() {
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  const [filterGroup, setFilterGroup] = useSavedGroup(courseId!)
  const {
    data: exercises,
    isLoading,
    error,
  } = useTeacherCourseExercises(courseId!, filterGroup || undefined)
  const { data: groups } = useCourseGroups(courseId!)

  // Filters — persisted per course, like the WUI's per-collection user conf.
  // The group lives in its own store because other pages share that selection.
  const [savedFilters, setFilters] = useSavedFilters(FILTERS_KEY, courseId!, {
    visibility: 'all' as string,
    deadline: 'all' as string,
    ungradedOnly: false as boolean,
  })
  const visFilter = oneOf<VisibilityFilter>(savedFilters.visibility, VIS_FILTERS, 'all')
  const deadlineFilter = oneOf<DeadlineFilter>(savedFilters.deadline, DEADLINE_FILTERS, 'all')
  const ungradedOnly = savedFilters.ungradedOnly

  const setVisFilter = (visibility: VisibilityFilter) => setFilters({ visibility })
  const setDeadlineFilter = (deadline: DeadlineFilter) => setFilters({ deadline })

  const [groupAnchor, setGroupAnchor] = useState<HTMLElement | null>(null)
  const [visAnchor, setVisAnchor] = useState<HTMLElement | null>(null)
  const [deadlineAnchor, setDeadlineAnchor] = useState<HTMLElement | null>(null)

  // Selection
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())

  // Dialogs / menus
  const [rowMenuAnchor, setRowMenuAnchor] = useState<HTMLElement | null>(null)
  const [rowMenuTarget, setRowMenuTarget] = useState<TeacherCourseExercise | null>(null)
  const [settingsTargetId, setSettingsTargetId] = useState<string | null>(null)
  const [reorderTarget, setReorderTarget] = useState<TeacherCourseExercise | null>(null)
  const [addAnchor, setAddAnchor] = useState<HTMLElement | null>(null)
  const [addFromLibraryOpen, setAddFromLibraryOpen] = useState(false)
  const [newExerciseOpen, setNewExerciseOpen] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState<TeacherCourseExercise[] | null>(null)
  const [snackMsg, setSnackMsg] = useState('')

  // Mutations
  const updateExercises = useUpdateCourseExercises(courseId!)
  const removeExercises = useRemoveExercisesFromCourse(courseId!)
  const reorder = useReorderCourseExercise(courseId!)

  const ordered = useMemo(
    () => [...(exercises ?? [])].sort((a, b) => a.ordering_idx - b.ordering_idx),
    [exercises],
  )

  const visible = useMemo(
    () =>
      ordered.filter((ex) => {
        if (visFilter === 'visible' && visibilityOf(ex) !== 'visible') return false
        if (visFilter === 'hidden' && visibilityOf(ex) === 'visible') return false
        if (deadlineFilter !== 'all') {
          if (!ex.soft_deadline) return false
          const isPassed = isPast(new Date(ex.soft_deadline))
          if (deadlineFilter === 'upcoming' && isPassed) return false
          if (deadlineFilter === 'passed' && !isPassed) return false
        }
        if (ungradedOnly && ex.ungraded_count === 0) return false
        return true
      }),
    [ordered, visFilter, deadlineFilter, ungradedOnly],
  )

  // Never keep a selection on a row that's been filtered out or removed
  const selected = useMemo(
    () => visible.filter((ex) => selectedIds.has(ex.course_exercise_id)),
    [visible, selectedIds],
  )

  const isFiltered = visFilter !== 'all' || deadlineFilter !== 'all' || ungradedOnly
  const isBusy = updateExercises.isPending || removeExercises.isPending

  // Only offer a mass visibility change that would do something to at least one
  // of the selected exercises
  const canReveal = selected.some((ex) => needsVisibilityChange(ex, true))
  const canHide = selected.some((ex) => needsVisibilityChange(ex, false))

  function toggle(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleAll() {
    if (selected.length === visible.length) setSelectedIds(new Set())
    else setSelectedIds(new Set(visible.map((ex) => ex.course_exercise_id)))
  }

  function setVisibility(targets: TeacherCourseExercise[], studentVisible: boolean) {
    const ids = targets
      .filter((ex) => needsVisibilityChange(ex, studentVisible))
      .map((ex) => ex.course_exercise_id)
    if (ids.length === 0) return
    updateExercises.mutate(
      { courseExerciseIds: ids, replace: { student_visible: studentVisible } },
      {
        onSuccess: () => {
          setSelectedIds(new Set())
          setSnackMsg(studentVisible ? t('exercises.revealed') : t('exercises.wasHidden'))
        },
      },
    )
  }

  function handleRemoveConfirmed() {
    if (!confirmRemove) return
    removeExercises.mutate(
      confirmRemove.map((ex) => ex.course_exercise_id),
      {
        onSuccess: () => {
          setConfirmRemove(null)
          setSelectedIds(new Set())
          setSnackMsg(t('general.removed'))
        },
      },
    )
  }

  /**
   * Step one place up or down. Positions are taken from the *visible* list, not
   * the full course, so under an active filter the exercise moves past the
   * neighbour the teacher can actually see rather than appearing not to move at
   * all. Landing on the neighbour's ordering_idx puts it directly before (up) or
   * after (down) that neighbour either way.
   */
  function moveByOne(ex: TeacherCourseExercise, direction: -1 | 1) {
    const neighbour = visible[indexInVisible(ex) + direction]
    if (!neighbour) return
    reorder.mutate(
      { courseExerciseId: ex.course_exercise_id, newIndex: neighbour.ordering_idx },
      { onSuccess: () => setSnackMsg(t('general.moved')) },
    )
  }

  function indexInVisible(ex: TeacherCourseExercise) {
    return visible.findIndex((e) => e.course_exercise_id === ex.course_exercise_id)
  }

  function openRowMenu(e: MouseEvent<HTMLElement>, ex: TeacherCourseExercise) {
    e.stopPropagation()
    e.preventDefault()
    setRowMenuAnchor(e.currentTarget)
    setRowMenuTarget(ex)
  }

  function closeRowMenu() {
    setRowMenuAnchor(null)
    setRowMenuTarget(null)
  }

  // Submissions that removal would destroy, for the confirmation wording
  const removeSubmissionCount = (confirmRemove ?? []).reduce(
    (sum, ex) => sum + ex.completed_count + ex.started_count + ex.ungraded_count,
    0,
  )

  return (
    <>
      <Header
        actions={
          <Button
            size="small"
            variant="outlined"
            startIcon={<AddOutlined />}
            onClick={(e) => setAddAnchor(e.currentTarget)}
            // The label collapses on narrow screens, leaving a square outlined
            // icon button. aria-label carries the name in both states, since
            // there's no text content left to derive it from on xs.
            aria-label={t('exercises.addExercise')}
            sx={{
              height: 32,
              flexShrink: 0,
              minWidth: 0,
              px: { xs: 1, sm: 2 },
              '& .MuiButton-startIcon': {
                mr: { xs: 0, sm: 1 },
                ml: { xs: 0, sm: -0.5 },
              },
            }}
          >
            <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>
              {t('exercises.addExercise')}
            </Box>
          </Button>
        }
      />

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {exercises && exercises.length === 0 && (
        <RobotPlaceholder message={t('exercises.noExercises')} />
      )}

      {exercises && exercises.length > 0 && (
        <>
          {selected.length > 0 ? (
            /* Mass action bar */
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                mb: 2,
                px: 1.5,
                bgcolor: 'action.selected',
                borderRadius: 1,
                minHeight: 36,
                flexWrap: 'wrap',
              }}
            >
              <Checkbox
                size="small"
                checked={selected.length === visible.length}
                indeterminate={selected.length < visible.length}
                onChange={toggleAll}
                sx={{ ml: -0.5 }}
              />
              <Typography variant="body2" sx={{ mr: 1 }}>
                {t('library.selected', { count: selected.length })}
              </Typography>
              {/* Disabled rather than hidden, so the buttons don't shift around
                  as the selection changes. */}
              <MassVisibilityButton
                label={t('exercises.doReveal')}
                icon={<VisibilityOutlined />}
                enabled={canReveal}
                disabledReason={t('exercises.allAlreadyVisible')}
                isBusy={isBusy}
                onClick={() => setVisibility(selected, true)}
              />
              <MassVisibilityButton
                label={t('exercises.doHide')}
                icon={<VisibilityOffOutlined />}
                enabled={canHide}
                disabledReason={t('exercises.allAlreadyHidden')}
                isBusy={isBusy}
                onClick={() => setVisibility(selected, false)}
              />
              <Button
                size="small"
                color="error"
                startIcon={<DeleteOutlined />}
                disabled={isBusy}
                onClick={() => setConfirmRemove(selected)}
              >
                {t('exercises.removeFromCourse')}
              </Button>
            </Box>
          ) : (
            <FilterBar>
              {/* Group filter */}
              {groups && groups.length > 0 && (
                <>
                  <Chip
                    label={
                      filterGroup
                        ? groups.find((g) => g.id === filterGroup)?.name
                        : t('participants.groups')
                    }
                    deleteIcon={<ArrowDropDownOutlined />}
                    onDelete={(e) => setGroupAnchor(e.currentTarget.closest('div'))}
                    onClick={(e) => setGroupAnchor(e.currentTarget)}
                    variant={filterGroup ? 'filled' : 'outlined'}
                    color={filterGroup ? 'primary' : 'default'}
                  />
                  <Menu
                    anchorEl={groupAnchor}
                    open={!!groupAnchor}
                    onClose={() => setGroupAnchor(null)}
                  >
                    <MenuItem
                      selected={!filterGroup}
                      onClick={() => {
                        setFilterGroup('')
                        setGroupAnchor(null)
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
                          setGroupAnchor(null)
                        }}
                      >
                        {g.name}
                      </MenuItem>
                    ))}
                  </Menu>
                </>
              )}

              {/* Visibility filter */}
              <Chip
                label={
                  visFilter === 'all'
                    ? t('exercises.visibility')
                    : visFilter === 'visible'
                      ? t('exercises.visible')
                      : t('exercises.hidden')
                }
                deleteIcon={<ArrowDropDownOutlined />}
                onDelete={(e) => setVisAnchor(e.currentTarget.closest('div'))}
                onClick={(e) => setVisAnchor(e.currentTarget)}
                variant={visFilter !== 'all' ? 'filled' : 'outlined'}
                color={visFilter !== 'all' ? 'primary' : 'default'}
              />
              <Menu anchorEl={visAnchor} open={!!visAnchor} onClose={() => setVisAnchor(null)}>
                {(['all', 'visible', 'hidden'] as VisibilityFilter[]).map((f) => (
                  <MenuItem
                    key={f}
                    selected={visFilter === f}
                    onClick={() => {
                      setVisFilter(f)
                      setVisAnchor(null)
                    }}
                  >
                    {f === 'all'
                      ? t('library.filterAll')
                      : f === 'visible'
                        ? t('exercises.visible')
                        : t('exercises.hidden')}
                  </MenuItem>
                ))}
              </Menu>

              {/* Deadline filter */}
              <Chip
                label={
                  deadlineFilter === 'all'
                    ? t('exercises.deadline')
                    : deadlineFilter === 'upcoming'
                      ? t('exercises.deadlineUpcoming')
                      : t('exercises.deadlinePassed')
                }
                deleteIcon={<ArrowDropDownOutlined />}
                onDelete={(e) => setDeadlineAnchor(e.currentTarget.closest('div'))}
                onClick={(e) => setDeadlineAnchor(e.currentTarget)}
                variant={deadlineFilter !== 'all' ? 'filled' : 'outlined'}
                color={deadlineFilter !== 'all' ? 'primary' : 'default'}
              />
              <Menu
                anchorEl={deadlineAnchor}
                open={!!deadlineAnchor}
                onClose={() => setDeadlineAnchor(null)}
              >
                {(['all', 'upcoming', 'passed'] as DeadlineFilter[]).map((f) => (
                  <MenuItem
                    key={f}
                    selected={deadlineFilter === f}
                    onClick={() => {
                      setDeadlineFilter(f)
                      setDeadlineAnchor(null)
                    }}
                  >
                    {f === 'all'
                      ? t('library.filterAll')
                      : f === 'upcoming'
                        ? t('exercises.deadlineUpcoming')
                        : t('exercises.deadlinePassed')}
                  </MenuItem>
                ))}
              </Menu>

              {/* Ungraded toggle */}
              <Chip
                label={t('exercises.ungradedSubmissions')}
                onClick={() => setFilters({ ungradedOnly: !ungradedOnly })}
                variant={ungradedOnly ? 'filled' : 'outlined'}
                color={ungradedOnly ? 'primary' : 'default'}
              />
            </FilterBar>
          )}

          {visible.length === 0 ? (
            <Box sx={{ py: 4, textAlign: 'center' }}>
              <Typography color="text.secondary">
                {isFiltered ? t('library.noResults') : t('exercises.noExercises')}
              </Typography>
              {/* Filters persist across visits, so an empty list on arrival needs
                  an obvious way out — otherwise the course looks empty. */}
              {isFiltered && (
                <Button
                  size="small"
                  startIcon={<FilterAltOffOutlined />}
                  onClick={() =>
                    setFilters({ visibility: 'all', deadline: 'all', ungradedOnly: false })
                  }
                  sx={{ mt: 1.5 }}
                >
                  {t('exercises.clearFilters')}
                </Button>
              )}
            </Box>
          ) : (
            <List disablePadding>
              {visible.map((ex) => (
                <TeacherExerciseRow
                  key={ex.course_exercise_id}
                  exercise={ex}
                  href={`/courses/${courseId}/exercises/${ex.course_exercise_id}`}
                  dateFnsLocale={dateFnsLocale}
                  isSelected={selectedIds.has(ex.course_exercise_id)}
                  hasSelection={selected.length > 0}
                  onToggle={() => toggle(ex.course_exercise_id)}
                  onOpenMenu={(e) => openRowMenu(e, ex)}
                  navigate={navigate}
                />
              ))}
            </List>
          )}

          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: 'block', textAlign: 'center', mt: 1.5 }}
          >
            {t('library.exerciseCount', { count: visible.length })}
          </Typography>
        </>
      )}

      {/* Add-exercise menu, from the "+" in the header */}
      <Menu
        anchorEl={addAnchor}
        open={!!addAnchor}
        onClose={() => setAddAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        <MenuItem
          onClick={() => {
            setAddFromLibraryOpen(true)
            setAddAnchor(null)
          }}
        >
          <ListItemIcon>
            <PlaylistAddOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('exercises.addFromLibrary')}</ListItemText>
        </MenuItem>
        <MenuItem
          onClick={() => {
            setNewExerciseOpen(true)
            setAddAnchor(null)
          }}
        >
          <ListItemIcon>
            <NoteAddOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('exercises.newExercise')}</ListItemText>
        </MenuItem>
      </Menu>

      {/* Row action menu */}
      <Menu anchorEl={rowMenuAnchor} open={!!rowMenuAnchor} onClose={closeRowMenu}>
        {rowMenuTarget && [
          // A scheduled exercise gets both: reveal it now, or cancel the
          // schedule so it stays hidden. The other two states get just one.
          needsVisibilityChange(rowMenuTarget, true) && (
            <MenuItem
              key="reveal"
              onClick={() => {
                setVisibility([rowMenuTarget], true)
                closeRowMenu()
              }}
            >
              <ListItemIcon>
                <VisibilityOutlined fontSize="small" />
              </ListItemIcon>
              <ListItemText>{t('exercises.doReveal')}</ListItemText>
            </MenuItem>
          ),
          needsVisibilityChange(rowMenuTarget, false) && (
            <MenuItem
              key="hide"
              onClick={() => {
                setVisibility([rowMenuTarget], false)
                closeRowMenu()
              }}
            >
              <ListItemIcon>
                <VisibilityOffOutlined fontSize="small" />
              </ListItemIcon>
              <ListItemText>{t('exercises.doHide')}</ListItemText>
            </MenuItem>
          ),
          <Divider key="move-divider" />,
          <MenuItem
            key="move-up"
            disabled={indexInVisible(rowMenuTarget) === 0}
            onClick={() => {
              moveByOne(rowMenuTarget, -1)
              closeRowMenu()
            }}
          >
            <ListItemIcon>
              <ArrowUpwardOutlined fontSize="small" />
            </ListItemIcon>
            <ListItemText>{t('general.moveUp')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="move-down"
            disabled={indexInVisible(rowMenuTarget) === visible.length - 1}
            onClick={() => {
              moveByOne(rowMenuTarget, 1)
              closeRowMenu()
            }}
          >
            <ListItemIcon>
              <ArrowDownwardOutlined fontSize="small" />
            </ListItemIcon>
            <ListItemText>{t('general.moveDown')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="move"
            disabled={ordered.length < 2}
            onClick={() => {
              setReorderTarget(rowMenuTarget)
              closeRowMenu()
            }}
          >
            <ListItemIcon>
              <SwapVertOutlined fontSize="small" />
            </ListItemIcon>
            <ListItemText>{t('exercises.moveTo')}</ListItemText>
          </MenuItem>,
          <Divider key="settings-divider" />,
          <MenuItem
            key="settings"
            onClick={() => {
              setSettingsTargetId(rowMenuTarget.course_exercise_id)
              closeRowMenu()
            }}
          >
            <ListItemIcon>
              <SettingsOutlined fontSize="small" />
            </ListItemIcon>
            <ListItemText>{t('exercises.exerciseSettings')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="remove"
            onClick={() => {
              setConfirmRemove([rowMenuTarget])
              closeRowMenu()
            }}
          >
            <ListItemIcon>
              <DeleteOutlined fontSize="small" />
            </ListItemIcon>
            <ListItemText>{t('exercises.removeFromCourse')}</ListItemText>
          </MenuItem>,
        ]}
      </Menu>

      {/* Dialogs */}
      {settingsTargetId && (
        <CourseExerciseSettingsDialog
          courseId={courseId!}
          courseExerciseId={settingsTargetId}
          onClose={() => setSettingsTargetId(null)}
        />
      )}

      {reorderTarget && (
        <ReorderExerciseDialog
          courseId={courseId!}
          exercise={reorderTarget}
          allExercises={ordered}
          onClose={() => setReorderTarget(null)}
          onSuccess={setSnackMsg}
        />
      )}

      <AddFromLibraryDialog
        courseId={courseId!}
        open={addFromLibraryOpen}
        onClose={() => setAddFromLibraryOpen(false)}
        onSuccess={setSnackMsg}
      />

      <NewCourseExerciseDialog
        courseId={courseId!}
        open={newExerciseOpen}
        onClose={() => setNewExerciseOpen(false)}
      />

      <ConfirmDialog
        open={!!confirmRemove}
        message={
          <>
            {confirmRemove?.length === 1
              ? t('exercises.removeFromCourseConfirm', { name: confirmRemove[0].effective_title })
              : t('exercises.removeManyFromCourseConfirm', { count: confirmRemove?.length ?? 0 })}
            {removeSubmissionCount > 0 && (
              <Typography component="span" color="error" sx={{ display: 'block', mt: 1 }}>
                {t('exercises.submissionsWillBeDeleted', { count: removeSubmissionCount })}
              </Typography>
            )}
          </>
        }
        confirmLabel={t('general.remove')}
        confirmColor="error"
        isPending={removeExercises.isPending}
        onClose={() => setConfirmRemove(null)}
        onConfirm={handleRemoveConfirmed}
      />

      <Snackbar
        open={!!snackMsg}
        autoHideDuration={3000}
        onClose={() => setSnackMsg('')}
        message={snackMsg}
      />
    </>
  )
}

/**
 * A mass reveal/hide button that stays in place when it doesn't apply, and says
 * why. The span is required because a disabled MUI button receives no pointer
 * events, so a Tooltip wrapped straight around it would never open.
 */
function MassVisibilityButton({
  label,
  icon,
  enabled,
  disabledReason,
  isBusy,
  onClick,
}: {
  label: string
  icon: React.ReactElement
  enabled: boolean
  disabledReason: string
  isBusy: boolean
  onClick: () => void
}) {
  return (
    <Tooltip title={enabled ? '' : disabledReason} arrow>
      <span>
        <Button size="small" startIcon={icon} disabled={isBusy || !enabled} onClick={onClick}>
          {label}
        </Button>
      </span>
    </Tooltip>
  )
}

/**
 * The filter row. Adding exercises is a course-setup task rather than part of the
 * day-to-day grading loop, so those actions live behind the "+" in the page
 * header and this row stays about filtering only.
 */
function FilterBar({ children }: { children?: React.ReactNode }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 0.75,
        mb: 2,
        flexWrap: 'wrap',
        rowGap: 1,
      }}
    >
      {children}
    </Box>
  )
}

function Header({ actions }: { actions?: React.ReactNode }) {
  const { t } = useTranslation()

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
      <IconButton component={RouterLink} to="/courses" size="small">
        <ArrowBackOutlined />
      </IconButton>
      <Typography variant="h5">{t('exercises.title')}</Typography>
      {actions && (
        <>
          <Box sx={{ flexGrow: 1 }} />
          {actions}
        </>
      )}
    </Box>
  )
}

function StudentExerciseRow({
  exercise,
  dateFnsLocale,
  href,
}: {
  exercise: CourseExercise
  dateFnsLocale: Locale
  /**
   * Where the row goes, rather than an `onClick` that goes there.
   *
   * A student's exercise list is the most-clicked list in the app and had no `href` at all, so
   * "open the next exercise in a second tab" was impossible. See `components/spaLink.ts`.
   */
  href: string
}) {
  const { t } = useTranslation()
  const deadline = exercise.deadline ? new Date(exercise.deadline) : null
  const isPastDeadline = deadline ? isPast(deadline) : false
  const isApproaching = deadline ? !isPastDeadline && differenceInHours(deadline, new Date()) < 24 : false

  return (
    <ListItemButton
      component={RouterLink}
      to={href}
      sx={{ borderRadius: 1, mb: 0.5 }}
    >
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
  href,
  dateFnsLocale,
  isSelected,
  hasSelection,
  onToggle,
  onOpenMenu,
  navigate,
}: {
  exercise: TeacherCourseExercise
  href: string
  dateFnsLocale: Locale
  isSelected: boolean
  hasSelection: boolean
  onToggle: () => void
  onOpenMenu: (e: MouseEvent<HTMLElement>) => void
  navigate: (to: string) => void
}) {
  const { t } = useTranslation()
  const deadline = exercise.soft_deadline ? new Date(exercise.soft_deadline) : null
  const vis = visibilityOf(exercise)
  const visibleFrom = exercise.student_visible_from
    ? new Date(exercise.student_visible_from)
    : null

  return (
    <ListItemButton
      component="a"
      href={href}
      selected={isSelected}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
        e.preventDefault()
        navigate(href)
      }}
      sx={{
        borderRadius: 1,
        mb: 0.5,
        textDecoration: 'none',
        color: 'inherit',
      }}
    >
      <ListItemIcon sx={{ minWidth: 40, alignSelf: 'center' }}>
        {/* Fixed-size box so the slot keeps its height while its contents are
            absolutely positioned — otherwise the row's icon column collapses
            and drifts out of line with the title. */}
        <Box
          sx={{
            position: 'relative',
            width: 24,
            height: 24,
            // The grader icon becomes a checkbox only while the cursor is on the
            // icon itself, not anywhere on the row — selecting exercises is rare,
            // so checkboxes shouldn't flicker in and out during ordinary reading.
            // ::before widens the hover zone to match the checkbox's 40px target
            // without affecting layout.
            ...(!hasSelection && {
              '&::before': { content: '""', position: 'absolute', inset: -8 },
              '& .ex-icon': { display: 'flex' },
              '& .ex-check': { display: 'none' },
              '&:hover .ex-icon': { display: 'none' },
              '&:hover .ex-check': { display: 'flex' },
            }),
          }}
        >
          {!hasSelection && (
            <Box
              className="ex-icon"
              sx={{ display: 'flex', position: 'absolute', inset: 0 }}
            >
              <Tooltip
                title={
                  exercise.grader_type === 'AUTO'
                    ? t('exercises.gradedAutomatically')
                    : t('exercises.gradedByTeacher')
                }
                arrow
              >
                {exercise.grader_type === 'AUTO' ? (
                  <RobotIcon sx={{ color: 'text.secondary' }} />
                ) : (
                  <TeacherFaceIcon sx={{ color: 'text.secondary' }} />
                )}
              </Tooltip>
            </Box>
          )}
          <Box
            className="ex-check"
            sx={{
              display: hasSelection ? 'flex' : 'none',
              position: 'absolute',
              inset: 0,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Checkbox
              size="small"
              checked={isSelected}
              // The row is an <a>, so the click has to be cancelled to stop
              // navigation — which also cancels the checkbox's own toggle, hence
              // driving the selection from here rather than from onChange.
              onClick={(e) => {
                e.stopPropagation()
                e.preventDefault()
                onToggle()
              }}
              // 8px padding pulled back out with a matching negative margin: a
              // 40px hit target that still lays out as 24px, so it lines up with
              // the hover zone above.
              sx={{ p: 1, m: -1 }}
            />
          </Box>
        </Box>
      </ListItemIcon>

      <ListItemText
        primary={
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography
              variant="body1"
              color={vis === 'visible' ? 'text.primary' : 'text.secondary'}
            >
              {exercise.effective_title}
            </Typography>
            {vis === 'hidden' && (
              <Tooltip title={t('exercises.hiddenHelp')} arrow>
                <VisibilityOffOutlined sx={{ fontSize: 16, color: 'text.disabled' }} />
              </Tooltip>
            )}
            {vis === 'scheduled' && visibleFrom && (
              <Tooltip
                title={`${t('exercises.visibleFrom')}: ${format(visibleFrom, 'PPp', { locale: dateFnsLocale })}`}
                arrow
              >
                <Chip
                  size="small"
                  variant="outlined"
                  // An eye, not a clock: a clock face means "deadline" everywhere
                  // else in the product. This pairs with the crossed-out eye used
                  // for plain-hidden above — eye + a date reads "visible from".
                  icon={<VisibilityOutlined />}
                  // Same month-name style as the deadline line below, just shorter
                  label={format(visibleFrom, 'd MMM, HH:mm', { locale: dateFnsLocale })}
                  sx={{ height: 20, '& .MuiChip-label': { px: 0.75, fontSize: 11 } }}
                />
              </Tooltip>
            )}
          </Box>
        }
        secondary={
          deadline
            ? `${t('exercises.deadline')}: ${format(deadline, 'PPp', { locale: dateFnsLocale })}`
            : undefined
        }
      />

      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexShrink: 0 }}>
        {exercise.ungraded_count > 0 && (
          <Chip
            label={`${exercise.ungraded_count} ${t('exercises.ungraded')}`}
            color="info"
            size="small"
            sx={{ display: { xs: 'none', md: 'flex' } }}
          />
        )}
        <ExerciseProgressBar
          completed={exercise.completed_count}
          started={exercise.started_count}
          ungraded={exercise.ungraded_count}
          unstarted={exercise.unstarted_count}
        />
        <IconButton
          size="small"
          aria-label={t('general.moreOptions')}
          onClick={onOpenMenu}
        >
          <MoreVertOutlined fontSize="small" />
        </IconButton>
      </Box>
    </ListItemButton>
  )
}