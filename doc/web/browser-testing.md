# Driving the React web in a browser without a backend or an IdP

For the backend equivalent — calling core with curl and A/B-ing two builds — see
`doc/core/api-testing.md`.

There are no automated tests for `web/` yet (EZ-1705). Until there are, this is how to
actually exercise a page end to end — real router, real components, real MUI, real
react-query — with Keycloak and every API response faked. It has caught bugs that reading
the code did not:

- an SVG that silently never rendered, because `sx={{ stroke: 'primary.main' }}` is not a
  resolvable theme token and produced no stroke at all
- a redirect that fired 90ms after a click instead of the intended 800ms, from a code path
  unrelated to the animation it broke
- a selection checkbox that never toggled, because `preventDefault()` (needed to stop the
  row's `<a>` from navigating) also cancels the checkbox's own state change, so `onChange`
  never fired

All three were invisible in the source and obvious in a screenshot or a timing number.

## The harness is checked in — don't rebuild it

Everything lives in **`web/dev-harness/`** and is committed. `node_modules` and
`screenshots/` are gitignored; nothing needs to be created or deleted per session.

```
web/vite.stub.config.ts          dev server with keycloak-js aliased to the stub
web/dev-harness/
  keycloak-stub.js               fake IdP; role comes from localStorage.stubRole
  harness.mjs                    launch() / fakeApi() / json() / checker()
  package.json                   playwright, kept out of web/package.json
  scripts/*.mjs                  one script per page under test, plus
                                 runtime-config.mjs which covers boot-time
                                 config loading and its failure screens
  screenshots/                   output (gitignored)
```

First time on a new machine:

```sh
cd web/dev-harness && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install
```

`chromium.launch({ channel: 'chrome' })` drives the Chrome that's already installed, so
there's no 130MB browser download.

## Running

Two terminals. The dev server:

```sh
cd web && npx vite --config vite.stub.config.ts --port 5199 --strictPort
```

and the script:

```sh
cd web/dev-harness && node scripts/course-exercises.mjs
```

It prints a `✅`/`❌` line per check, a `📷` line per screenshot, and exits non-zero if
anything failed.

## Why the app can't just be pointed at a fake IdP

`AuthProvider` does `new Keycloak(...)` from an ES module import, so it can't be replaced
from `window` in an init script — hence the build-time alias. Everything else is easier
than it looks: the dev server proxies `/v2` to `localhost:8080`, so Playwright's route
interception, which happens inside the browser *before* the proxy, stands in for the whole
backend.

## Writing a new script

`harness.mjs` carries the boilerplate. A minimal script:

```js
import { launch, fakeApi, json, checker, BASE_URL } from '../harness.mjs'

const { browser, page, shot } = await launch({ role: 'teacher,admin', shotPrefix: 'my-page-' })
const check = checker()

// Handlers are [urlSubstringOrRegex, handler] pairs, tried in order.
// Return a value to send it as JSON, or call route.fulfill yourself and return undefined.
// Anything unmatched is fulfilled with {} and logged as [unstubbed].
const calls = await fakeApi(page, [
  ['/account/checkin', () => ({})],
  [/\/teacher\/courses\/\d+\/exercises(\?|$)/, () => ({ exercises: EXERCISES })],
])

await page.goto(`${BASE_URL}/courses/9006/exercises`)
await page.waitForSelector('a[href*="/exercises/"]')

check('rows render', (await page.locator('a[href*="/exercises/"]').count()) === 4)
await shot('01-list')

process.exit(check.summary() ? 0 : 1)
```

`launch()` options: `role`, `language` (default `'en'`), `theme`, `colorScheme`, `viewport`,
`reducedMotion`, `shotPrefix`. It also forwards browser console errors and page errors to
stdout, which is how you notice a broken query without asserting on it.

## Gotchas, all of which cost me time

- **Match query strings.** `/\/exercises$/` does not match `…/exercises?group=11`. Use
  `(\?|$)`. A near-miss regex silently falls through to the catch-all and the page renders
  an empty list that looks like an app bug.
- **Default UI language is Estonian.** `launch()` seeds `language: 'en'` for this reason;
  without it `getByRole('button', { name: /join/i })` finds nothing because the button says
  *Liitu*.
- **Playwright strict mode.** `{ name: 'Move' }` also matches "Remove from course" and
  throws. Reach for `exact: true` on short labels.
- **Arm `waitForURL` before `click()`.** Sleeping first and measuring afterwards folds your
  own sleep into the number — that turns an immediate redirect into a false PASS.
  ```js
  const navigated = page.waitForURL(/\/courses\/9006\//)
  const t0 = Date.now()
  await button.click()
  await navigated
  console.log(Date.now() - t0)
  ```
- **Model the state change, not just the response.** Keep the stub's data in a mutable
  `let` and have PATCH/DELETE/POST handlers mutate it, the way query invalidation would in
  production. The 90ms-redirect bug only reproduces if the course list starts empty and
  contains the course *after* the join call. A static stub misses it, and so does an
  assertion that only checks the request was sent.
- **Assert the request body, not just the effect.** `patches.some(p => p.body.replace.student_visible === false)`
  is what proves the row action sends the right thing.
- **Run the check against the unfixed code first.** If it doesn't fail, it isn't testing
  what you think it is.
- **`/v2` is same-origin** in dev (Vite proxies it), so match `**/v2/**`, not a backend host.

## Screenshots are the point

For anything visual, take one and **look at it**. Assertions confirm the DOM; only the
image shows that an icon sits a line lower than its title, that a dashed divider reads as a
divider rather than a drop target, or that two date formats in one view disagree. Capture
light, dark, and mobile at minimum.

For animations, sample several frames (`250 / 700 / 1100 / 1450ms`) — that's how a
staggered entrance is confirmed to be staggered rather than merely present at the end.

That's how one subtle bug turned up: an element kept its entrance animation while an exit
`transition` tried to fade it, so it stayed fully opaque while everything around it left. A
CSS animation with `fill-mode: both` pins the property at its final keyframe and outranks
plain styles, so the transition silently did nothing. The entrance has to step aside:

```tsx
...(joined ? {} : enter(280)),
```

No type checker or lint rule catches that class of bug. A screenshot does, instantly.

## What this does not prove

The auth layer and every API response are fabricated. This validates a page's logic,
timing, and appearance — never that the real endpoints behave as assumed. A run against a
live core is still worth doing before calling something done.
