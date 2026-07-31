import { Box, Tooltip, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

/**
 * Segmented bar of student progress on one course exercise, in the same
 * order the WUI used: completed, started (i.e. submitted but under the
 * threshold), ungraded, unstarted.
 */
export default function ExerciseProgressBar({
  completed,
  started,
  ungraded,
  unstarted,
  width = 160,
}: {
  completed: number
  started: number
  ungraded: number
  unstarted: number
  width?: number | string
}) {
  const { t } = useTranslation()
  const total = completed + started + ungraded + unstarted

  const segments = [
    { count: completed, color: 'success.main', label: t('exercises.completed') },
    { count: started, color: 'warning.main', label: t('exercises.started') },
    { count: ungraded, color: 'info.main', label: t('exercises.ungraded') },
    { count: unstarted, color: 'action.disabledBackground', label: t('exercises.unstarted') },
  ].filter((s) => s.count > 0)

  // Nothing to show a bar for — render nothing rather than an empty track
  if (total === 0) return null

  // One or the other, never both: on phones the bar would take more room than the
  // title can spare, so the count stands in for it. A `display: none` element is
  // out of flex layout entirely, so the hidden one costs no width and no gap.
  return (
    <>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: { xs: 'block', sm: 'none' }, whiteSpace: 'nowrap' }}
      >
        {completed}/{total}
      </Typography>

      <Box
        sx={{
          display: { xs: 'none', sm: 'flex' },
          width,
          height: 6,
          borderRadius: 3,
          overflow: 'hidden',
          bgcolor: 'action.hover',
        }}
      >
        {segments.map((s) => (
          <Tooltip key={s.label} title={`${s.count} ${s.label}`} arrow>
            <Box sx={{ flexGrow: s.count, bgcolor: s.color }} />
          </Tooltip>
        ))}
      </Box>
    </>
  )
}
