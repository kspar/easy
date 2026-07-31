import {
  createContext,
  useContext,
  useState,
  useMemo,
  useCallback,
  type ReactNode,
} from 'react'
import { ThemeProvider as MuiThemeProvider, type PaletteMode } from '@mui/material/styles'
import { CssBaseline } from '@mui/material'
import { createAppTheme } from './theme.ts'

interface ThemeContextType {
  mode: PaletteMode
  toggleMode: () => void
}

const ThemeContext = createContext<ThemeContextType>({
  mode: 'light',
  toggleMode: () => {},
})

const THEME_KEY = 'themeMode'

function getInitialMode(): PaletteMode {
  const stored = localStorage.getItem(THEME_KEY)
  if (stored === 'light' || stored === 'dark') return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<PaletteMode>(getInitialMode)

  const toggleMode = useCallback(() => {
    setMode((prev) => {
      const next = prev === 'light' ? 'dark' : 'light'
      localStorage.setItem(THEME_KEY, next)
      return next
    })
  }, [])

  const theme = useMemo(() => createAppTheme(mode), [mode])

  return (
    <ThemeContext.Provider value={{ mode, toggleMode }}>
      <MuiThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </MuiThemeProvider>
    </ThemeContext.Provider>
  )
}

export function useThemeMode() {
  return useContext(ThemeContext)
}