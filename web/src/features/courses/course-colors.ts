/**
 * Course colour palette and the helpers that pick from it.
 *
 * Kept out of CoursesPage.tsx so that file exports only components — mixing component and
 * non-component exports breaks React Fast Refresh (react-refresh/only-export-components).
 */

export const COLOR_PALETTE = [
  '#e57373', // red
  '#f06292', // pink
  '#9575cd', // purple
  '#7986cb', // indigo
  '#4fc3f7', // blue
  '#4db6ac', // teal
  '#aed581', // green
  '#dce775', // lime
  '#ffd54f', // amber
  '#ff8a65', // orange
  '#a1887f', // brown
  '#90a4ae', // grey
]

export function randomColor(): string {
  return COLOR_PALETTE[Math.floor(Math.random() * COLOR_PALETTE.length)]
}

/** Stable colour for a string, so the same course always gets the same swatch. */
export function stringToColor(str: string): string {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  return COLOR_PALETTE[Math.abs(hash) % COLOR_PALETTE.length]
}
