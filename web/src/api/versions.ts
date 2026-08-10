import { useQuery } from '@tanstack/react-query'
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
