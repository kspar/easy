# web — the Lahendus frontend

React + TypeScript + Vite + MUI. Replaced the old Kotlin/JS `wui`, which was deleted 2026-07-31.

For running the whole stack locally (database, core, auth modes) see `../DEVELOPMENT.md`. For
driving pages in a real browser with Keycloak and the backend faked, see
`../doc/web/browser-testing.md`.

```sh
npm install
npm run dev        # http://localhost:5173, proxies /v2 to core on :8080
npm run lint       # fails on errors; there is a known warning backlog (EZ-1722)
npm run build      # tsc -b && vite build
```

## Version stamping

The bundle knows what it is (EZ-1709). `vite.config.ts` injects three constants via `define`, so
they cost no request and cannot disagree with the code around them:

| Constant | From |
| --- | --- |
| `__APP_VERSION__` | the repo-root `VERSION` file, which core and aae read too |
| `__APP_COMMIT__` | `GITHUB_SHA` in CI, `git rev-parse` locally, `unknown` where neither exists |
| `__APP_BUILT_AT__` | build time, ISO |

Declared in `src/build-info.d.ts` and rendered by the Versions block on the About page, which gets
core's and the executors' numbers from `GET /unauth/versions` — an unauthenticated endpoint, so a
reporter who cannot log in can still say what they were running. `package.json` keeps `0.0.0` on
purpose: it is never published, and a second version to bump at release is a second one to forget.

Below it, **only while acting as admin**, an operating panel from `GET /admin/operating-info`
(`@Secured("ROLE_ADMIN")`): uptime, heap, database pool, the Liquibase changeset the schema is on,
grading queue depth per executor, and free disk. Not Spring Actuator — see the reasoning in
`core/ems/service/operating_info.kt`. The hook is disabled for non-admins, so no request is made
that could only 403. Covered by `dev-harness/scripts/about-operating-info.mjs`, which checks both
the admin view and that a teacher neither sees the panel nor calls the endpoint.

### Telling a tab it is out of date

The build writes the same three values to **`dist/version.json`** (EZ-1752), and
`src/api/webVersion.ts` fetches it every five minutes and whenever the tab regains focus. A commit
that differs from `__APP_COMMIT__` means the files behind this tab have been replaced, and
`components/UpdateAvailableBanner.tsx` offers a reload.

Four decisions worth knowing, because each is the answer to a way this goes wrong:

- **It never reloads by itself.** There is a code editor in this app and a student's unsubmitted
  solution lives in the page. Taking that away to install a bug fix would be a worse bug than the
  one being fixed, and an invisible one — the work is just gone.
- **The commit decides, not the version.** A fortnight of deploys off master are all `4.0`.
  Different, not newer: two hashes have no order, and a rollback is a change a tab must hear about
  for the same reasons a roll-forward is.
- **A body that is not a build stamp means "nothing to say".** The SPA fallback answers a request
  for a missing file with `index.html` and a **200**, not a 404, so this is the normal case under
  `vite dev` and on any environment yet to deploy a build that has the file.
- **The request carries a cache-buster as well as `cache: 'no-store'`.** Unlike `config.json` this
  needs no `Cache-Control` from the server, which matters because a cached stamp fails silently —
  it reports "no update" forever and looks exactly like being up to date.

Dismissal is stored per commit in `localStorage`, so waving away today's release says nothing about
next week's. Covered by `dev-harness/unit/web-version.mjs` (the comparison rules) and
`dev-harness/scripts/web-update-banner.mjs` (the wiring and the buttons).

## Runtime configuration

Environment-specific settings are fetched from **`/config.json` at boot**, not baked in at build
time (EZ-1726). One built dist therefore serves every environment, and the artifact CI tested is
the one that gets deployed.

`version.json` is the mirror image of this file and deliberately so: `config.json` differs per
environment and is therefore *stripped* from the artifact, while `version.json` is identical
wherever the artifact goes, which is what makes it a usable build stamp.

`public/config.json` holds the defaults and is what ends up in `dist/`:

```json
{
  "emsRoot": "/v2",
  "keycloak": {
    "url": "https://idp.lahendus.ut.ee/auth/",
    "realm": "master",
    "clientId": "lahendus.ut.ee"
  },
  "environment": { "label": "LOCAL", "colour": "#5c6bc0" }
}
```

- `emsRoot` — base URL for core. A same-origin path (`/v2`) when one reverse proxy fronts both, or
  an absolute URL (`https://dev.ems.lahendus.ut.ee/v2`) when the API is on another host. Core's
  CORS allowlist has to contain the web origin in that case — see `SecurityConf.kt`.
- `keycloak.*` — passed straight to keycloak-js.
- `idpAdminUrl` — optional; where an admin goes to administer the IdP. No value, no menu item.
- `environment` — optional; **absent means production** (EZ-1733). See below.

### Marking a non-production environment

With a dev environment live, people keep several tabs open on the same application, and the mistake worth
preventing — deleting a course on production while believing you are on dev — is made by
clicking the wrong tab. Setting `environment` marks a deployment four ways:

| Where | What |
| --- | --- |
| Beside the wordmark | A small badge in `colour` showing the label, top left, with the "not production" warning as its tooltip and accessible name. Nothing to dismiss. Rendered in the app shell *and* in the landing page's own navbar, which is outside `AppLayout` |
| Tab title | `[DEV] My courses - Lahendus` — leading, because tab titles truncate from the right |
| Favicon | The Lahendus glyph knocked out of a badge in `colour`, built as an SVG data URI at boot |
| Embed footer | `LAHENDUS · DEV`, so an embed pasted into a wiki page says where it came from |

**Production carries no `environment` key and needs no config edit.** That direction is deliberate:
production cannot accidentally acquire a badge, and the rule to learn is the simple one — anything
unusual on the page means this is not production.

`label` carries the meaning on its own, since colour cannot for everyone; it is trimmed and capped
at 16 characters. `colour` must be a hex colour (`#abc` or `#aabbcc`) and defaults to amber if it
is not — it is interpolated into the favicon's SVG, so a value that is not plainly a colour is
replaced rather than escaped. Anything unparseable in the whole key degrades to "production"
rather than to an error page. `src/environment.ts` is where all of it lives;
`dev-harness/scripts/environment-badge.mjs` covers it.

**Deploying** means writing that environment's `config.json` next to `index.html`. Serve it with
`Cache-Control: no-store`: the app already fetches it with `cache: 'no-store'`, but a caching proxy
in front is the one thing that can defeat this whole approach — a stale config.json points a fresh
deploy at the wrong backend.

All four of `emsRoot` and the `keycloak.*` keys are required. If `config.json` is missing,
unparseable, or incomplete, the app renders
a plain "Configuration error" page naming the problem instead of white-screening. That path is
covered by `dev-harness/scripts/runtime-config.mjs`.

### Local overrides

`VITE_*` variables in `.env.local` still override `config.json`, but **only under `npm run dev`** —
`import.meta.env.DEV` guards it, so a production build cannot be pinned to one environment again.
See `.env` for the list.

### Why config loads before the app

`main.tsx` awaits `loadConfig()` and then imports `App.tsx` **dynamically**. `AuthContext.tsx`
constructs its Keycloak instance at module scope, so a static import would evaluate it with an empty
realm. Don't turn that dynamic import into a static one.
