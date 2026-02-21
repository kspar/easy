import { useState, useCallback } from 'react'

const STORAGE_KEY = 'selectedGroup'

function readMap(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}')
  } catch {
    return {}
  }
}

export default function useSavedGroup(courseId: string): [string, (groupId: string) => void] {
  const [groupId, setGroupIdState] = useState(() => readMap()[courseId] ?? '')

  const setGroupId = useCallback((id: string) => {
    setGroupIdState(id)
    const map = readMap()
    if (id) {
      map[courseId] = id
    } else {
      delete map[courseId]
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map))
  }, [courseId])

  return [groupId, setGroupId]
}
