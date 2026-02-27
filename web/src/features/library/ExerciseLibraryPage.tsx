import { useState, useMemo, useEffect, type MouseEvent } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Box,
  Typography,
  Breadcrumbs,
  Button,
  Menu,
  MenuItem,
  ListItemIcon,
  ListItemText,
  Table,
  TableBody,
  TableRow,
  TableCell,
  IconButton,
  Checkbox,
  Chip,
  Snackbar,
  CircularProgress,
} from '@mui/material'
import {
  FolderOutlined,
  CreateNewFolderOutlined,
  NoteAddOutlined,
  SortOutlined,
  MoreVertOutlined,
  DriveFileRenameOutlineOutlined,
  DeleteOutlined,
  PlaylistAddOutlined,
  ArrowDropDownOutlined,
  PersonAddOutlined,
} from '@mui/icons-material'
import { useLibraryDir, useLibraryDirParents, useDeleteDir, useDeleteExercise } from '../../api/library.ts'
import type { LibraryDir, LibraryExercise, DirAccessLevel } from '../../api/types.ts'
import { RobotIcon, TeacherFaceIcon } from '../../components/icons.tsx'
import RelativeTime from '../../components/RelativeTime.tsx'
import usePageTitle from '../../hooks/usePageTitle.ts'
import CreateDirDialog from './CreateDirDialog.tsx'
import CreateExerciseDialog from './CreateExerciseDialog.tsx'
import RenameDirDialog from './RenameDirDialog.tsx'
import AddToCourseDialog from './AddToCourseDialog.tsx'
import ShareDialog from './ShareDialog.tsx'
import ConfirmDialog from '../participants/ConfirmDialog.tsx'

type SortMode = 'name' | 'modified' | 'popularity'
type VisibilityFilter = 'all' | 'shared' | 'private'
type GradingFilter = 'all' | 'auto' | 'teacher'

function slugify(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9\u00C0-\u024F-]/g, '')
}

function dirLink(id: string, name: string): string {
  return `/library/dir/${id}/${slugify(name)}`
}

function exerciseLink(id: string, title: string): string {
  return `/library/exercise/${id}/${slugify(title)}`
}

function spaLinkProps(href: string, navigate: (to: string) => void, stopPropagation = false) {
  return {
    href,
    onClick: (e: MouseEvent) => {
      if (stopPropagation) e.stopPropagation()
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
      e.preventDefault()
      navigate(href)
    },
  }
}

function hasAccess(level: DirAccessLevel, required: DirAccessLevel): boolean {
  const order: DirAccessLevel[] = ['P', 'PR', 'PRA', 'PRAW', 'PRAWM']
  return order.indexOf(level) >= order.indexOf(required)
}

function useSavedSort(): [SortMode, (s: SortMode) => void] {
  const [sort, setSort] = useState<SortMode>(() => {
    const saved = localStorage.getItem('library_sort')
    return (saved === 'name' || saved === 'modified' || saved === 'popularity') ? saved : 'name'
  })
  const update = (s: SortMode) => {
    setSort(s)
    localStorage.setItem('library_sort', s)
  }
  return [sort, update]
}

export default function ExerciseLibraryPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { dirId = 'root' } = useParams()

  const { data, isLoading, error } = useLibraryDir(dirId)
  const { data: parents } = useLibraryDirParents(dirId)

  const isRoot = dirId === 'root'
  const currentDir = data?.current_dir
  const canCreate = currentDir ? hasAccess(currentDir.effective_access, 'PRA') : isRoot
  const canManage = currentDir ? hasAccess(currentDir.effective_access, 'PRAWM') : false

  usePageTitle(currentDir?.name ?? t('library.title'))

  // Sort
  const [sortMode, setSortMode] = useSavedSort()
  const [sortAnchor, setSortAnchor] = useState<HTMLElement | null>(null)

  // Filters
  const [visFilter, setVisFilter] = useState<VisibilityFilter>('all')
  const [visAnchor, setVisAnchor] = useState<HTMLElement | null>(null)
  const [gradingFilter, setGradingFilter] = useState<GradingFilter>('all')
  const [gradingAnchor, setGradingAnchor] = useState<HTMLElement | null>(null)

  // Selection — clear when navigating to a different directory
  const [selectedExIds, setSelectedExIds] = useState<Set<string>>(new Set())
  useEffect(() => setSelectedExIds(new Set()), [dirId])

  // Dialogs
  const [createDirOpen, setCreateDirOpen] = useState(false)
  const [createExOpen, setCreateExOpen] = useState(false)
  const [renameDirTarget, setRenameDirTarget] = useState<LibraryDir | null>(null)
  const [addToCourseExercises, setAddToCourseExercises] = useState<{ id: string; title: string }[]>([])
  const [shareTarget, setShareTarget] = useState<{ dirId: string; parentDirId?: string; name: string; isDir: boolean } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<{
    type: 'dir' | 'exercise' | 'bulk-exercise'
    id: string
    name: string
  } | null>(null)

  // Row action menus
  const [rowMenuAnchor, setRowMenuAnchor] = useState<HTMLElement | null>(null)
  const [rowMenuTarget, setRowMenuTarget] = useState<{ type: 'dir'; dir: LibraryDir } | { type: 'exercise'; exercise: LibraryExercise } | null>(null)

  // Snackbar
  const [snackMsg, setSnackMsg] = useState('')
  const [snackAction, setSnackAction] = useState<string | undefined>()

  // Mutations
  const deleteDir = useDeleteDir()
  const deleteExercise = useDeleteExercise()

  // Filtered + sorted items
  const { sortedDirs, sortedExercises } = useMemo(() => {
    if (!data) return { sortedDirs: [], sortedExercises: [] }

    let dirs = data.child_dirs.filter((d) => {
      if (visFilter === 'shared' && !d.is_shared) return false
      if (visFilter === 'private' && d.is_shared) return false
      return true
    })

    let exercises = data.child_exercises.filter((ex) => {
      if (visFilter === 'shared' && !ex.is_shared) return false
      if (visFilter === 'private' && ex.is_shared) return false
      if (gradingFilter === 'auto' && ex.grader_type !== 'AUTO') return false
      if (gradingFilter === 'teacher' && ex.grader_type !== 'TEACHER') return false
      return true
    })

    const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

    // Sort dirs
    dirs = [...dirs].sort((a, b) => collator.compare(a.name, b.name))

    // Sort exercises
    exercises = [...exercises].sort((a, b) => {
      if (sortMode === 'modified') {
        return new Date(b.modified_at).getTime() - new Date(a.modified_at).getTime()
      }
      if (sortMode === 'popularity') {
        return b.courses_count - a.courses_count || collator.compare(a.title, b.title)
      }
      return collator.compare(a.title, b.title)
    })

    return { sortedDirs: dirs, sortedExercises: exercises }
  }, [data, visFilter, gradingFilter, sortMode])

  async function handleDeleteConfirm() {
    if (!confirmDelete) return
    if (confirmDelete.type === 'dir') {
      deleteDir.mutate(confirmDelete.id, {
        onSuccess: () => {
          setConfirmDelete(null)
          setSnackMsg(t('library.dirDeleted'))
        },
      })
    } else if (confirmDelete.type === 'bulk-exercise') {
      const ids = [...selectedExIds]
      await Promise.allSettled(
        ids.map((id) => deleteExercise.mutateAsync(id)),
      )
      setConfirmDelete(null)
      setSelectedExIds(new Set())
      setSnackMsg(t('library.exercisesDeleted', { count: ids.length }))
    } else {
      deleteExercise.mutate(confirmDelete.id, {
        onSuccess: () => {
          setConfirmDelete(null)
          setSnackMsg(t('library.exerciseDeleted'))
        },
      })
    }
  }

  function openRowMenu(e: MouseEvent<HTMLElement>, target: typeof rowMenuTarget) {
    e.stopPropagation()
    setRowMenuAnchor(e.currentTarget)
    setRowMenuTarget(target)
  }

  function closeRowMenu() {
    setRowMenuAnchor(null)
    setRowMenuTarget(null)
  }

  function toggleExercise(id: string) {
    setSelectedExIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleAll() {
    if (selectedExIds.size === sortedExercises.length) {
      setSelectedExIds(new Set())
    } else {
      setSelectedExIds(new Set(sortedExercises.map((ex) => ex.exercise_id)))
    }
  }

  if (isLoading) {
    return (
      <Box display="flex" justifyContent="center" py={8}>
        <CircularProgress />
      </Box>
    )
  }

  if (error) {
    return (
      <Typography color="error" sx={{ py: 4 }}>
        {t('general.somethingWentWrong')}
      </Typography>
    )
  }

  const isEmpty = sortedDirs.length === 0 && sortedExercises.length === 0
  const hasAnyItems = (data?.child_dirs.length ?? 0) + (data?.child_exercises.length ?? 0) > 0

  return (
    <>
      {/* Breadcrumbs */}
      <Breadcrumbs sx={{ mb: 2 }}>
        {isRoot ? (
          <Typography color="text.primary" fontWeight={500}>
            {t('library.title')}
          </Typography>
        ) : [
          <Typography
            key="root"
            component="a"
            {...spaLinkProps('/library/dir/root', navigate)}
            sx={{ textDecoration: 'none', color: 'text.secondary', '&:hover': { textDecoration: 'underline' } }}
          >
            {t('library.title')}
          </Typography>,
          ...(parents?.map((p) => (
            <Typography
              key={p.id}
              component="a"
              {...spaLinkProps(dirLink(p.id, p.name), navigate)}
              sx={{ textDecoration: 'none', color: 'text.secondary', '&:hover': { textDecoration: 'underline' } }}
            >
              {p.name}
            </Typography>
          )) ?? []),
          <Typography key="current" color="text.primary" fontWeight={500}>
            {currentDir?.name}
          </Typography>,
        ]}
      </Breadcrumbs>

      {/* Toolbar / Selection toolbar */}
      {selectedExIds.size > 0 ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            mb: 2,
            px: 1.5,
            bgcolor: 'action.selected',
            borderRadius: 1,
            height: 36,
          }}
        >
          <Checkbox
            size="small"
            checked={selectedExIds.size === sortedExercises.length && sortedExercises.length > 0}
            indeterminate={selectedExIds.size > 0 && selectedExIds.size < sortedExercises.length}
            onChange={toggleAll}
            sx={{ ml: -0.5 }}
          />
          <Typography variant="body2" sx={{ mr: 1 }}>
            {t('library.selected', { count: selectedExIds.size })}
          </Typography>
          <Button
            size="small"
            startIcon={<PlaylistAddOutlined />}
            onClick={() => setAddToCourseExercises(
              sortedExercises
                .filter((ex) => selectedExIds.has(ex.exercise_id))
                .map((ex) => ({ id: ex.exercise_id, title: ex.title }))
            )}
          >
            {t('library.addToCourse')}
          </Button>
          <Button
            size="small"
            color="error"
            startIcon={<DeleteOutlined />}
            onClick={() => setConfirmDelete({
              type: 'bulk-exercise',
              id: '',
              name: String(selectedExIds.size),
            })}
          >
            {t('general.delete')}
          </Button>
        </Box>
      ) : (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2, flexWrap: 'wrap', rowGap: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            {canCreate && (
              <>
                <Button
                  variant="contained"
                  size="small"
                  startIcon={<NoteAddOutlined sx={{ display: { xs: 'inherit', sm: 'inherit' } }} />}
                  onClick={() => setCreateExOpen(true)}
                  sx={{ minWidth: 0, px: { xs: 1, sm: 2 }, '& .MuiButton-startIcon': { mr: { xs: 0, sm: 1 }, ml: { xs: 0, sm: -0.5 } } }}
                >
                  <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>{t('library.newExercise')}</Box>
                </Button>
                <Button
                  variant="outlined"
                  size="small"
                  startIcon={<CreateNewFolderOutlined sx={{ display: { xs: 'inherit', sm: 'inherit' } }} />}
                  onClick={() => setCreateDirOpen(true)}
                  sx={{ minWidth: 0, px: { xs: 1, sm: 2 }, '& .MuiButton-startIcon': { mr: { xs: 0, sm: 1 }, ml: { xs: 0, sm: -0.5 } } }}
                >
                  <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>{t('library.newDirectory')}</Box>
                </Button>
              </>
            )}
            {canManage && (
              <Button
                variant="outlined"
                size="small"
                startIcon={<PersonAddOutlined sx={{ display: { xs: 'inherit', sm: 'inherit' } }} />}
                onClick={() => setShareTarget({ dirId: currentDir!.id, name: currentDir!.name, isDir: true })}
                sx={{ height: 32, minWidth: 0, px: { xs: 1, sm: 2 }, '& .MuiButton-startIcon': { mr: { xs: 0, sm: 1 }, ml: { xs: 0, sm: -0.5 } } }}
              >
                <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>{t('library.share')}</Box>
              </Button>
            )}
          </Box>

          <Box sx={{ flexGrow: 1 }} />

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          {/* Visibility filter */}
          <Chip
            label={visFilter === 'all' ? t('library.filterVisibility')
              : visFilter === 'shared' ? t('library.filterShared')
                : t('library.filterPrivate')}
            deleteIcon={<ArrowDropDownOutlined />}
            onDelete={(e) => setVisAnchor(e.currentTarget.closest('div'))}
            onClick={(e) => setVisAnchor(e.currentTarget)}
            variant={visFilter !== 'all' ? 'filled' : 'outlined'}
            color={visFilter !== 'all' ? 'primary' : 'default'}
          />
          <Menu
            anchorEl={visAnchor}
            open={Boolean(visAnchor)}
            onClose={() => setVisAnchor(null)}
          >
            {(['all', 'shared', 'private'] as VisibilityFilter[]).map((f) => (
              <MenuItem
                key={f}
                selected={visFilter === f}
                onClick={() => { setVisFilter(f); setVisAnchor(null) }}
              >
                {f === 'all' ? t('library.filterAll')
                  : f === 'shared' ? t('library.filterShared')
                    : t('library.filterPrivate')}
              </MenuItem>
            ))}
          </Menu>

          {/* Grading filter */}
          <Chip
            label={gradingFilter === 'all' ? t('library.filterTests')
              : gradingFilter === 'auto' ? t('library.filterAutoGraded')
                : t('library.filterTeacherGraded')}
            deleteIcon={<ArrowDropDownOutlined />}
            onDelete={(e) => setGradingAnchor(e.currentTarget.closest('div'))}
            onClick={(e) => setGradingAnchor(e.currentTarget)}
            variant={gradingFilter !== 'all' ? 'filled' : 'outlined'}
            color={gradingFilter !== 'all' ? 'primary' : 'default'}
          />
          <Menu
            anchorEl={gradingAnchor}
            open={Boolean(gradingAnchor)}
            onClose={() => setGradingAnchor(null)}
          >
            {(['all', 'auto', 'teacher'] as GradingFilter[]).map((f) => (
              <MenuItem
                key={f}
                selected={gradingFilter === f}
                onClick={() => { setGradingFilter(f); setGradingAnchor(null) }}
              >
                {f === 'all' ? t('library.filterAll')
                  : f === 'auto' ? t('library.filterAutoGraded')
                    : t('library.filterTeacherGraded')}
              </MenuItem>
            ))}
          </Menu>

          {/* Sort */}
          <Button
            variant="outlined"
            size="small"
            startIcon={<SortOutlined />}
            onClick={(e) => setSortAnchor(e.currentTarget)}
            sx={{ height: 32 }}
          >
            {sortMode === 'name' ? t('library.sortByName')
              : sortMode === 'modified' ? t('library.sortByModified')
                : t('library.sortByPopularity')}
          </Button>
          <Menu
            anchorEl={sortAnchor}
            open={Boolean(sortAnchor)}
            onClose={() => setSortAnchor(null)}
          >
            {(['name', 'modified', 'popularity'] as SortMode[]).map((mode) => (
              <MenuItem
                key={mode}
                selected={sortMode === mode}
                onClick={() => { setSortMode(mode); setSortAnchor(null) }}
              >
                {mode === 'name' && t('library.sortByName')}
                {mode === 'modified' && t('library.sortByModified')}
                {mode === 'popularity' && t('library.sortByPopularity')}
              </MenuItem>
            ))}
          </Menu>
          </Box>
        </Box>
      )}

      {/* Content */}
      {isEmpty ? (
        <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
          {hasAnyItems ? t('library.noResults') : t('library.emptyDir')}
        </Typography>
      ) : (
        <Table size="small">
          <TableBody>
            {/* Directories */}
            {sortedDirs.map((dir) => (
              <TableRow
                key={`d-${dir.id}`}
                hover
                sx={{ cursor: 'pointer', '& td': { borderBottom: '1px solid', borderColor: 'divider' } }}
                onClick={(e) => {
                  if ((e as unknown as MouseEvent).metaKey || (e as unknown as MouseEvent).ctrlKey) {
                    window.open(dirLink(dir.id, dir.name), '_blank')
                  } else {
                    navigate(dirLink(dir.id, dir.name))
                  }
                }}
              >
                <TableCell width={40} sx={{ pl: 1, pr: 0 }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: 24, height: 24 }}>
                    <FolderOutlined sx={{ fontSize: 20, color: 'text.secondary' }} />
                  </Box>
                </TableCell>
                <TableCell>
                  <Typography
                    component="a"
                    {...spaLinkProps(dirLink(dir.id, dir.name), navigate, true)}
                    sx={{
                      textDecoration: 'none',
                      color: 'text.primary',
                      fontWeight: 500,
                      '&:hover': { textDecoration: 'underline' },
                    }}
                  >
                    {dir.name}
                  </Typography>
                </TableCell>
                <TableCell width={120} />
                <TableCell width={140} />
                <TableCell width={100} />
                <TableCell width={48} sx={{ pr: 1 }}>
                  {hasAccess(dir.effective_access, 'PRAW') && (
                    <IconButton
                      size="small"
                      onClick={(e) => openRowMenu(e, { type: 'dir', dir })}
                    >
                      <MoreVertOutlined fontSize="small" />
                    </IconButton>
                  )}
                </TableCell>
              </TableRow>
            ))}

            {/* Exercises */}
            {sortedExercises.map((ex) => {
              const isSelected = selectedExIds.has(ex.exercise_id)
              const hasSelection = selectedExIds.size > 0
              return (
              <TableRow
                key={`e-${ex.exercise_id}`}
                hover
                selected={isSelected}
                sx={{
                  cursor: 'pointer',
                  '& td': { borderBottom: '1px solid', borderColor: 'divider' },
                  // When no selection: show checkbox on row hover, hide icon
                  ...(!hasSelection && {
                    '& .ex-icon': { display: 'flex' },
                    '& .ex-check': { display: 'none' },
                    '&:hover .ex-icon': { display: 'none' },
                    '&:hover .ex-check': { display: 'flex' },
                  }),
                }}
                onClick={(e) => {
                  if ((e as unknown as MouseEvent).metaKey || (e as unknown as MouseEvent).ctrlKey) {
                    window.open(exerciseLink(ex.exercise_id, ex.title), '_blank')
                  } else {
                    navigate(exerciseLink(ex.exercise_id, ex.title))
                  }
                }}
              >
                <TableCell width={40} sx={{ pl: 1, pr: 0, position: 'relative' }}>
                  <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: 24, height: 24 }}>
                    {/* Icon — hidden on hover or when in selection mode */}
                    {!hasSelection && (
                      <Box className="ex-icon" sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'absolute' }}>
                        {ex.grader_type === 'AUTO' ? (
                          <RobotIcon sx={{ fontSize: 20, color: 'text.secondary' }} />
                        ) : (
                          <TeacherFaceIcon sx={{ fontSize: 20, color: 'text.secondary' }} />
                        )}
                      </Box>
                    )}
                    {/* Checkbox — shown on hover or when in selection mode */}
                    <Box className="ex-check" sx={{ display: hasSelection ? 'flex' : 'none', alignItems: 'center', justifyContent: 'center', position: 'absolute' }}>
                      <Checkbox
                        size="small"
                        checked={isSelected}
                        onClick={(e) => e.stopPropagation()}
                        onChange={() => toggleExercise(ex.exercise_id)}
                      />
                    </Box>
                  </Box>
                </TableCell>
                <TableCell>
                  <Typography
                    component="a"
                    {...spaLinkProps(exerciseLink(ex.exercise_id, ex.title), navigate, true)}
                    sx={{
                      textDecoration: 'none',
                      color: 'text.primary',
                      '&:hover': { textDecoration: 'underline' },
                    }}
                  >
                    {ex.title}
                  </Typography>
                </TableCell>
                <TableCell width={120} />
                <TableCell width={140}>
                  <Typography variant="caption" color="text.secondary">
                    <RelativeTime date={ex.modified_at} />
                  </Typography>
                </TableCell>
                <TableCell width={100}>
                  <Typography variant="caption" color="text.secondary">
                    {t('library.coursesCount', { count: ex.courses_count })}
                  </Typography>
                </TableCell>
                <TableCell width={48} sx={{ pr: 1 }}>
                  <IconButton
                    size="small"
                    onClick={(e) => openRowMenu(e, { type: 'exercise', exercise: ex })}
                  >
                    <MoreVertOutlined fontSize="small" />
                  </IconButton>
                </TableCell>
              </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}

      {!isEmpty && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', textAlign: 'center', mt: 1.5 }}>
          {[
            sortedDirs.length > 0 && t('library.dirCount', { count: sortedDirs.length }),
            sortedExercises.length > 0 && t('library.exerciseCount', { count: sortedExercises.length }),
          ].filter(Boolean).join(', ')}
        </Typography>
      )}

      {/* Row action menu */}
      <Menu
        anchorEl={rowMenuAnchor}
        open={Boolean(rowMenuAnchor)}
        onClose={closeRowMenu}
      >
        {rowMenuTarget?.type === 'dir' && [
          hasAccess(rowMenuTarget.dir.effective_access, 'PRAWM') && (
            <MenuItem
              key="share"
              onClick={() => {
                setShareTarget({ dirId: rowMenuTarget.dir.id, name: rowMenuTarget.dir.name, isDir: true })
                closeRowMenu()
              }}
            >
              <ListItemIcon><PersonAddOutlined fontSize="small" /></ListItemIcon>
              <ListItemText>{t('library.share')}</ListItemText>
            </MenuItem>
          ),
          <MenuItem
            key="rename"
            onClick={() => {
              setRenameDirTarget(rowMenuTarget.dir)
              closeRowMenu()
            }}
          >
            <ListItemIcon><DriveFileRenameOutlineOutlined fontSize="small" /></ListItemIcon>
            <ListItemText>{t('library.rename')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="delete"
            onClick={() => {
              setConfirmDelete({ type: 'dir', id: rowMenuTarget.dir.id, name: rowMenuTarget.dir.name })
              closeRowMenu()
            }}
          >
            <ListItemIcon><DeleteOutlined fontSize="small" /></ListItemIcon>
            <ListItemText>{t('library.deleteDir')}</ListItemText>
          </MenuItem>,
        ]}
        {rowMenuTarget?.type === 'exercise' && [
          hasAccess(rowMenuTarget.exercise.effective_access, 'PRAWM') && (
            <MenuItem
              key="share"
              onClick={() => {
                setShareTarget({
                  dirId: rowMenuTarget.exercise.dir_id,
                  parentDirId: currentDir?.id,
                  name: rowMenuTarget.exercise.title,
                  isDir: false,
                })
                closeRowMenu()
              }}
            >
              <ListItemIcon><PersonAddOutlined fontSize="small" /></ListItemIcon>
              <ListItemText>{t('library.share')}</ListItemText>
            </MenuItem>
          ),
          <MenuItem
            key="add-to-course"
            onClick={() => {
              setAddToCourseExercises([{ id: rowMenuTarget.exercise.exercise_id, title: rowMenuTarget.exercise.title }])
              closeRowMenu()
            }}
          >
            <ListItemIcon><PlaylistAddOutlined fontSize="small" /></ListItemIcon>
            <ListItemText>{t('library.addToCourse')}</ListItemText>
          </MenuItem>,
          <MenuItem
            key="delete"
            onClick={() => {
              setConfirmDelete({
                type: 'exercise',
                id: rowMenuTarget.exercise.exercise_id,
                name: rowMenuTarget.exercise.title,
              })
              closeRowMenu()
            }}
          >
            <ListItemIcon><DeleteOutlined fontSize="small" /></ListItemIcon>
            <ListItemText>{t('library.deleteExercise')}</ListItemText>
          </MenuItem>,
        ]}
      </Menu>

      {/* Dialogs */}
      <CreateDirDialog
        parentDirId={currentDir?.id ?? null}
        open={createDirOpen}
        onClose={() => setCreateDirOpen(false)}
        onSuccess={setSnackMsg}
      />

      <CreateExerciseDialog
        parentDirId={currentDir?.id ?? null}
        open={createExOpen}
        onClose={() => setCreateExOpen(false)}
      />

      {renameDirTarget && (
        <RenameDirDialog
          dirId={renameDirTarget.id}
          currentName={renameDirTarget.name}
          open={!!renameDirTarget}
          onClose={() => setRenameDirTarget(null)}
          onSuccess={setSnackMsg}
        />
      )}

      {addToCourseExercises.length > 0 && (
        <AddToCourseDialog
          exercises={addToCourseExercises}
          open={addToCourseExercises.length > 0}
          onClose={() => setAddToCourseExercises([])}
          onSuccess={(msg, navigateTo) => {
            setSelectedExIds(new Set())
            setSnackMsg(msg)
            setSnackAction(navigateTo)
          }}
        />
      )}

      <ShareDialog
        dirId={shareTarget?.dirId}
        parentDirId={shareTarget?.parentDirId}
        itemName={shareTarget?.name ?? ''}
        isDir={shareTarget?.isDir ?? true}
        open={!!shareTarget}
        onClose={() => setShareTarget(null)}
      />

      <ConfirmDialog
        open={!!confirmDelete}
        message={
          confirmDelete?.type === 'dir'
            ? t('library.deleteDirConfirm', { name: confirmDelete.name })
            : confirmDelete?.type === 'bulk-exercise'
              ? t('library.deleteExercisesConfirm', { count: selectedExIds.size })
              : t('library.deleteExerciseConfirm', { name: confirmDelete?.name ?? '' })
        }
        confirmLabel={t('general.delete')}
        confirmColor="error"
        isPending={deleteDir.isPending || deleteExercise.isPending}
        onClose={() => setConfirmDelete(null)}
        onConfirm={handleDeleteConfirm}
      />

      <Snackbar
        open={!!snackMsg}
        autoHideDuration={snackAction ? 6000 : 3000}
        onClose={() => { setSnackMsg(''); setSnackAction(undefined) }}
        message={snackMsg}
        action={snackAction && (
          <Button
            component="a"
            href={snackAction}
            variant="outlined"
            size="small"
            color="primary"
            onClick={(e: MouseEvent) => {
              if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
              e.preventDefault()
              navigate(snackAction)
              setSnackMsg('')
              setSnackAction(undefined)
            }}
          >
            {t('library.goToExercise')}
          </Button>
        )}
      />
    </>
  )
}
