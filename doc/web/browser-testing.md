# Driving the React web in a browser without a backend or an IdP

For the backend equivalent — calling core with curl and A/B-ing two builds — see
`doc/core/api-testing.md`.

This is how to exercise a page end to end — real router, real components, real MUI, real
react-query — with Keycloak and every API response faked. All of it runs in CI, on every push, as
part of the gate a deploy is allowed to act on. It has caught bugs that reading the code did not:

- an SVG that silently never rendered, because `sx={{ stroke: 'primary.main' }}` is not a
  resolvable theme token and produced no stroke at all
- a redirect that fired 90ms after a click instead of the intended 800ms, from a code path
  unrelated to the animation it broke
- a selection checkbox that never toggled, because `preventDefault()` (needed to stop the
  row's `<a>` from navigating) also cancels the checkbox's own state change, so `onChange`
  never fired
- a `<button>` nested inside MUI's `AccordionSummary` (itself a button) — invalid HTML that
  only announces itself as a runtime console error
- three `Select`s with no accessible name at all, because MUI only wires `InputLabel` to
  `Select` when both carry ids

All three were invisible in the source and obvious in a screenshot or a timing number.

## The harness is checked in — don't rebuild it

Everything lives in **`web/tests/`** and is committed. Nothing needs to be created or deleted per
session.

```
web/playwright.config.ts         the browser suite: workers, timeouts, the stub dev server
web/vitest.config.ts             the unit suite
web/vite.stub.config.ts          dev server with keycloak-js aliased to the stub
web/tests/
  browser/*.spec.mjs             one spec per page under test, plus runtime-config
                                 which covers boot-time config loading and its
                                 failure screens
  unit/*.test.mjs                pure functions, no browser (see "Unit tests" below)
  support/
    keycloak-stub.js             fake IdP; role comes from localStorage.stubRole
    harness.mjs                  launch() / fakeApi() / json() / waitUntil()
    spec.mjs                     the `test` every spec imports: check(), the ratchets
    contract.mjs                 fixtures vs doc/core/api-shapes.json
    quarantine.mjs               the rules for muting a check, and their expiry
  expected-checks.json           per-spec check counts — the ratchet
  contract-baseline.json         per-spec allowed fixture drift
  quarantine.json                temporary, expiring exemptions (normally empty)
  screenshots/                   output (gitignored)
```

`web/dev-harness/` is gone: it had its own `package.json`, its own lockfile and its own dependabot
entry, all for two dependencies. `@playwright/test` and `vitest` are devDependencies of
`web/package.json` now, so `npm ci` in `web/` is the whole install.

Playwright drives the Chrome that's already installed, so there's no 130MB browser download on a
laptop. CI pins the browser instead — see below.

## Running

```sh
cd web
npm run test:browser                    # everything, what CI runs
npx playwright test grade-table         # one spec; the filter is a path substring
npx playwright test --ui                # pick, watch, and time-travel through them
npx playwright test --headed            # watch it happen in a real window
npx playwright test --debug             # step through with the inspector
```

Playwright starts and stops the stub dev server itself, so there is no second terminal any more.
Each spec still prints a `✅`/`❌` line per check and a `📷` line per screenshot.

**Run a filtered subset while iterating.** The full suite takes minutes and CI runs all of it on
every push, so re-running everything locally after each edit buys very little and costs real
waiting. Run the spec covering what you changed; let CI catch the rest.

There are no per-spec npm aliases to keep in step any more, and no list of scripts to add to.
`npx playwright test <substring>` is the framework's own filter and cannot go stale, and every
`*.spec.mjs` under `tests/browser/` runs by default — which is the point: the old runner named its
27 scripts explicitly, and the 28th on disk quietly ran nowhere for months.

### Specs that need a real backend

`library-exercise-tsl-live.spec.mjs` relays `/v2/tsl/compile` to a core on :8080 to prove the TSL
JSON the visual editor produces actually decodes on the Kotlin side — kotlinx.serialization
runs with `ignoreUnknownKeys = false`, so a stub cannot answer that question. It needs
`easy.core.auth-enabled: false` and the `tiivad:tsl-compose` container image registered and
attached to an executor (`POST /v2/container-images`, then `PUT /v2/executors/{id}`).

It skips itself unless asked for:

```sh
HARNESS_LIVE=1 npx playwright test tsl-live
```

Skipped rather than excluded, so every run lists it — being invisible is how the old suite lost
track of it.

### In CI

The browser is Playwright's own chromium rather than the runner's Chrome, so the version is
pinned by the `@playwright/test` dependency: CI sets `HARNESS_BROWSER_CHANNEL=''` and runs
`npx playwright install --with-deps chromium`. Locally the variable is unset and the suite
drives the Chrome you already have.

On failure CI uploads the HTML report, the screenshots, and a **trace** per spec — the
time-travelling recording of everything the browser did. Open one with
`npx playwright show-trace path/to/trace.zip`. Tracing is on by default under CI and off by default
locally (`PW_TRACE=1` / `PW_TRACE=0` to force it), because on a laptop a failure can be reproduced
by re-running one spec and on a runner it cannot be reproduced at all.

**`retries` is 0 and should stay 0.** A retry converts an intermittent failure into a green build
plus a log line nobody reads, and the flakes this suite has are mostly *product* timing bugs — the
90ms redirect, a poll that stops, refetch races — exactly the class a retry hides. The escape hatch
is quarantine, below.

## The three things that keep a green run meaningful

A browser test that stops early looks exactly like one that passed. An early return, a swallowed
locator error, a `waitFor` that throws past the remaining assertions — every one of those is green
without help. So three counts are ratcheted, all enforced from `check`:

- **`expected-checks.json`** — how many checks each spec must report. Fewer fails the build.
  Regenerate on a **green** suite with `npm run test:browser:record`, and if a number goes down,
  say why in the commit.
- **`contract-baseline.json`** — how much each spec's fixtures may disagree with
  `doc/core/api-shapes.json`. The number may fall, never rise.
- **`quarantine.json`** — normally `[]`. An entry mutes one check, by exact label, and needs a
  `spec`, `label`, `issue`, `note` and an `expires` at most 14 days out. An expired entry, a
  missing field, or an entry whose label no longer matches anything **fails the build**. Quarantine
  a check, never a spec — muting a whole file removes coverage nobody is counting.

`tests/unit/suite-integrity.test.mjs` checks the bookkeeping itself in milliseconds: an expired
quarantine entry or a spec nobody is counting fails there, before a browser starts.

## Unit tests

`tests/unit/*.test.mjs`, run by vitest:

```sh
cd web
npm run test:unit
npx vitest --watch          # while iterating
npx vitest --coverage       # report-only, no threshold
```

They import the TypeScript sources directly — vitest resolves them, which is why the old runner's
esbuild bundling step is gone.

**Which logic goes where.** Anything decidable from values alone — a merge rule, a comparator, a
formatter, a parser — belongs here, and belongs in a plain module extracted out of the component so
it can be. Anything needing layout, the cascade, focus, or the router goes in `tests/browser/`.

**There are deliberately no jsdom component tests.** The bugs this app actually ships need a real
browser: an unresolvable `sx` token producing no stroke, `animation-fill-mode` outranking a
transition, MUI only wiring `InputLabel`→`Select` when both carry ids, a `<button>` nested inside
another. jsdom catches none of those while duplicating the browser suite at lower fidelity.

## Why the app can't just be pointed at a fake IdP

`AuthProvider` does `new Keycloak(...)` from an ES module import, so it can't be replaced
from `window` in an init script — hence the build-time alias. Everything else is easier
than it looks: the dev server proxies `/v2` to `localhost:8080`, so Playwright's route
interception, which happens inside the browser *before* the proxy, stands in for the whole
backend.

## Writing a new spec

Drop a `*.spec.mjs` in `tests/browser/` and it runs. A minimal one:

```js
import { test } from '../support/spec.mjs'
import { fakeApi, json, BASE_URL } from '../support/harness.mjs'

test('my-page', async ({ launch, check }) => {
  const { page, shot, close } = await launch({ role: 'teacher,admin', shotPrefix: 'my-page-' })

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

  await close()
})
```

`check()` records and keeps going, so one run reports every problem rather than the first — it is
`expect.soft` underneath. Once the spec is green, run `npm run test:browser:record` so it is
ratcheted like the others; until then the run prints a note saying it is not.

`launch()` options: `role`, `language` (default `'en'`), `theme`, `colorScheme`, `viewport`,
`reducedMotion`, `shotPrefix`. It also forwards browser console errors and page errors to
stdout, which is how you notice a broken query without asserting on it. Call it more than once for
a spec that needs a second visitor or a dark-mode pass; `close()` closes that context, and anything
you forget is closed for you.

**One test per spec file** is the convention, not a rule. Splitting a spec into several `test()`
blocks buys parallelism inside the file — but the specs share a screenshot prefix and a single
check count, so do it deliberately rather than by habit.

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
- **Poll, don't sleep.** `waitUntil(() => ...)` from `harness.mjs` replaces
  `waitForTimeout` + assert. A sleep tuned on a laptop is a coin flip on a loaded runner,
  and when it loses the failure reads like a product bug. The one legitimate use of a fixed
  sleep is asserting that something *doesn't* happen — and even then, settle to quiescence
  before starting the clock, or you'll count work that was already in flight.
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
