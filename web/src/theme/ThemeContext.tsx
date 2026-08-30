import { useState, useMemo, useCallback, useEffect, type ReactNode } from 'react'
import { ThemeProvider as MuiThemeProvider, type PaletteMode } from '@mui/material/styles'
import { CssBaseline } from '@mui/material'
import { createAppTheme } from './theme.ts'
import { ThemeContext, type ThemePreference } from './theme-context.ts'

const THEME_KEY = 'themeMode'

/**
 * Three states, not two (audit X-038).
 *
 * The app followed the OS until the first time anyone touched the toggle, and then never again:
 * `themeMode` was written to localStorage and the preference was gone for good, with no third
 * option and no way back short of devtools. It also read `prefers-color-scheme` exactly once at
 * mount, so a machine that turns dark at sunset did nothing even *before* the first toggle.
 *
 * `useEmbedTheme` has always done both of these correctly, for the same user on the same machine.
 * This brings the app up to its own embed.
 */
function systemMode(): PaletteMode {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light'
}

function storedPreference(): ThemePreference {
  try {
    const v = localStorage.getItem(THEME_KEY)
    return v === 'dark' || v === 'light' ? v : 'system'
  } catch {
    return 'system'
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(storedPreference)
  const [system, setSystem] = useState<PaletteMode>(systemMode)

  // Subscribe, rather than read once at mount: while the preference is "system", the OS changing
  // its mind mid-session is exactly the case this is for.
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => setSystem(mq.matches ? 'dark' : 'light')
    onChange()
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next)
    try {
      // "system" is the *absence* of an override, so it removes the key rather than storing a
      // third value. That way it is also what a first-time visitor gets.
      if (next === 'system') localStorage.removeItem(THEME_KEY)
      else localStorage.setItem(THEME_KEY, next)
    } catch { /* storage unavailable — the choice still holds for this session */ }
  }, [])

  const mode: PaletteMode = preference === 'system' ? system : preference

  // Kept for the two existing controls, which are switches rather than three-way pickers. Toggling
  // is an explicit choice, so it always lands on light or dark — never back to following the OS.
  const toggleMode = useCallback(() => {
    setPreference(mode === 'light' ? 'dark' : 'light')
  }, [mode, setPreference])

  const theme = useMemo(() => createAppTheme(mode), [mode])
  const value = useMemo(
    () => ({ mode, preference, setPreference, toggleMode }),
    [mode, preference, setPreference, toggleMode],
  )

  return (
    <ThemeContext.Provider value={value}>
      <MuiThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </MuiThemeProvider>
    </ThemeContext.Provider>
  )
}
