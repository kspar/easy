import { useQuery } from '@tanstack/react-query'
import { apiFetch } from './client.ts'

/**
 * How the deployment is doing (EZ-1709), for admins.
 *
 * `@Secured("ROLE_ADMIN")` in core, and the hook is enabled only while acting as admin — a teacher
 * who happens to have the admin role would otherwise fire a request that can only 403.
 *
 * Deliberately not Spring Actuator: see `core/ems/service/operating_info.kt` for why.
 */

export interface OperatingInfo {
  jvm: {
    started_at: string
    uptime_sec: number
    heap_used_mb: number
    /** -1 when the JVM reports no maximum. */
    heap_max_mb: number
    threads: number
    java_version: string
  }
  /** Null if the pool is not HikariCP. */
  db_pool: { active: number; idle: number; waiting: number; max: number } | null
  schema: {
    changeset: string | null
    filename: string | null
    applied_at: string | null
    total_changesets: number
  }
  grading: { executor: string; queued: number; running: number; reachable: boolean }[]
  disk: { free_gb: number; total_gb: number }
}

export function useOperatingInfo(enabled: boolean) {
  return useQuery({
    queryKey: ['operating-info'],
    queryFn: () => apiFetch<OperatingInfo>('/admin/operating-info'),
    enabled,
    // Queue depth and heap move minute to minute, unlike versions — but this is a page someone
    // reads, not a dashboard they watch, so it refetches on focus rather than on a timer.
    staleTime: 15 * 1000,
    retry: false,
  })
}

/** `3d 4h`, `4h 12m`, `12m` — the largest two units that are non-zero. */
export function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${minutes}m`
}
