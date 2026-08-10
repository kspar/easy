/**
 * Runtime configuration (EZ-1726).
 *
 * The environment-specific values are fetched from `/config.json` at boot rather than baked in
 * at build time, so one built dist serves every environment and the artifact CI tested is
 * literally the one that gets deployed. Pointing a deploy at a different backend means writing a
 * `config.json` next to `index.html`, not rebuilding.
 *
 * [loadConfig] must finish before anything reads these values. `main.tsx` awaits it and only then
 * imports the app, dynamically — which matters because `AuthContext.tsx` constructs its Keycloak
 * instance at module scope, so a static import would evaluate it too early.
 */

/** The environment-specific half, i.e. what `config.json` carries. */
export interface RuntimeConfig {
  /** Base URL for core. Same-origin path (`/v2`) or absolute (`https://dev.core.…/v2`). */
  emsRoot: string
  keycloak: {
    url: string
    realm: string
    clientId: string
  }
  /**
   * Where an admin goes to administer the identity provider. Optional — no value, no menu item.
   *
   * Not derived from `keycloak.url`, though it looks derivable. On dev this points at
   * `/idp-admin/`, a page installed by `ansible/roles/keycloak` that checks whether the account may
   * use Keycloak's console and says so if not; production's IdP has never been managed by that role
   * and has no such page, so deriving the URL would put a link to a 404 in production's menu.
   *
   * Optional rather than required for the same reason: an environment that has nowhere sensible to
   * send an admin should show nothing, not something broken.
   */
  idpAdminUrl?: string
  /**
   * Which deployment this is (EZ-1733), shown as a banner, a tab title prefix, a tinted favicon
   * and a line in the embed footer.
   *
   * **Absent means production**, and that direction is deliberate: production needs no config
   * edit, cannot accidentally acquire a banner, and the rule a person learns is the simple one —
   * anything unusual on the page means this is not production. The mistake being prevented is
   * deleting a course or resolving a submission on production while believing you are on dev,
   * which is exactly the habit a dev environment encourages.
   */
  environment?: {
    /**
     * Short name of the environment — DEV, LOCAL. Carries the meaning on its own, because
     * colour cannot: whoever cannot tell the banner's colour from any other still reads the word.
     */
    label: string
    /** Hex colour for the banner and the favicon badge. Defaulted rather than required. */
    colour: string
  }
}

const config = {
  // Build-time constants: identical in every environment, so deliberately not in config.json.
  appName: 'Lahendus',
  keycloakTokenMinValidSec: 30,
  repoUrl: 'https://github.com/kspar/easy',
  discordInviteUrl: 'https://discord.gg/6FBC8Z8pBP',

  // The terms of service, which live in a Google Doc rather than in this app.
  //
  // `/tos` redirects here rather than the footer linking the document directly, and that indirection
  // is the point: **Keycloak's own terms link points at `<app>/tos`**, so this stays the single place
  // the document URL is written down. Change it here and the IdP follows; link the document directly
  // from the footer and it does not.
  //
  // Build-time rather than in config.json, like the two above and like the WUI's AppProperties.TOS_URL
  // it replaces: there is one Lahendus terms document, not one per environment.
  tosUrl:
    'https://docs.google.com/document/d/1dk1Pp3hXJEX7HllQFdMFo5AXhgzy4zhZv3Qt6-xI_CI/edit?usp=sharing',

  // Replaced by loadConfig(). Empty rather than defaulted: a config that failed to load should
  // fail visibly at boot, not quietly talk to whichever backend the default happened to name.
  emsRoot: '',
  keycloak: {
    url: '',
    realm: '',
    clientId: '',
  },
  // undefined rather than '' so the menu item's condition is a plain truthiness check and an
  // environment that omits the key behaves identically to one that sets it empty.
  idpAdminUrl: undefined as string | undefined,

  // Same reasoning, and the one place where "unset" is the *normal* production value rather than
  // a degraded one. See RuntimeConfig.environment.
  environment: undefined as RuntimeConfig['environment'],
}

/** Amber: not a colour anything else in the app uses, and dark enough to carry white text. */
const DEFAULT_ENVIRONMENT_COLOUR = '#b26a00'
const HEX_COLOUR = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/
/** Long enough for DEV or LOCAL, short enough that it cannot push the app bar off the page. */
const MAX_ENVIRONMENT_LABEL = 16

/** Thrown with a message meant to be readable by whoever is looking at the blank page. */
export class ConfigError extends Error {}

/**
 * Never throws: a typo in this key must not take an environment down. Anything it cannot make
 * sense of degrades to "production", which is the same thing every existing config.json already
 * says by omitting it.
 */
function validateEnvironment(raw: unknown): RuntimeConfig['environment'] {
  if (typeof raw !== 'object' || raw === null) return undefined
  const o = raw as Record<string, unknown>

  const label = typeof o.label === 'string' ? o.label.trim().slice(0, MAX_ENVIRONMENT_LABEL) : ''
  if (label === '') return undefined

  // The colour is interpolated into an SVG data URI for the favicon, so anything that is not
  // plainly a hex colour is replaced rather than escaped — config.json is not a template language,
  // and a "colour" carrying markup has no legitimate reading.
  const colour =
    typeof o.colour === 'string' && HEX_COLOUR.test(o.colour) ? o.colour : DEFAULT_ENVIRONMENT_COLOUR

  return { label, colour }
}

function validate(raw: unknown): RuntimeConfig {
  if (typeof raw !== 'object' || raw === null) {
    throw new ConfigError('config.json is not a JSON object')
  }
  const o = raw as Record<string, unknown>
  const kc = (o.keycloak ?? {}) as Record<string, unknown>

  const missing = (
    [
      ['emsRoot', o.emsRoot],
      ['keycloak.url', kc.url],
      ['keycloak.realm', kc.realm],
      ['keycloak.clientId', kc.clientId],
    ] as const
  )
    .filter(([, v]) => typeof v !== 'string' || v === '')
    .map(([k]) => k)

  if (missing.length > 0) {
    throw new ConfigError(`config.json is missing or has empty: ${missing.join(', ')}`)
  }

  return {
    emsRoot: o.emsRoot as string,
    keycloak: {
      url: kc.url as string,
      realm: kc.realm as string,
      clientId: kc.clientId as string,
    },
    // Deliberately absent from the `missing` check above: this one is optional, and an environment
    // without it should boot normally rather than show the configuration-error page.
    idpAdminUrl:
      typeof o.idpAdminUrl === 'string' && o.idpAdminUrl !== '' ? o.idpAdminUrl : undefined,
    environment: validateEnvironment(o.environment),
  }
}

/**
 * Fetch and apply `/config.json`. Throws [ConfigError] with a displayable message on any
 * failure — a missing or malformed backend URL is not something to paper over with a default.
 */
export async function loadConfig(): Promise<void> {
  // no-store because a redeploy that changes the API URL must not be defeated by a cached
  // config. The webserver should send `Cache-Control: no-store` for it as well; a stale
  // config.json is the one real failure mode of this approach.
  let response: Response
  try {
    response = await fetch('/config.json', { cache: 'no-store' })
  } catch (e) {
    throw new ConfigError(`Could not fetch /config.json: ${(e as Error).message}`)
  }
  if (!response.ok) {
    throw new ConfigError(`Could not fetch /config.json: HTTP ${response.status}`)
  }

  let parsed: unknown
  try {
    parsed = await response.json()
  } catch {
    throw new ConfigError('config.json is not valid JSON')
  }

  const runtime = validate(parsed)

  // No VITE_ override on purpose, unlike the four below. Local dev gets its label from
  // `public/config.json`, which is committed, so every checkout is marked without anyone setting
  // anything up — and an override that can silently *remove* the marking is the wrong knob to
  // hand out for a feature whose whole job is to make an environment impossible to mistake.
  config.environment = runtime.environment

  // Local-dev convenience: VITE_* in .env.local still wins, so nobody's existing setup breaks.
  // Guarded on DEV so that a production build can never be pinned to one environment again,
  // which is the entire point of this change.
  if (import.meta.env.DEV) {
    config.emsRoot = import.meta.env.VITE_EMS_ROOT ?? runtime.emsRoot
    config.keycloak = {
      url: import.meta.env.VITE_KEYCLOAK_URL ?? runtime.keycloak.url,
      realm: import.meta.env.VITE_KEYCLOAK_REALM ?? runtime.keycloak.realm,
      clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? runtime.keycloak.clientId,
    }
    config.idpAdminUrl = import.meta.env.VITE_IDP_ADMIN_URL ?? runtime.idpAdminUrl
  } else {
    config.emsRoot = runtime.emsRoot
    config.keycloak = runtime.keycloak
    config.idpAdminUrl = runtime.idpAdminUrl
  }
}

export default config
