/**
 * The last half hour of what this tab was doing, for attaching to a bug report.
 *
 * Nothing in this app watched the client before EZ-1786 — no `ErrorBoundary`, no `window.onerror`,
 * no `unhandledrejection`, no interceptor, no telemetry. A React render throw showed the router's
 * default error screen and nobody heard about it, and "it went blank" was the whole of what a
 * reporter could tell us. This is the smallest thing that fixes that: a capped ring buffer of four
 * kinds of event, shown to the reporter in full before it is sent, and sent only if they agree.
 *
 * ### Why sessionStorage
 *
 * The case this exists for is a crash, and a crash is frequently followed by a reload — which is
 * exactly when an in-memory buffer is empty and the reporter is on the page that lost it. So the
 * buffer is persisted. `sessionStorage` rather than `localStorage` because the lifetime is right:
 * per tab, gone when the tab closes. This holds a person's console output and the pages they
 * visited; it should not outlive the session that produced it, and it has no business being visible
 * to a different tab.
 *
 * Nothing here is sent anywhere on its own. It is read when the dialog opens and posted only with a
 * submitted report, only with the checkbox ticked.
 */

export type BreadcrumbKind = 'console' | 'error' | 'api' | 'route'

export interface Breadcrumb {
  /** Epoch millis. */
  t: number
  kind: BreadcrumbKind
  text: string
}

const STORAGE_KEY = 'bugReportBreadcrumbs'

/**
 * Three caps, because each bounds a different way of going wrong.
 *
 * [MAX_ENTRIES] bounds a render loop logging every frame; [MAX_AGE_MS] bounds a tab left open for a
 * week, where yesterday's noise would push out the thing that just happened; [MAX_TEXT] bounds one
 * enormous entry, which is the realistic case — a stack trace or a serialised object is easily
 * kilobytes, and thirty of them would fill the request on their own.
 */
const MAX_ENTRIES = 200
const MAX_AGE_MS = 30 * 60 * 1000
const MAX_TEXT = 300

/** How many stack frames to keep. Enough to place the throw, not enough to bury the message. */
const STACK_FRAMES = 5

/** Coalesces a burst — a failing render can produce hundreds of console calls in a tick. */
const FLUSH_DEBOUNCE_MS = 500

/**
 * Anything shaped like a JWT or a bearer token.
 *
 * Not hypothetical: `AuthContext.tsx` is one of the few places in this app that calls
 * `console.error` at all, and it does so when a token refresh fails. Redaction happens on the way
 * *in* rather than on the way out, so a token never sits in storage waiting for someone to decide
 * whether to send it.
 */
const TOKEN_SHAPE = /\beyJ[\w-]{10,}(\.[\w-]+)*/g

/** Bearer values in a header-ish string, which the JWT pattern alone would miss for opaque tokens. */
const BEARER_SHAPE = /\b(bearer|authorization)\b\s*:?\s*\S+/gi

export function redact(text: string): string {
  return text.replace(TOKEN_SHAPE, '[redacted-token]').replace(BEARER_SHAPE, '$1 [redacted]')
}

// --- storage, guarded exactly as api/localStorage.ts is -----------------------------------------
//
// Same reasoning, different store: reads and writes both throw in private windows, under
// third-party-cookie blocking inside an iframe, and on a full quota. Losing breadcrumbs must never
// be worse than not having collected them, and this code runs inside a `console.error` patch — a
// throw here would turn one logged error into two, from a place nothing is watching.

function readStored(): Breadcrumb[] {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (raw === null) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as Breadcrumb[]) : []
  } catch {
    return []
  }
}

function writeStored(entries: Breadcrumb[]): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(entries))
  } catch {
    // Quota, private mode, blocked storage. Nothing to do and nowhere useful to say it.
  }
}

// --- the buffer ---------------------------------------------------------------------------------

let buffer: Breadcrumb[] = readStored()
let flushTimer: ReturnType<typeof setTimeout> | null = null

function prune(entries: Breadcrumb[], now: number): Breadcrumb[] {
  // Age first, then count. Oldest dropped either way — the newest events are the ones describing
  // whatever the reporter is complaining about.
  return entries.filter((e) => now - e.t <= MAX_AGE_MS).slice(-MAX_ENTRIES)
}

function flush(): void {
  if (flushTimer !== null) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  writeStored(buffer)
}

function scheduleFlush(): void {
  if (flushTimer !== null) return
  flushTimer = setTimeout(flush, FLUSH_DEBOUNCE_MS)
}

/** Add one event. Never throws; callers are error handlers and a throw here would be invisible. */
export function record(kind: BreadcrumbKind, text: string): void {
  try {
    const now = Date.now()
    const entry = { t: now, kind, text: redact(text).slice(0, MAX_TEXT) }

    // A route recorded twice in a row is noise, and it happens: React's StrictMode double-invokes
    // effects in development, so every navigation showed up as two identical lines in the panel the
    // reporter is asked to read. Arriving at the same URL twice also tells a reader nothing the
    // first line did not.
    //
    // Only for routes. Two identical console lines or two identical failed requests are a
    // repetition that matters — a retry loop looks exactly like this, and collapsing it would hide
    // the thing worth seeing.
    const last = buffer[buffer.length - 1]
    if (kind === 'route' && last?.kind === 'route' && last.text === entry.text) return

    buffer = prune([...buffer, entry], now)

    // An error is flushed immediately rather than debounced: the plausible next event is the page
    // going away, and a debounced write would not survive it.
    if (kind === 'error') flush()
    else scheduleFlush()
  } catch {
    // Deliberately swallowed. See above.
  }
}

/** Everything still within the caps, oldest first. */
export function readBreadcrumbs(): Breadcrumb[] {
  buffer = prune(buffer, Date.now())
  return buffer
}

/** Drop everything. Called after a report is filed, so the next one starts from that moment. */
export function clearBreadcrumbs(): void {
  buffer = []
  flush()
}

function stamp(t: number): string {
  const d = new Date(t)
  const pad = (n: number, width = 2) => String(n).padStart(width, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
}

/**
 * The buffer as plain text, oldest first.
 *
 * This exact string is both what the disclosure expander shows and what the request carries, and
 * that is the point rather than a convenience: a consent checkbox next to a summary of what will be
 * sent is not consent. What they read is, character for character, what leaves the browser.
 */
export function serialiseBreadcrumbs(): string {
  return readBreadcrumbs()
    .map((e) => `${stamp(e.t)}  ${e.kind.toUpperCase().padEnd(7)}  ${e.text}`)
    .join('\n')
}

// --- the four sources --------------------------------------------------------------------------

/** First few frames of a stack, without the message line most engines repeat at the top. */
function briefStack(error: unknown): string {
  if (!(error instanceof Error) || !error.stack) return ''
  const frames = error.stack
    .split('\n')
    .filter((line) => /^\s+at\s/.test(line))
    .slice(0, STACK_FRAMES)
    .map((line) => line.trim())
  return frames.length > 0 ? ` | ${frames.join(' < ')}` : ''
}

let installed = false

/**
 * Start listening. Idempotent, and called once from `main.tsx` before the app is imported.
 *
 * Before, so that a throw while the app's own modules are evaluating is caught too — which is the
 * class of failure that produces a blank page and no clue at all.
 */
export function installBreadcrumbs(): void {
  if (installed) return
  installed = true

  window.addEventListener('error', (event) => {
    // Resource load failures (a missing image, a chunk that 404s) arrive here too, with no `error`.
    // Worth keeping — a failed chunk load is a real cause of a blank page after a deploy.
    const detail = event.error
      ? `${String(event.message)}${briefStack(event.error)}`
      : `resource failed to load: ${String((event.target as HTMLElement | null)?.nodeName ?? 'unknown')}`
    record('error', detail)
  })

  window.addEventListener('unhandledrejection', (event) => {
    const reason: unknown = event.reason
    const message = reason instanceof Error ? reason.message : String(reason)
    record('error', `unhandled rejection: ${message}${briefStack(reason)}`)
  })

  // Patched, not replaced. Everything still reaches the real console — a developer's own debugging
  // must not be quietly eaten by the bug reporter.
  patchConsole('error')
  patchConsole('warn')

  // Last chance to persist before the tab goes. `pagehide` rather than `unload`, which browsers no
  // longer fire reliably and which blocks the back/forward cache.
  window.addEventListener('pagehide', flush)
}

function patchConsole(level: 'error' | 'warn'): void {
  const original = console[level].bind(console)
  console[level] = (...args: unknown[]) => {
    record('console', `${level}: ${args.map(describe).join(' ')}`)
    original(...args)
  }
}

/**
 * One console argument as text.
 *
 * `String(someObject)` is `[object Object]`, which is worse than useless in a bug report — it looks
 * like information. JSON gets the shape across; a circular structure or a DOM node falls back
 * rather than throwing inside a logging patch.
 */
function describe(value: unknown): string {
  if (typeof value === 'string') return value
  if (value instanceof Error) return `${value.name}: ${value.message}${briefStack(value)}`
  try {
    return JSON.stringify(value) ?? String(value)
  } catch {
    return String(value)
  }
}
