import { useContext } from 'react'
import { ThemeContext } from './theme-context.ts'

export function useThemeMode() {
  return useContext(ThemeContext)
}
