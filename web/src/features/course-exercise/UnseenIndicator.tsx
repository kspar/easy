import { Box } from '@mui/material'
import { FiberManualRecordOutlined } from '@mui/icons-material'

/**
 * One glyph carries "seen" and "unseen" everywhere: a small ring, blue when nobody has opened the
 * submission and muted once somebody has.
 *
 * It marks the row in the students list and it is what the toggle in the grading header shows, so
 * the button is recognisably the same thing as the dot the teacher is trying to clear rather than a
 * second symbol they have to learn. Only the colour moves between the two states — a tick, a cross
 * or a filled dot would each be a different mark saying the same thing, and the seen state is
 * precisely the one that should recede.
 *
 * The wrapper is a flex box rather than a bare icon: an svg is inline, so on a text baseline it
 * sits a pixel or two low, which in a list of names reads as a misaligned dot.
 */
export default function UnseenIndicator({ size = 10 }: { size?: number }) {
  return <Ring size={size} color="info.main" />
}

export function SeenIndicator({ size = 10 }: { size?: number }) {
  return <Ring size={size} color="action.disabled" />
}

function Ring({ size, color }: { size: number; color: string }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      <FiberManualRecordOutlined sx={{ fontSize: size, color }} />
    </Box>
  )
}
