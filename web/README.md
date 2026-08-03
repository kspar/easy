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

## Runtime configuration

Environment-specific settings are fetched from **`/config.json` at boot**, not baked in at build
time (EZ-1726). One built dist therefore serves every environment, and the artifact CI tested is
the one that gets deployed.

`public/config.json` holds the defaults and is what ends up in `dist/`:

```json
{
  "emsRoot": "/v2",
  "keycloak": {
    "url": "https://idp.lahendus.ut.ee/auth/",
    "realm": "master",
    "clientId": "lahendus.ut.ee"
  }
}
```

- `emsRoot` — base URL for core. A same-origin path (`/v2`) when one reverse proxy fronts both, or
  an absolute URL (`https://dev.ems.lahendus.ut.ee/v2`) when the API is on another host. Core's
  CORS allowlist has to contain the web origin in that case — see `SecurityConf.kt`.
- `keycloak.*` — passed straight to keycloak-js.

**Deploying** means writing that environment's `config.json` next to `index.html`. Serve it with
`Cache-Control: no-store`: the app already fetches it with `cache: 'no-store'`, but a caching proxy
in front is the one thing that can defeat this whole approach — a stale config.json points a fresh
deploy at the wrong backend.

All four keys are required. If `config.json` is missing, unparseable, or incomplete, the app renders
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
