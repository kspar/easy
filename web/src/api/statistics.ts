import { useEffect, useRef, useState } from 'react'
import config from '../config.ts'
import { getToken } from './client.ts'

interface Stats {
  in_auto_assessing: number
  total_submissions: number
  total_users: number
}

interface UseStatisticsResult {
  inAutoAssessing: number
  totalSubmissions: number
  totalUsers: number
  isLoading: boolean
}

export function useStatistics(): UseStatisticsResult {
  const [stats, setStats] = useState<Stats | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    let cancelled = false

    async function poll(currentStats: Stats | null) {
      while (!cancelled) {
        const controller = new AbortController()
        abortRef.current = controller

        try {
          // Wait for token provider to be initialised (set by QueryProvider)
          while (!getToken && !cancelled) {
            await new Promise((r) => setTimeout(r, 100))
          }
          if (cancelled) return

          const token = await getToken!()
          const headers: Record<string, string> = {
            'Content-Type': 'application/json',
          }
          if (token) {
            headers['Authorization'] = `Bearer ${token}`
          }

          const response = await fetch(`${config.emsRoot}/statistics/common`, {
            method: 'POST',
            headers,
            body: currentStats ? JSON.stringify(currentStats) : undefined,
            signal: controller.signal,
          })

          if (!response.ok) throw new Error(`HTTP ${response.status}`)

          const newStats: Stats = await response.json()
          if (cancelled) return
          setStats(newStats)
          setIsLoading(false)
          currentStats = newStats
        } catch (e) {
          if (cancelled) return
          // On error, wait before retrying
          await new Promise((r) => setTimeout(r, 5000))
        }
      }
    }

    poll(null)

    return () => {
      cancelled = true
      abortRef.current?.abort()
    }
  }, [])

  return {
    inAutoAssessing: stats?.in_auto_assessing ?? 0,
    totalSubmissions: stats?.total_submissions ?? 0,
    totalUsers: stats?.total_users ?? 0,
    isLoading,
  }
}
