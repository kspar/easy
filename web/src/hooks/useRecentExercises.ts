import { useState, useCallback, useSyncExternalStore } from 'react'

const STORAGE_KEY = 'recentExercises'
const MAX_ITEMS = 10
const SYNC_EVENT = 'recentExercisesChanged'

export interface RecentExercise {
  id: string
  title: string
  viewedAt: number
}

function readRecent(): RecentExercise[] {
  try {
    const items = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    return Array.isArray(items) ? items : []
  } catch {
    return []
  }
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
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
    snapshot = next
    window.dispatchEvent(new Event(SYNC_EVENT))
  }, [])

  return { recent, addRecent }
}
