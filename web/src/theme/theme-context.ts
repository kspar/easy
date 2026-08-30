import { createContext } from 'react'
import type { PaletteMode } from '@mui/material/styles'

/**
 * The context object and its hook live here rather than in ThemeContext.tsx so that file
 * exports only the provider component — mixing component and non-component exports breaks
 * React Fast Refresh (react-refresh/only-export-components).
 */
/** What the user asked for. `system` means "no override" — the absence of a stored choice. */
export type ThemePreference = PaletteMode | 'system'

export interface ThemeContextType {
  /** The mode actually in force, with `system` already resolved. What components should read. */
  mode: PaletteMode
  preference: ThemePreference
  setPreference: (preference: ThemePreference) => void
  /** Light ⇄ dark. Never selects `system`: a toggle cannot express "stop deciding". */
  toggleMode: () => void
}

export const ThemeContext = createContext<ThemeContextType>({
  mode: 'light',
  preference: 'system',
  setPreference: () => {},
  toggleMode: () => {},
})
