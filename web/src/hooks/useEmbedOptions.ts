import { useCallback, useState } from 'react'

const STORAGE_KEY = 'embedOptions'

/**
 * The embed dialog's snippet options, remembered across dialogs and sessions.
 *
 * Not per exercise, deliberately: the case this serves is a teacher embedding a run of exercises
 * into one wiki page, who wants the same options each time. wui persisted the same idea (the
 * allow-testing checkbox and the selected format tab).
 *
 * Only options that describe *how to embed* live here. `title-alias` and the course link belong to
 * one exercise and would be nonsense carried to the next, so they stay component state.
 */
export interface EmbedOptions {
  showTitle: boolean
  allowTesting: boolean
  format: 'html' | 'pmwiki'
}

const DEFAULTS: EmbedOptions = {
  showTitle: true,
  allowTesting: false,
  format: 'html',
}

function read(): EmbedOptions {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')
    // Spread over the defaults rather than trusting the blob: a stored value from an older shape
    // would otherwise leave a field undefined and flip a controlled switch to uncontrolled.
    return {
      ...DEFAULTS,
      ...(typeof stored === 'object' && stored !== null ? stored : {}),
      format: stored?.format === 'pmwiki' ? 'pmwiki' : 'html',
    }
  } catch {
    return DEFAULTS
  }
}

export default function useEmbedOptions(): [EmbedOptions, (patch: Partial<EmbedOptions>) => void] {
  const [options, setOptions] = useState<EmbedOptions>(read)

  const update = useCallback((patch: Partial<EmbedOptions>) => {
    setOptions((current) => {
      const next = { ...current, ...patch }
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      } catch {
        // Private browsing, quota, a locked-down profile — remembering is a convenience, so
        // failing to remember must not break the dialog.
      }
      return next
    })
  }, [])

  return [options, update]
}
