import { useCallback, useState } from 'react'

type Primitive = string | number | boolean

function readMap(storageKey: string): Record<string, unknown> {
  try {
    const raw = JSON.parse(localStorage.getItem(storageKey) ?? '{}')
    return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}
  } catch {
    return {}
  }
}

/**
 * A blob of filter settings persisted per course, like the WUI's per-collection
 * "user conf". Stored as `{ [courseId]: { ...filters } }`.
 *
 * Values are merged over `defaults`, and any stored value whose type doesn't
 * match its default is discarded — so adding or changing a filter later can't be
 * broken by a blob written by an older build. Callers still own validating
 * *which* strings are acceptable (see `oneOf` at the call site).
 */
export default function useSavedFilters<T extends Record<string, Primitive>>(
  storageKey: string,
  courseId: string,
  defaults: T,
): [T, (patch: Partial<T>) => void] {
  const [filters, setFilters] = useState<T>(() => {
    const stored = readMap(storageKey)[courseId]
    if (!stored || typeof stored !== 'object') return defaults

    const merged = { ...defaults }
    for (const key of Object.keys(defaults) as (keyof T)[]) {
      const value = (stored as Record<string, unknown>)[key as string]
      if (typeof value === typeof defaults[key]) {
        merged[key] = value as T[keyof T]
      }
    }
    return merged
  })

  const update = useCallback(
    (patch: Partial<T>) => {
      setFilters((prev) => {
        const next = { ...prev, ...patch }
        const map = readMap(storageKey)
        map[courseId] = next
        try {
          localStorage.setItem(storageKey, JSON.stringify(map))
        } catch {
          // Private-mode / quota — persistence is a convenience, not a feature
        }
        return next
      })
    },
    [storageKey, courseId],
  )

  return [filters, update]
}

/** Narrow an unvalidated stored value back to its union, falling back if unknown. */
export function oneOf<T extends string>(
  value: unknown,
  allowed: readonly T[],
  fallback: T,
): T {
  return allowed.includes(value as T) ? (value as T) : fallback
}
