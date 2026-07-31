# Driving the React web in a browser without a backend or an IdP

For the backend equivalent — calling core with curl and A/B-ing two builds — see
`doc/core/api-testing.md`.

There are no automated tests for `web/` yet (EZ-1705). Until there are, this is how to
actually exercise a page end to end — real router, real components, real MUI, real
react-query — with Keycloak and every API response faked. It takes about five minutes to
set up and it has caught bugs that reading the code did not:

- an SVG that silently never rendered, because `sx={{ stroke: 'primary.main' }}` is not a
  resolvable theme token and produced no stroke at all
- a redirect that fired 90ms after a click instead of the intended 800ms, from a code path
  unrelated to the animation it broke

Both were invisible in the source and obvious in a screenshot or a timing number.

## Why the app can't just be pointed at a fake IdP

`AuthProvider` does `new Keycloak(...)` from an ES module import, so it can't be replaced
from `window` in an init script. It has to be aliased at build time. Everything else is
easier than it looks: the dev server proxies `/v2` to `localhost:8080`, so Playwright's
route interception — which happens inside the browser, before the proxy — is enough to
stand in for the whole backend.

## Setup

**1. Fake Keycloak.** `web/keycloak-stub.dev.js`:

```js
export default class Keycloak {
  constructor() {
    this.authenticated = true
    this.token = 'stub-token'
    this.tokenParsed = {
      given_name: 'Test', family_name: 'Student', email: 'student@test.ee',
      preferred_username: 'dev-student', easy_role: 'student',   // or 'teacher,admin'
    }
  }
  init() { return Promise.resolve(true) }
  updateToken() { return Promise.resolve(false) }
  login() {}
  logout() {}
}
```

The file must live **inside `web/`** — Vite won't serve an alias target outside the project
root without `server.fs.allow`.

**2. Point Vite at it.** `web/vite.stub.config.ts`:

```ts
import { mergeConfig } from 'vite'
import base from './vite.config.ts'
import { fileURLToPath } from 'node:url'

export default mergeConfig(base, {
  resolve: {
    alias: { 'keycloak-js': fileURLToPath(new URL('./keycloak-stub.dev.js', import.meta.url)) },
  },
})
```

```sh
cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
```

**3. Playwright, outside the repo** so `web/package.json` stays clean. In a scratch dir:

```sh
npm init -y && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install playwright
```

`chromium.launch({ channel: 'chrome' })` drives the Chrome that's already installed — no
130MB browser download.

## The script

```js
import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'chrome' })
const ctx = await browser.newContext({
  viewport: { width: 900, height: 700 },
  deviceScaleFactor: 2,      // legible screenshots
  colorScheme: 'light',      // also run 'dark'
  // reducedMotion: 'reduce' // exercises the prefers-reduced-motion branches
})
const page = await ctx.newPage()

// The app reads all three of these on boot
await page.addInitScript(() => {
  localStorage.setItem('themeMode', 'light')
  localStorage.setItem('language', 'en')     // default is 'et' — see gotchas
  localStorage.setItem('activeRole', 'student')
})

const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

// Make the fake backend change state the way the real one would
let hasJoined = false
await page.route('**/v2/**', (route) => {
  const url = route.request().url()
  if (url.includes('/account/checkin')) return json(route, {})
  if (url.includes('/courses/join/')) { hasJoined = true; return json(route, { course_id: '9006' }) }
  if (url.includes('/student/courses')) return json(route, { courses: hasJoined ? [COURSE] : [] })
  return json(route, {})          // catch-all, so nothing hangs
})

await page.goto('http://localhost:5199/link/JOINME')
```

## Gotchas, all of which cost me time

- **Default UI language is Estonian.** `getByRole('button', { name: /join/i })` finds
  nothing because the button says *Liitu*. Seed `localStorage.language = 'en'`.
- **Arm `waitForURL` before `click()`.** Sleeping first and measuring afterwards folds your
  own sleep into the number — that turns an immediate redirect into a false PASS.
  ```js
  const navigated = page.waitForURL(/\/courses\/9006\//)
  const t0 = Date.now()
  await button.click()
  await navigated
  console.log(Date.now() - t0)
  ```
- **Model the state change, not just the response.** The 90ms-redirect bug only reproduces
  because the stubbed course list starts empty and contains the course *after* the join
  call — exactly what query invalidation does in production. A static stub misses it.
- **Run the check against the unfixed code first.** If it doesn't fail, it isn't testing
  what you think it is.
- **Always add a catch-all route.** A single unstubbed request can leave a page loading
  forever with no error.
- **`/v2` is same-origin** in dev (Vite proxies it), so match `**/v2/**`, not a backend host.

## Screenshots are the point

For anything visual, take one and look at it. Also capture a frame mid-animation
(`waitForTimeout(230)`) — that's how you confirm a staggered entrance is actually staggered
rather than just present at the end. Sampling several frames across a sequence
(`250 / 700 / 1100 / 1450ms`) is what catches elements that leave at the wrong time.

That's how the third bug turned up: an element kept its entrance animation while an exit
`transition` tried to fade it, so it stayed fully opaque while everything around it left.
A CSS animation with `fill-mode: both` pins the property at its final keyframe and outranks
plain styles, so the transition silently did nothing. The entrance has to step aside:

```tsx
...(joined ? {} : enter(280)),
```

No type checker or lint rule catches that class of bug. A screenshot does, instantly.

## Clean up

Delete `keycloak-stub.dev.js` and `vite.stub.config.ts`, stop the dev server, and confirm
with `git status` that nothing survived. Keep the Playwright scripts outside the repo.

## What this does not prove

The auth layer and every API response are fabricated. This validates a page's logic,
timing, and appearance — never that the real endpoints behave as assumed. A run against a
live core is still worth doing before calling something done.
