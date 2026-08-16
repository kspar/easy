import { useCallback, useSyncExternalStore } from 'react'
import { readJsonIf, writeJson } from '../api/localStorage.ts'

const STORAGE_KEY = 'recentExercises'
const MAX_ITEMS = 10
const SYNC_EVENT = 'recentExercisesChanged'

export interface RecentExercise {
  id: string
  title: string
  viewedAt: number
}

function readRecent(): RecentExercise[] {
  return readJsonIf<RecentExercise[]>(STORAGE_KEY, Array.isArray, [])
}

// Shared snapshot — all hook instances in the same tab see the same reference
let snapshot = readRecent()

function subscribe(onStoreChange: () => void) {
  // Same-tab updates (custom event)
  const handleSync = () => {
    snapshot = readRecent()
    onStoreChange()
  }
  window.addEventListener(SYNC_EVENT, handleSync)
  return () => window.removeEventListener(SYNC_EVENT, handleSync)
}

function getSnapshot() {
  return snapshot
}

export default function useRecentExercises() {
  const recent = useSyncExternalStore(subscribe, getSnapshot)

  const addRecent = useCallback((id: string, title: string) => {
    const next = [
      { id, title, viewedAt: Date.now() },
      ...readRecent().filter((item) => item.id !== id),
    ].slice(0, MAX_ITEMS)
    // Guarded, like every other write in the app now. See api/localStorage.ts.
    writeJson(STORAGE_KEY, next)
    snapshot = next
    window.dispatchEvent(new Event(SYNC_EVENT))
  }, [])

  return { recent, addRecent }
}
