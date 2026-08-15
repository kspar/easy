// EZ-1733: a non-production environment must be obvious at a glance — banner, tab title, favicon,
// and the embed footer — while production stays completely undecorated.
//
//   cd web && npx playwright test environment-badge
import { test } from '../support/spec.mjs'
import { fakeApi, waitUntil, BASE_URL } from '../support/harness.mjs'

test('environment-badge', async ({ launch, check }) => {
  const KC = { url: 'http://localhost:9999/auth/', realm: 'stub', clientId: 'cfg' }
  const DEV = { label: 'DEV', colour: '#b26a00' }

  const { page, shot, close } = await launch({ shotPrefix: 'env-badge-' })

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
      // The badge is the element carrying the environment warning as its accessible name — found
      // that way rather than by class or position, since it renders in two places (sidenav on
      // desktop, app bar on mobile) and neither is inherently "the" one.
      const badge = document.querySelector('[aria-label*="not the production"], [aria-label*="päriskeskkond"]')
      return { title: document.title, icons, badge: badge ? badge.textContent.trim() : null }
    })
  }

  // --- production: nothing at all ------------------------------------------------------------------
  // The absence of the key is what production ships, so this is the case that must stay untouched:
  // no banner, no prefix, and the green icons index.html declares.
  const prod = await bootWith({ emsRoot: '/v2', keycloak: KC })
  check(`production: no badge (${prod.badge})`, prod.badge === null)
  check(`production: plain title (${prod.title})`, !prod.title.includes('['))
  check(
    `production: the original icons are untouched (${prod.icons.length})`,
    prod.icons.length >= 3 && prod.icons.every((i) => i.href.endsWith('.png') || i.href.endsWith('.ico') || i.href.endsWith('.svg')),
  )
  check(
    'production: no favicon was swapped in',
    !prod.icons.some((i) => i.href.startsWith('data:')),
  )

  // --- dev: all three signals ------------------------------------------------------------------
  const dev = await bootWith({ emsRoot: '/v2', keycloak: KC, environment: DEV })
  check(`dev: the badge shows the label (${dev.badge})`, (dev.badge ?? '').includes('DEV'))
  // The label is short enough to be cryptic on its own, so the warning has to be reachable — it is
  // the badge's accessible name and its tooltip. Colour is never the only channel.
  check(
    'dev: the badge carries the warning as its accessible name',
    await page.locator('[aria-label*="not the production"]').count() > 0,
  )
  // Beside the wordmark, not floating somewhere else on the page: the whole point of moving it off
  // the top strip was that it should be read together with "LAHENDUS".
  check(
    'dev: it sits next to the wordmark',
    await page.evaluate(() => {
      const badge = document.querySelector('[aria-label*="not the production"]')
      const mark = [...document.querySelectorAll('*')].find(
        (el) => el.children.length === 0 && el.textContent.trim() === 'LAHENDUS',
      )
      if (!badge || !mark) return false
      const b = badge.getBoundingClientRect()
      const m = mark.getBoundingClientRect()
      // Same line, and within a wordmark's width of it.
      return Math.abs(b.top - m.top) < 24 && b.left >= m.left && b.left - m.right < 40
    }),
  )
  check(`dev: title is prefixed (${dev.title})`, dev.title.startsWith('[DEV] '))
  // The prefix must lead: a tab strip truncates from the right, so a suffix disappears exactly when
  // several tabs are open, which is the case this whole feature exists for.
  check(
    `dev: exactly one favicon, an SVG data URI (${dev.icons.length})`,
    dev.icons.length === 1 && dev.icons[0].href.startsWith('data:image/svg+xml,'),
  )
  check(
    'dev: the favicon is drawn in the configured colour',
    decodeURIComponent(dev.icons[0].href).includes(DEV.colour),
  )
  // The green PNGs must be *gone*, not merely outranked: leaving one behind lets the browser keep
  // showing production's icon on dev, which is the exact failure being prevented.
  check(
    'dev: no green icon link survives',
    !dev.icons.some((i) => i.href.includes('favicon-32x32')),
  )
  await shot('01-dev-badge')

  // Nothing to dismiss: a marking someone can turn off is a marking that is off on the day it
  // matters. The badge is a span, not a chip with a delete affordance.
  const dismissables = await page.locator('[aria-label*="not the production"] button').count()
  check(`dev: nothing to dismiss (${dismissables} buttons inside it)`, dismissables === 0)

  // --- the landing page, which lives outside AppLayout ----------------------------------------------
  // The one page a signed-out person lands on, and the page a production/dev mix-up starts from. It
  // has its own navbar rather than the app shell's, so the badge has to be placed there separately —
  // which means it can silently stop being there without anything else failing.
  const landing = await bootWith(
    { emsRoot: '/v2', keycloak: KC, environment: DEV },
    { path: '/landing', rendered: 'text=LAHENDUS' },
  )
  check(`landing: marked too (${landing.badge})`, (landing.badge ?? '').includes('DEV'))
  check(`landing: title carries the prefix (${landing.title})`, landing.title.startsWith('[DEV] '))
  await shot('03-landing')

  const landingProd = await bootWith(
    { emsRoot: '/v2', keycloak: KC },
    { path: '/landing', rendered: 'text=LAHENDUS' },
  )
  check('landing on production: undecorated', landingProd.badge === null)

  // --- a malformed environment must not take the app down ------------------------------------------
  // A typo in a config file should degrade to "production", never to a configuration-error page.
  const emptyLabel = await bootWith({ emsRoot: '/v2', keycloak: KC, environment: { label: '   ' } })
  check(`empty label: treated as production (${emptyLabel.title})`, emptyLabel.badge === null)
  check('empty label: the app still booted', (await page.locator('[class*=MuiAppBar]').count()) > 0)

  const notAnObject = await bootWith({ emsRoot: '/v2', keycloak: KC, environment: 'DEV' })
  check(`environment as a string: treated as production`, notAnObject.badge === null)

  // A colour is interpolated into an SVG, so anything that is not a hex colour is replaced rather
  // than escaped. `<script>` in a colour has no legitimate reading.
  const badColour = await bootWith({
    emsRoot: '/v2',
    keycloak: KC,
    environment: { label: 'ODD', colour: '"/><script>alert(1)</script>' },
  })
  const badFavicon = decodeURIComponent(badColour.icons[0]?.href ?? '')
  check(`odd colour: still marked (${badColour.badge})`, (badColour.badge ?? '').includes('ODD'))
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

  await bootWith({ emsRoot: '/v2', keycloak: KC, environment: DEV }, EMBED)
  const footer = (await page.locator('#root').innerText()).replace(/\s+/g, ' ')
  check(`embed: footer says the environment (${footer.slice(-60)})`, footer.includes('DEV'))
  const courseLink = await page.locator('a[href="/courses/1/exercises/2"]').innerText()
  check(
    `embed: the course link says it too (${courseLink.replace(/\s+/g, ' ')})`,
    courseLink.includes('DEV'),
  )
  await shot('02-embed-footer')

  // And on production the embed is exactly what it was before this change.
  await bootWith({ emsRoot: '/v2', keycloak: KC }, EMBED)
  const prodFooter = (await page.locator('#root').innerText()).replace(/\s+/g, ' ')
  check('embed on production: undecorated', prodFooter.includes('LAHENDUS') && !prodFooter.includes('DEV'))

  await close()
})
