import { useQuery } from '@tanstack/react-query'

/**
 * Noticing that web has been redeployed under an open tab (EZ-1752).
 *
 * A deploy replaces the files behind the SPA, but a tab keeps running whatever bundle it loaded.
 * Nothing tells the person in front of it, so they stay on the old build until they happen to
 * reload — for a tab left open all week, days — and the symptoms mislead: a fixed bug still
 * present, a new page that 404s, a request core no longer accepts. Automatic deploys from master
 * make this the normal case rather than a rare one.
 *
 * The check is one fetch of `version.json`, a stamp the build writes into the dist (see
 * `vite.config.ts`), compared against the constants compiled into this bundle. No backend: web is
 * deployed as static files and often changes when core does not, so core is not in a position to
 * say which web build is live.
 *
 * What this deliberately does NOT do is reload. See `components/UpdateAvailableBanner.tsx`.
 */

/** The three fields `version.json` carries, matching the `define` constants in `vite.config.ts`. */
export interface WebBuild {
  version: string
  commit: string
  /** ISO timestamp of the build. */
  builtAt: string
}

/** How often to ask. */
const POLL_MS = 5 * 60 * 1000

/**
 * `unknown` is what `vite.config.ts` falls back to when there is no VERSION file, no `GITHUB_SHA`
 * and no git — a bundle that cannot say which build it is. Comparing against it could only produce
 * noise, so a build stamped `unknown` on either side never reports an update.
 */
const UNKNOWN = 'unknown'

/** What this running bundle is, from the build-time constants. */
export const runningBuild: WebBuild = {
  version: __APP_VERSION__,
  commit: __APP_COMMIT__,
  builtAt: __APP_BUILT_AT__,
}

/**
 * Is `deployed` a different build from `running`?
 *
 * The commit decides, because several builds share a version — a fortnight of deploys off master
 * are all `4.0`, and comparing versions would notice none of them. Deliberately "different" and
 * not "newer": there is no ordering to be had from two commit hashes, and a rollback is a change
 * a tab should hear about for exactly the same reasons a roll-forward is.
 *
 * Exported for its own sake — this is the piece worth testing directly, and it has no React,
 * no fetch and no clock in it.
 */
export function isDifferentBuild(running: WebBuild, deployed: WebBuild | null): boolean {
  if (!deployed) return false
  if (!deployed.commit || deployed.commit === UNKNOWN) return false
  if (!running.commit || running.commit === UNKNOWN) return false
  return deployed.commit !== running.commit
}

/**
 * Reads a fetched body as a build stamp, or gives up.
 *
 * The shape check is load-bearing rather than defensive habit: the SPA fallback
 * (`try_files $uri $uri/ /index.html`) answers a request for a missing file with **index.html and
 * a 200**, so a server without this file hands back HTML that is not a 404. Under `vite dev` that
 * is the normal case. Anything that is not an object with a string `commit` means "nothing to
 * say", which is also the right answer for a truncated file or a login page from a captive portal.
 */
export function parseBuild(body: unknown): WebBuild | null {
  if (typeof body !== 'object' || body === null) return null
  const b = body as Record<string, unknown>
  if (typeof b.commit !== 'string' || b.commit === '') return null
  return {
    version: typeof b.version === 'string' ? b.version : UNKNOWN,
    commit: b.commit,
    builtAt: typeof b.builtAt === 'string' ? b.builtAt : '',
  }
}

/**
 * Where the stamp lives: beside `index.html`, so it follows the app wherever it is served from
 * rather than assuming the site root.
 *
 * `cache: 'no-store'` *and* a changing query parameter, which is belt and braces on purpose. The
 * header covers the browser's own cache; the parameter covers anything in front of it that ignores
 * the header, which is the failure this cannot detect from the inside — a cached stamp does not
 * look wrong, it just silently reports "no update, ever". Unlike `config.json` (§4.1 of
 * doc/dev-environment.md), this needs no `Cache-Control` from the server to work.
 */
async function fetchDeployedBuild(): Promise<WebBuild | null> {
  const url = new URL('version.json', document.baseURI)
  url.searchParams.set('t', String(Date.now()))

  const res = await fetch(url, { cache: 'no-store', credentials: 'omit' })
  if (!res.ok) return null
  try {
    return parseBuild(await res.json())
  } catch {
    // Not JSON — almost always index.html arriving via the SPA fallback.
    return null
  }
}

/**
 * Poll for a redeploy. Returns the deployed build once it differs from this one.
 *
 * Never retries and never throws upward: a failed check is a non-event, and an offline laptop
 * should not turn into an error boundary. The query simply keeps its previous answer until the
 * next tick succeeds.
 */
export function useWebUpdate(enabled = true): { available: boolean; deployed: WebBuild | null } {
  const { data } = useQuery({
    queryKey: ['web-version'],
    queryFn: fetchDeployedBuild,
    enabled,
    refetchInterval: POLL_MS,
    // Not in a background tab. Nobody is going to act on a banner they cannot see, and a laptop
    // with twenty tabs open should not be waking up for twenty of these.
    refetchIntervalInBackground: false,
    // Coming back to a tab left open for a while is exactly when the answer is most likely to have
    // changed, and the cheapest moment to ask.
    refetchOnWindowFocus: true,
    staleTime: 0,
    retry: false,
  })

  const deployed = data ?? null
  return { available: isDifferentBuild(runningBuild, deployed), deployed }
}
