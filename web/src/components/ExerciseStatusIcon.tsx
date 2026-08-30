import {
  CheckCircleOutlined,
  CircleOutlined,
  HourglassEmptyOutlined,
  IncompleteCircleOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { StudentExerciseStatus } from '../api/types.ts'
import { STATUS_LABEL_KEY } from './exerciseStatus.ts'

/**
 * The one place a student's exercise status becomes a picture.
 *
 * Two call sites used to draw this themselves and had drifted apart — the sidebar showed the same
 * empty ring for STARTED and UNGRADED and left the colour to carry the difference, which is a
 * distinction nobody with a red-green deficiency can make, and which vanishes entirely in a
 * greyscale screenshot. So each status gets its own *shape* here, and colour only reinforces it:
 * a ticked ring, a half-filled ring, an hourglass, an empty ring.
 *
 * The label is not decoration either. In the sidebar the icon is the only status indicator there
 * is, so it carries the same word the chip on the exercise list spells out.
 */

const SHAPE = {
  COMPLETED: CheckCircleOutlined,
  STARTED: IncompleteCircleOutlined,
  UNGRADED: HourglassEmptyOutlined,
  UNSTARTED: CircleOutlined,
} as const

const COLOR = {
  COMPLETED: 'success.main',
  STARTED: 'warning.main',
  UNGRADED: 'info.main',
  UNSTARTED: 'text.disabled',
} as const

export default function ExerciseStatusIcon({
  status,
  size = 20,
  decorative = false,
}: {
  status: StudentExerciseStatus
  /** 16 in the sidebar, where the row is tighter; the default elsewhere. */
  size?: number
  /**
   * Drop the label where the same word is already on screen.
   *
   * The exercise list puts a chip beside this icon carrying the identical status, so labelling both
   * makes a screen reader read the row as "in progress, <title>, in progress" and pops a tooltip
   * repeating text the reader can already see. The sidebar has no chip, so it keeps the label.
   */
  decorative?: boolean
}) {
  const { t } = useTranslation()
  // `StudentExerciseStatus` is a hand-written union, not generated from core's schema, so it is a
  // claim about the server rather than a promise. The code this replaced ended in `default:`; an
  // unknown status must still draw something, not make `<Shape>` undefined and throw the whole
  // sidebar into the ErrorBoundary.
  const Shape = SHAPE[status] ?? CircleOutlined
  const color = COLOR[status] ?? 'text.disabled'
  const label = t(STATUS_LABEL_KEY[status] ?? 'exercises.unstarted')

  if (decorative) {
    return <Shape aria-hidden sx={{ fontSize: size, color }} />
  }

  return (
    <Shape
      role="img"
      aria-label={label}
      titleAccess={label}
      sx={{ fontSize: size, color }}
    />
  )
}
