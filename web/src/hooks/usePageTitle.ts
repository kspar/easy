import { useEffect } from 'react'
import { documentTitle } from '../environment.ts'

/**
 * The base title is not a constant here: on a non-production environment it carries the
 * environment prefix, so every page's title says which deployment it belongs to. See
 * `environment.ts`.
 */
export default function usePageTitle(title?: string) {
  useEffect(() => {
    document.title = documentTitle(title)
    return () => {
      document.title = documentTitle()
    }
  }, [title])
}
