import { useState, useCallback } from 'react'
import { isPlainObject, readJsonIf, writeJson } from '../api/localStorage.ts'

const STORAGE_KEY = 'selectedGroup'

const readMap = (): Record<string, string> =>
  readJsonIf<Record<string, string>>(STORAGE_KEY, isPlainObject, {})

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
    // Was an unguarded setItem: it throws in Safari private browsing and on a full quota,
    // from inside this click handler. Persisting a group choice is not worth a broken page.
    writeJson(STORAGE_KEY, map)
  }, [courseId])

  return [groupId, setGroupId]
}
