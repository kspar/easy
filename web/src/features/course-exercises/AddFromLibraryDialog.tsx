import { useMemo, useState } from 'react'
import {
  Box,
  Breadcrumbs,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  ChevronRightOutlined,
  CloseOutlined,
  FolderOutlined,
  PlaylistAddCheckOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { useLibraryDir, useAddExerciseToCourse } from '../../api/library.ts'
import { useTeacherCourseExercises } from '../../api/exercises.ts'
import { RobotIcon, TeacherFaceIcon } from '../../components/icons.tsx'

interface Crumb {
  id: string
  name: string
}

/**
 * Attach existing library exercises to this course. New in React — WUI only
 * offered the reverse direction (library page → "Add to course").
 */
export default function AddFromLibraryDialog({
  courseId,
  open,
  onClose,
  onSuccess,
}: {
  courseId: string
  open: boolean
  onClose: () => void
  onSuccess: (msg: string) => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const addToCourse = useAddExerciseToCourse()

  // Browsing state — a breadcrumb trail we push/pop, so we never need the
  // /parents endpoint here.
  const [trail, setTrail] = useState<Crumb[]>([])
  const currentDirId = trail.length > 0 ? trail[trail.length - 1].id : 'root'
  const { data, isLoading } = useLibraryDir(currentDirId)

  // Selection survives navigation, so titles are carried along with the ids
  const [selected, setSelected] = useState<Map<string, string>>(new Map())
  const [pending, setPending] = useState(false)

  const { data: courseExercises } = useTeacherCourseExercises(courseId)
  const alreadyOnCourse = useMemo(
    () => new Set((courseExercises ?? []).map((ex) => ex.exercise_id)),
    [courseExercises],
  )

  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
  const dirs = [...(data?.child_dirs ?? [])].sort((a, b) => collator.compare(a.name, b.name))
  const exercises = [...(data?.child_exercises ?? [])].sort((a, b) =>
    collator.compare(a.title, b.title),
  )

  function toggle(exerciseId: string, title: string) {
    setSelected((prev) => {
      const next = new Map(prev)
      if (next.has(exerciseId)) next.delete(exerciseId)
      else next.set(exerciseId, title)
      return next
    })
  }

  // Select-all deliberately skips exercises already on the course: ticking them
  // in bulk would quietly create duplicates. They stay individually tickable for
  // anyone who actually wants a second copy.
  const selectable = exercises.filter((ex) => !alreadyOnCourse.has(ex.exercise_id))
  const allSelected =
    selectable.length > 0 && selectable.every((ex) => selected.has(ex.exercise_id))
  const someSelected = selectable.some((ex) => selected.has(ex.exercise_id))

  function toggleAllInDir() {
    setSelected((prev) => {
      const next = new Map(prev)
      if (allSelected) {
        selectable.forEach((ex) => next.delete(ex.exercise_id))
      } else {
        selectable.forEach((ex) => next.set(ex.exercise_id, ex.title))
      }
      return next
    })
  }

  async function handleAdd() {
    if (selected.size === 0) return
    setPending(true)
    const ids = [...selected.keys()]
    const results = await Promise.allSettled(
      ids.map((exerciseId) => addToCourse.mutateAsync({ courseId, exerciseId })),
    )
    await queryClient.invalidateQueries({
      queryKey: ['teacher', 'courses', courseId, 'exercises'],
    })
    setPending(false)

    const added = results.filter((r) => r.status === 'fulfilled').length
    const failed = results.length - added
    handleClose()
    onSuccess(
      failed > 0
        ? t('exercises.addedToCoursePartial', { count: added, failed })
        : t('exercises.addedToCourse', { count: added }),
    )
  }

  function handleClose() {
    setTrail([])
    setSelected(new Map())
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ pb: 1 }}>{t('exercises.addFromLibrary')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important', display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        {/* Breadcrumbs */}
        <Breadcrumbs separator={<ChevronRightOutlined sx={{ fontSize: 16 }} />}>
          <Typography
            component="button"
            onClick={() => setTrail([])}
            sx={{
              background: 'none',
              border: 0,
              p: 0,
              cursor: trail.length > 0 ? 'pointer' : 'default',
              font: 'inherit',
              color: trail.length > 0 ? 'text.secondary' : 'text.primary',
              fontWeight: trail.length > 0 ? 400 : 500,
              '&:hover': { textDecoration: trail.length > 0 ? 'underline' : 'none' },
            }}
          >
            {t('library.title')}
          </Typography>
          {trail.map((crumb, i) => {
            const isLast = i === trail.length - 1
            return (
              <Typography
                key={crumb.id}
                component="button"
                onClick={() => setTrail(trail.slice(0, i + 1))}
                sx={{
                  background: 'none',
                  border: 0,
                  p: 0,
                  cursor: isLast ? 'default' : 'pointer',
                  font: 'inherit',
                  color: isLast ? 'text.primary' : 'text.secondary',
                  fontWeight: isLast ? 500 : 400,
                  '&:hover': { textDecoration: isLast ? 'none' : 'underline' },
                }}
              >
                {crumb.name}
              </Typography>
            )
          })}
        </Breadcrumbs>

        {/* Listing */}
        <Box
          sx={{
            border: 1,
            borderColor: 'divider',
            borderRadius: 2,
            height: 360,
            overflow: 'auto',
          }}
        >
          {isLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
              <CircularProgress size={28} />
            </Box>
          ) : dirs.length === 0 && exercises.length === 0 ? (
            <Typography color="text.secondary" sx={{ textAlign: 'center', py: 6 }}>
              {t('library.emptyDir')}
            </Typography>
          ) : (
            <List disablePadding dense>
              {/* Select-all for the current directory only; the selection itself
                  still accumulates across directories. */}
              {selectable.length > 0 && (
                <Box
                  role="button"
                  tabIndex={0}
                  aria-label={t('exercises.selectAllInDir')}
                  onClick={toggleAllInDir}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      toggleAllInDir()
                    }
                  }}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    pl: 1,
                    pr: 2,
                    py: 0.25,
                    cursor: 'pointer',
                    position: 'sticky',
                    top: 0,
                    zIndex: 1,
                    bgcolor: 'background.paper',
                    borderBottom: 1,
                    borderColor: 'divider',
                    '&:hover, &:focus-visible': { bgcolor: 'action.hover', outline: 'none' },
                  }}
                >
                  <Checkbox
                    size="small"
                    checked={allSelected}
                    indeterminate={someSelected && !allSelected}
                    tabIndex={-1}
                    disableRipple
                    sx={{ p: 0.5 }}
                  />
                  <Typography variant="body2" color="text.secondary">
                    {t('exercises.selectAllInDir')}
                  </Typography>
                  <Box sx={{ flexGrow: 1 }} />
                  <Typography variant="caption" color="text.disabled">
                    {t('library.exerciseCount', { count: selectable.length })}
                  </Typography>
                </Box>
              )}

              {dirs.map((dir) => (
                <ListItemButton
                  key={dir.id}
                  onClick={() => setTrail([...trail, { id: dir.id, name: dir.name }])}
                >
                  <ListItemIcon sx={{ minWidth: 36 }}>
                    <FolderOutlined sx={{ fontSize: 20, color: 'text.secondary' }} />
                  </ListItemIcon>
                  <ListItemText primary={dir.name} primaryTypographyProps={{ fontWeight: 500 }} />
                  <ChevronRightOutlined sx={{ fontSize: 18, color: 'text.disabled' }} />
                </ListItemButton>
              ))}

              {exercises.map((ex) => {
                const isOnCourse = alreadyOnCourse.has(ex.exercise_id)
                return (
                  <ListItemButton
                    key={ex.exercise_id}
                    onClick={() => toggle(ex.exercise_id, ex.title)}
                    selected={selected.has(ex.exercise_id)}
                  >
                    <ListItemIcon sx={{ minWidth: 36 }}>
                      <Checkbox
                        edge="start"
                        size="small"
                        checked={selected.has(ex.exercise_id)}
                        tabIndex={-1}
                        disableRipple
                        sx={{ p: 0.5 }}
                      />
                    </ListItemIcon>
                    <ListItemIcon sx={{ minWidth: 30 }}>
                      {ex.grader_type === 'AUTO' ? (
                        <RobotIcon sx={{ fontSize: 18, color: 'text.secondary' }} />
                      ) : (
                        <TeacherFaceIcon sx={{ fontSize: 18, color: 'text.secondary' }} />
                      )}
                    </ListItemIcon>
                    <ListItemText primary={ex.title} />
                    {isOnCourse && (
                      <Tooltip title={t('exercises.alreadyOnCourse')} arrow>
                        <PlaylistAddCheckOutlined
                          sx={{ fontSize: 18, color: 'warning.main', flexShrink: 0 }}
                        />
                      </Tooltip>
                    )}
                  </ListItemButton>
                )
              })}
            </List>
          )}
        </Box>

        {/* Selection summary — selections made in other directories stay visible */}
        {selected.size > 0 && (
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
            {[...selected].map(([id, title]) => (
              <Chip
                key={id}
                size="small"
                label={title}
                onDelete={() => toggle(id, title)}
                deleteIcon={<CloseOutlined />}
              />
            ))}
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleAdd}
          variant="contained"
          disabled={pending || selected.size === 0}
        >
          {pending
            ? t('general.adding')
            : selected.size > 0
              ? `${t('general.add')} (${selected.size})`
              : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
