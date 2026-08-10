import { useQuery } from '@tanstack/react-query'
import { format } from 'date-fns'
import { apiFetch } from './client.ts'

/**
 * What is deployed (EZ-1709).
 *
 * `permitAll()` in `SecurityConf` and fetched with `noAuth`, like the embed's two endpoints: the
 * About page is reachable without signing in, and the bug report that needs a version most is the
 * one from someone who could not log in.
 *
 * Web's own version is not in here — it is baked into this bundle at build time, see
 * `build-info.d.ts`.
 */

export interface ComponentVersion {
  version: string
  commit: string
  /** ISO timestamp, absent when core was built without build-info. */
  built_at: string | null
}

export interface ExecutorVersion {
  name: string
  /** Null when the executor did not answer. */
  version: string | null
  commit: string | null
  /** aae has no build step, so this is when its source was last written to its host. */
  built_at: string | null
  reachable: boolean
}

export interface Versions {
  core: ComponentVersion
  executors: ExecutorVersion[]
}

export function useVersions() {
  return useQuery({
    queryKey: ['versions'],
    queryFn: () => apiFetch<Versions>('/unauth/versions', { noAuth: true }),
    // A deployed version changes only when someone deploys, and core caches the executor half for
    // five minutes anyway — re-asking on every focus would be pure noise.
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
}

/** `v4.0 (b14b916)`, or just the version when there is no usable commit. */
export function formatVersion(version: string, commit?: string | null): string {
  const hasCommit = commit && commit !== 'unknown'
  return hasCommit ? `v${version} (${commit})` : `v${version}`
}

/**
 * `10/08/2026 09:43` — British order, like every other date in the app, and with the time because
 * several builds share a day and "which build" is the question this whole block exists to answer.
 *
 * Rendered in the reader's own timezone: the timestamps arrive as UTC, and a person comparing what
 * the page says against when they pushed is comparing against their own clock.
 */
export function formatBuiltAt(builtAt: string | null | undefined): string {
  if (!builtAt) return ''
  const date = new Date(builtAt)
  if (Number.isNaN(date.getTime())) return ''
  return format(date, 'dd/MM/yyyy HH:mm')
}
