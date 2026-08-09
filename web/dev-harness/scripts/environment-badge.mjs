// EZ-1733: a non-production environment must be obvious at a glance — banner, tab title, favicon,
// and the embed footer — while production stays completely undecorated.
//
//   cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
//   cd web/dev-harness && node scripts/environment-badge.mjs
import { launch, checker, fakeApi, waitUntil, BASE_URL } from '../harness.mjs'

const check = checker()
const KC = { url: 'http://localhost:9999/auth/', realm: 'stub', clientId: 'cfg' }
const STAGING = { label: 'STAGING', colour: '#b26a00' }

const { browser, page, shot } = await launch({ shotPrefix: 'env-badge-' })

await fakeApi(
  page,
  [
    ['/account/checkin', () => ({})],
    ['/courses', () => ({ courses: [] })],
    ['/messages', () => ({ messages: [] })],
  ],
  { log: false },
)

/**
 * Boot the app with a given config.json and report what the environment marking looks like.
 *
 * The title is read after a wait: `main.tsx` sets it before the app renders, but the route then
 * sets its own through usePageTitle, and the prefix has to survive that second write — which is
 * the whole point of routing it through documentTitle().
 */
async function bootWith(config, { path = '/courses', rendered = '[class*=MuiAppBar]' } = {}) {
  await page.unroute('**/config.json').catch(() => {})
  await page.route('**/config.json', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(config) }),
  )
  await page.goto(`${BASE_URL}${path}`)
  await page.locator(rendered).first().waitFor()
  // usePageTitle runs in an effect, so the route's own title lands a tick after the shell does.
  await waitUntil(async () => (await page.title()) !== 'Lahendus', { timeout: 2000 })

  return await page.evaluate(() => {
    const icons = [...document.querySelectorAll('link')]
      .filter((l) => (l.getAttribute('rel') ?? '').includes('icon'))
      .map((l) => ({ rel: l.getAttribute('rel'), href: l.getAttribute('href') ?? '' }))
    const banner = document.querySelector('[role=note]')
    return { title: document.title, icons, banner: banner ? banner.textContent : null }
  })
}

// --- production: nothing at all ------------------------------------------------------------------
// The absence of the key is what production ships, so this is the case that must stay untouched:
// no banner, no prefix, and the green icons index.html declares.
const prod = await bootWith({ emsRoot: '/v2', keycloak: KC })
check(`production: no banner (${prod.banner})`, prod.banner === null)
check(`production: plain title (${prod.title})`, !prod.title.includes('['))
check(
  `production: the original icons are untouched (${prod.icons.length})`,
  prod.icons.length >= 3 && prod.icons.every((i) => i.href.endsWith('.png') || i.href.endsWith('.ico') || i.href.endsWith('.svg')),
)
check(
  'production: no favicon was swapped in',
  !prod.icons.some((i) => i.href.startsWith('data:')),
)

// --- staging: all three signals ------------------------------------------------------------------
const staging = await bootWith({ emsRoot: '/v2', keycloak: KC, environment: STAGING })
check(`staging: banner shows the label (${staging.banner})`, (staging.banner ?? '').includes('STAGING'))
check(
  'staging: the label is not the only thing said — the warning text carries the meaning',
  (staging.banner ?? '').toLowerCase().includes('not the production environment'),
)
check(`staging: title is prefixed (${staging.title})`, staging.title.startsWith('[STAGING] '))
// The prefix must lead: a tab strip truncates from the right, so a suffix disappears exactly when
// several tabs are open, which is the case this whole feature exists for.
check(
  `staging: exactly one favicon, an SVG data URI (${staging.icons.length})`,
  staging.icons.length === 1 && staging.icons[0].href.startsWith('data:image/svg+xml,'),
)
check(
  'staging: the favicon is drawn in the configured colour',
  decodeURIComponent(staging.icons[0].href).includes(STAGING.colour),
)
// The green PNGs must be *gone*, not merely outranked: leaving one behind lets the browser keep
// showing production's icon on staging, which is the exact failure being prevented.
check(
  'staging: no green icon link survives',
  !staging.icons.some((i) => i.href.includes('favicon-32x32')),
)
await shot('01-staging-banner')

// The banner is not dismissible — no close button anywhere in it.
const bannerButtons = await page.locator('[role=note] button').count()
check(`staging: banner cannot be dismissed (${bannerButtons} buttons)`, bannerButtons === 0)

// --- a malformed environment must not take the app down ------------------------------------------
// A typo in a config file should degrade to "production", never to a configuration-error page.
const emptyLabel = await bootWith({ emsRoot: '/v2', keycloak: KC, environment: { label: '   ' } })
check(`empty label: treated as production (${emptyLabel.title})`, emptyLabel.banner === null)
check('empty label: the app still booted', (await page.locator('[class*=MuiAppBar]').count()) > 0)

const notAnObject = await bootWith({ emsRoot: '/v2', keycloak: KC, environment: 'STAGING' })
check(`environment as a string: treated as production`, notAnObject.banner === null)

// A colour is interpolated into an SVG, so anything that is not a hex colour is replaced rather
// than escaped. `<script>` in a colour has no legitimate reading.
const badColour = await bootWith({
  emsRoot: '/v2',
  keycloak: KC,
  environment: { label: 'ODD', colour: '"/><script>alert(1)</script>' },
})
const badFavicon = decodeURIComponent(badColour.icons[0]?.href ?? '')
check(`odd colour: still marked (${badColour.banner})`, (badColour.banner ?? '').includes('ODD'))
check('odd colour: nothing from it reaches the favicon', !badFavicon.includes('script'))
check('odd colour: fell back to the default amber', badFavicon.includes('#b26a00'))

// --- the embed footer ----------------------------------------------------------------------------
// Different audience from the three above: students on someone else's wiki page, long after
// whoever pasted the snippet stopped thinking about environments.
const EMBED_ID = '4242'
await page.route(`**/unauth/exercises/${EMBED_ID}/anonymous/details`, (r) =>
  r.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      title: 'Sum of two numbers',
      text_html: '<p>Read two numbers.</p>',
      submit_allowed: false,
    }),
  }),
)

const EMBED = {
  path: `/embed/exercises/${EMBED_ID}/sum?course=1&exercise=2`,
  rendered: 'text=Read two numbers',
}

await bootWith({ emsRoot: '/v2', keycloak: KC, environment: STAGING }, EMBED)
const footer = (await page.locator('#root').innerText()).replace(/\s+/g, ' ')
check(`embed: footer says the environment (${footer.slice(-60)})`, footer.includes('STAGING'))
const courseLink = await page.locator('a[href="/courses/1/exercises/2"]').innerText()
check(
  `embed: the course link says it too (${courseLink.replace(/\s+/g, ' ')})`,
  courseLink.includes('STAGING'),
)
await shot('02-embed-footer')

// And on production the embed is exactly what it was before this change.
await bootWith({ emsRoot: '/v2', keycloak: KC }, EMBED)
const prodFooter = (await page.locator('#root').innerText()).replace(/\s+/g, ' ')
check('embed on production: undecorated', prodFooter.includes('LAHENDUS') && !prodFooter.includes('STAGING'))

await browser.close()
process.exit(check.summary() ? 0 : 1)
