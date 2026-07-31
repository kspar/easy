import { createContext } from 'react'
import type { PaletteMode } from '@mui/material/styles'

/**
 * The context object and its hook live here rather than in ThemeContext.tsx so that file
 * exports only the provider component — mixing component and non-component exports breaks
 * React Fast Refresh (react-refresh/only-export-components).
 */
export interface ThemeContextType {
  mode: PaletteMode
  toggleMode: () => void
}

export const ThemeContext = createContext<ThemeContextType>({
  mode: 'light',
  toggleMode: () => {},
})
