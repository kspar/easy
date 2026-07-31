import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
} from '@mui/material'
import { AddOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useReorderCourseExercise } from '../../api/exercises.ts'
import type { TeacherCourseExercise } from '../../api/types.ts'

/**
 * Pick a new position for one exercise. Unlike WUI's radio list this shows the
 * course as it will look after the move: the other exercises stay put and the
 * moved one is drawn in place between them, so you choose the *result* rather
 * than decoding "before which one".
 */
export default function ReorderExerciseDialog({
  courseId,
  exercise,
  allExercises,
  onClose,
  onSuccess,
}: {
  courseId: string
  exercise: TeacherCourseExercise
  allExercises: TeacherCourseExercise[]
  onClose: () => void
  onSuccess: (msg: string) => void
}) {
  const { t } = useTranslation()
  const reorder = useReorderCourseExercise(courseId)

  // Mounted fresh each time a target is picked, so the initial value is enough
  const oldIndex = exercise.ordering_idx
  const [target, setTarget] = useState(oldIndex)

  const listRef = useRef<HTMLDivElement>(null)
  const movedRef = useRef<HTMLDivElement>(null)

  // On a long course the exercise being moved can start well below the fold, so
  // centre it on open. scrollTop is set directly rather than via scrollIntoView,
  // which would also scroll the dialog itself.
  const centreMovedRow = useCallback(() => {
    const list = listRef.current
    const moved = movedRef.current
    if (!list || !moved) return
    list.scrollTop = moved.offsetTop - list.clientHeight / 2 + moved.offsetHeight / 2
  }, [])

  // Runs twice by design. The mount pass positions the list before first paint
  // so there's no visible jump; the pass in the Dialog's onEntered re-applies it
  // because MUI's focus trap focuses an element once the transition ends, and
  // focusing scrolls the container back to the top. Mount-only otherwise —
  // re-centring whenever a slot is picked would yank the list under the cursor.
  useEffect(centreMovedRow, [centreMovedRow])

  // The other exercises, in order — the moved one slots into gap `i`, meaning
  // "after others[i - 1] and before others[i]".
  const others = allExercises
    .filter((ex) => ex.course_exercise_id !== exercise.course_exercise_id)
    .sort((a, b) => a.ordering_idx - b.ordering_idx)

  function handleMove() {
    if (target === oldIndex) {
      onClose()
      return
    }
    reorder.mutate(
      { courseExerciseId: exercise.course_exercise_id, newIndex: target },
      {
        onSuccess: () => {
          onClose()
          onSuccess(t('general.moved'))
        },
      },
    )
  }

  const gaps = Array.from({ length: others.length + 1 }, (_, i) => i)

  return (
    <Dialog
      open
      onClose={onClose}
      maxWidth="xs"
      fullWidth
      TransitionProps={{ onEntered: centreMovedRow }}
    >
      <DialogTitle sx={{ pb: 1 }}>
        {t('exercises.moveExercise')}
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
          {t('exercises.moveExerciseHelp')}
        </Typography>
      </DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <Box
          ref={listRef}
          sx={{
            // Positioned so the moved row's offsetTop is relative to this box
            position: 'relative',
            border: 1,
            borderColor: 'divider',
            borderRadius: 2,
            py: 1,
            maxHeight: 420,
            overflow: 'auto',
          }}
        >
          {gaps.map((gap) => (
            <Box key={gap}>
              {/* Drop slot — the moved exercise is drawn here when selected */}
              {target === gap ? (
                <Box
                  // Only the initial position is scrolled to, so tracking the ref
                  // across slot changes isn't needed
                  ref={gap === oldIndex ? movedRef : undefined}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    mx: 1,
                    my: 0.5,
                    px: 1,
                    py: 0.75,
                    borderRadius: 1.5,
                    bgcolor: 'primary.main',
                    color: 'primary.contrastText',
                  }}
                >
                  <Typography variant="caption" sx={{ minWidth: 18, textAlign: 'right', opacity: 0.8 }}>
                    {gap + 1}.
                  </Typography>
                  <Typography variant="body2" fontWeight={500} noWrap>
                    {exercise.effective_title}
                  </Typography>
                </Box>
              ) : (
                <Box
                  role="button"
                  tabIndex={0}
                  aria-label={t('exercises.moveHere')}
                  onClick={() => setTarget(gap)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      setTarget(gap)
                    }
                  }}
                  sx={{
                    position: 'relative',
                    mx: 1,
                    my: 0.25,
                    height: 28,
                    display: 'flex',
                    alignItems: 'center',
                    borderRadius: 1.5,
                    cursor: 'pointer',
                    color: 'text.disabled',
                    // Grow the hit area 5px past the box top and bottom without
                    // affecting layout, so aiming at a 2px dashed line isn't
                    // required. The neighbouring rows aren't interactive, so
                    // borrowing their padding costs nothing.
                    '&::before': {
                      content: '""',
                      position: 'absolute',
                      inset: '-5px 0',
                    },
                    // The label is what makes the slot read as a target rather
                    // than a divider; it overlays the line so the line stays
                    // unbroken at rest.
                    '& .slot-label': { opacity: 0, transition: 'opacity 120ms' },
                    '&:hover, &:focus-visible': {
                      color: 'primary.main',
                      outline: 'none',
                    },
                    '&:hover .slot-label, &:focus-visible .slot-label': { opacity: 1 },
                    '&:hover .slot-line, &:focus-visible .slot-line': {
                      borderColor: 'primary.main',
                    },
                  }}
                >
                  <Box
                    className="slot-line"
                    sx={{
                      flexGrow: 1,
                      mx: 0.5,
                      borderBottom: '2px dashed',
                      borderColor: 'divider',
                    }}
                  />
                  <Typography
                    className="slot-label"
                    variant="caption"
                    sx={{
                      position: 'absolute',
                      left: '50%',
                      transform: 'translateX(-50%)',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 0.25,
                      px: 1,
                      whiteSpace: 'nowrap',
                      bgcolor: 'background.paper',
                    }}
                  >
                    <AddOutlined sx={{ fontSize: 14 }} />
                    {t('exercises.moveHere')}
                  </Typography>
                </Box>
              )}

              {/* The exercise that follows this slot */}
              {gap < others.length && (
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    px: 2,
                    py: 0.75,
                  }}
                >
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ minWidth: 18, textAlign: 'right' }}
                  >
                    {/* Position after the move: everything at or past the
                        chosen slot is pushed down by one */}
                    {(gap < target ? gap + 1 : gap + 2)}.
                  </Typography>
                  <Typography variant="body2" color="text.secondary" noWrap>
                    {others[gap].effective_title}
                  </Typography>
                </Box>
              )}
            </Box>
          ))}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleMove}
          variant="contained"
          disabled={reorder.isPending || target === oldIndex}
        >
          {reorder.isPending ? t('exercises.moving') : t('general.move')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
