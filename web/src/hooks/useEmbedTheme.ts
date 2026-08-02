import { useCallback, useEffect, useState } from 'react'
import type { PaletteMode } from '@mui/material'

/**
 * Light or dark for an embedded exercise, chosen by whoever is *reading* it.
 *
 * Separate from the app's own `themeMode` on purpose. The person looking at an embed on a wiki
 * page is usually not a Lahendus user, and if they happen to be one, their preference for the
 * Lahendus UI says nothing about the page they are on right now.
 *
 * Two behaviours worth knowing:
 *
 * - **Until they choose, it follows the OS.** `prefers-color-scheme` is the best guess available,
 *   and it keeps following it — a reader who switches their machine to dark at dusk sees the
 *   embed follow, right up until the first time they overrule it here.
 * - **All embeds on a page move together.** A page can carry several of these, each its own
 *   iframe and its own React tree, with no way to talk to each other directly. They do share an
 *   origin, so `localStorage` and its `storage` event carry the choice between them: the frame
 *   that was clicked writes, and every other frame hears about it. `storage` does not fire in the
 *   document that did the writing, which is why that one sets its own state directly.
 */
const STORAGE_KEY = 'embedTheme'

function stored(): PaletteMode | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'dark' || v === 'light' ? v : null
  } catch {
    // Third-party cookie blocking can make localStorage throw outright inside an iframe. Falling
    // back to the OS preference is a fine outcome; a crashed embed is not.
    return null
  }
}

function systemMode(): PaletteMode {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light'
}

export default function useEmbedTheme(): [PaletteMode, () => void] {
  const [mode, setMode] = useState<PaletteMode>(() => stored() ?? systemMode())

  useEffect(() => {
    // Another embed on this page changed it.
    const onStorage = (e: StorageEvent) => {
      if (e.key !== STORAGE_KEY) return
      setMode(e.newValue === 'dark' || e.newValue === 'light' ? e.newValue : systemMode())
    }
    window.addEventListener('storage', onStorage)

    // And keep following the OS while the reader has expressed no preference of their own.
    const query = window.matchMedia?.('(prefers-color-scheme: dark)')
    const onSystem = () => {
      if (stored() === null) setMode(systemMode())
    }
    query?.addEventListener('change', onSystem)

    return () => {
      window.removeEventListener('storage', onStorage)
      query?.removeEventListener('change', onSystem)
    }
  }, [])

  const toggle = useCallback(() => {
    setMode((current) => {
      const next: PaletteMode = current === 'dark' ? 'light' : 'dark'
      try {
        localStorage.setItem(STORAGE_KEY, next)
      } catch {
        // Same as above: remembering is a nicety, switching must still work.
      }
      return next
    })
  }, [])

  return [mode, toggle]
}
