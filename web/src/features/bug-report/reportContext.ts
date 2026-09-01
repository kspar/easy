/**
 * The state of the world at the moment a report is filed, as a header above the activity log.
 *
 * The activity log answers "what happened"; this answers "to whom, on what, against which build".
 * Almost every one of these has been the whole of a bug at some point — a tab running a bundle
 * from three deploys ago, a teacher who was actually in the student role, a `config.json` pointing
 * at the wrong core, a phone in dark mode at 320 CSS pixels, a laptop that had gone offline.
 *
 * ### Why a registry rather than a hook
 *
 * Half of what belongs here is React state — who is signed in, which role is active, whether a
 * newer build has been seen — and the other half is global. But this string has to be producible
 * from `ErrorBoundary`, which sits *outside* the router and outside `AuthProvider` precisely so it
 * survives a throw in either. A hook could not read the first half from there, and a component
 * that has just crashed is not a good place to start calling them anyway.
 *
 * So the React side *pushes*: `AuthProvider` and `AppLayout` call [updateReportContext] from
 * effects, into module state that survives them. What has never been pushed is simply absent from
 * the header, which is the honest rendering of "the app never got that far".
 *
 * Nothing here is sent on its own. Like the breadcrumbs, it is read when the dialog opens, shown to
 * the reporter in full, and posted only with the checkbox ticked.
 */
import config from '../../config.ts'
import { runningBuild, type WebBuild } from '../../api/webVersion.ts'
import { redact } from './breadcrumbs.ts'

/** What the React side contributes. Every field optional: none of it is known before it is. */
export interface ReportContext {
  /** Keycloak's `preferred_username`, which is what an admin looks an account up by. */
  username?: string
  /** Every role the token grants, which is not the same as the one in use. */
  availableRoles?: readonly string[]
  /** The one in use — the difference between the two explains a surprising number of reports. */
  activeRole?: string
  /**
   * How far the session got: `initialised`, `authenticated`, `checked in`, or one of the failures.
   * Written as a phrase rather than flags because the failures are mutually exclusive states, and
   * a reader wants the one word that says which.
   */
  session?: string
  /** UI language, which decides which of two translations a reported message came from. */
  language?: string
  /** `light` or `dark`. Several bugs in this app have existed in exactly one of them. */
  theme?: string
  /** A build seen on the server that is not this one — i.e. this tab is stale. */
  deployedBuild?: WebBuild | null
}

let context: ReportContext = {}

/** Merge in what a caller knows. Partial, repeatable, and last write wins per field. */
export function updateReportContext(partial: ReportContext): void {
  context = { ...context, ...partial }
}

/** For tests, which need the module to start from nothing. */
export function resetReportContext(): void {
  context = {}
}

/** When this page load began, so the header can say how long the tab has been open. */
const loadedAt = Date.now()

function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m ${s}s` : `${s}s`
}

function formatBuild(build: WebBuild | null | undefined): string {
  if (!build) return 'unknown'
  const built = build.builtAt ? `, built ${build.builtAt}` : ''
  return `${build.version} (${build.commit})${built}`
}

/**
 * Whether a storage API can actually be used, rather than whether it exists.
 *
 * It exists in a private window and under blocked site data too — and throws on access. That
 * distinction is load-bearing for this app: the breadcrumb buffer, the remembered role and the
 * 401-recovery guard all live in storage and all degrade silently when it throws, which produces
 * reports ("it keeps sending me back to the login page") whose cause is invisible everywhere else.
 */
function storageState(store: 'localStorage' | 'sessionStorage'): string {
  try {
    const key = '__easyBugReportProbe'
    window[store].setItem(key, '1')
    window[store].removeItem(key)
    return 'ok'
  } catch {
    return 'unavailable'
  }
}

/** `navigator.connection`, which is Chromium-only and typed nowhere. */
interface NetworkInformation {
  effectiveType?: string
  downlink?: number
  rtt?: number
  saveData?: boolean
}

function networkLine(): string {
  const online = navigator.onLine ? 'online' : 'OFFLINE'
  const conn = (navigator as Navigator & { connection?: NetworkInformation }).connection
  if (!conn?.effectiveType) return online
  const parts = [conn.effectiveType]
  if (typeof conn.downlink === 'number') parts.push(`${conn.downlink} Mbps`)
  if (typeof conn.rtt === 'number') parts.push(`${conn.rtt} ms rtt`)
  if (conn.saveData) parts.push('data saver on')
  return `${online}, ${parts.join(', ')}`
}

function deviceLine(): string {
  const nav = navigator as Navigator & {
    deviceMemory?: number
    userAgentData?: { platform?: string; mobile?: boolean }
  }
  const parts: string[] = []
  const platform = nav.userAgentData?.platform
  if (platform) parts.push(nav.userAgentData?.mobile ? `${platform} (mobile)` : platform)
  if (typeof navigator.hardwareConcurrency === 'number') {
    parts.push(`${navigator.hardwareConcurrency} cores`)
  }
  if (typeof nav.deviceMemory === 'number') parts.push(`${nav.deviceMemory} GB`)
  return parts.join(', ')
}

function screenLine(): string {
  const dpr = window.devicePixelRatio
  return (
    `${window.innerWidth}×${window.innerHeight} viewport, ` +
    `${window.screen.width}×${window.screen.height} screen` +
    (dpr && dpr !== 1 ? ` @${dpr}×` : '')
  )
}

function preferencesLine(): string {
  const q = (query: string) => window.matchMedia?.(query).matches
  const prefs: string[] = []
  if (q('(prefers-color-scheme: dark)')) prefs.push('prefers dark')
  if (q('(prefers-reduced-motion: reduce)')) prefs.push('prefers reduced motion')
  if (q('(prefers-contrast: more)')) prefs.push('prefers more contrast')
  if (q('(pointer: coarse)')) prefs.push('coarse pointer')
  return prefs.join(', ')
}

/** `name  value`, aligned, and dropped entirely when there is no value. */
function row(name: string, value: string | undefined | null): string {
  return value ? `${name.padEnd(14)}${value}` : ''
}

/**
 * The header, as plain text in the same style as the activity log below it.
 *
 * Plain text and not JSON, because the audience is a person reading a YouTrack issue. It is also
 * the exact string the reporter is shown — see the disclosure in `BugReportDialog`.
 *
 * Redacted on the way out as well as on the way in. Everything here is assembled locally rather
 * than arriving through `record`, and the page URL is the reason: an IdP callback carries an
 * authorization code in its query string.
 */
export function describeReportContext(pageUrl: string): string {
  const stale = context.deployedBuild
  const rows = [
    row('filed', `${new Date().toISOString()} (${Intl.DateTimeFormat().resolvedOptions().timeZone})`),
    row('page', pageUrl),
    row('tab open', `${formatDuration(Date.now() - loadedAt)}, currently ${document.visibilityState}`),
    row('web build', formatBuild(runningBuild)),
    // Only when they differ, because `useWebUpdate` only ever reports a build that does. Shouted,
    // because "the fix is already deployed and this tab has never seen it" ends a triage early.
    stale ? row('deployed', `${formatBuild(stale)}  ← THIS TAB IS RUNNING AN OLDER BUILD`) : '',
    row('environment', config.environment?.label ?? 'production (no environment label)'),
    row('core at', config.emsRoot),
    row('idp', `${config.keycloak.url} realm ${config.keycloak.realm}`),
    row('account', context.username),
    row(
      'role',
      context.activeRole &&
        `${context.activeRole} of ${context.availableRoles?.join(', ') || 'unknown'}`,
    ),
    row('session', context.session),
    row('language', context.language && `${context.language} (browser ${navigator.language})`),
    row('theme', context.theme),
    row('screen', screenLine()),
    row('preferences', preferencesLine()),
    row('network', networkLine()),
    row('device', deviceLine()),
    row(
      'storage',
      `local ${storageState('localStorage')}, session ${storageState('sessionStorage')}, ` +
        `cookies ${navigator.cookieEnabled ? 'enabled' : 'DISABLED'}`,
    ),
    row('user agent', navigator.userAgent),
  ].filter(Boolean)

  return redact(rows.join('\n'))
}
