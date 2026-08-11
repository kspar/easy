import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { decodeJwt } from './vite-jwt-proxy.ts'

/**
 * Version stamping (EZ-1709).
 *
 * The version comes from the repo-root `VERSION` file, which core and aae read too — one number for
 * the product rather than three that can disagree. `web/package.json` deliberately keeps its
 * meaningless `0.0.0`: it is not published to npm, and a second place to bump at release time is a
 * second place to forget.
 *
 * The commit is what tells you which *build* you are looking at, since several builds share a
 * version. `GITHUB_SHA` is the authority in CI; locally it asks git, and where neither exists it
 * says "unknown" rather than failing a build over a diagnostic string.
 */
function readVersion(): string {
  try {
    return readFileSync(new URL('../VERSION', import.meta.url), 'utf8').trim() || 'unknown'
  } catch {
    return 'unknown'
  }
}

function readCommit(): string {
  const fromCi = process.env.GITHUB_SHA
  if (fromCi) return fromCi.slice(0, 7)
  try {
    return execFileSync('git', ['rev-parse', '--short=7', 'HEAD'], { encoding: 'utf8' }).trim()
  } catch {
    return 'unknown'
  }
}

/**
 * The build stamp, read once so that the constants compiled into the bundle and the manifest
 * written beside it can never disagree — which is the whole basis of the update check in
 * `src/api/webVersion.ts`: it compares one against the other.
 */
const BUILD = {
  version: readVersion(),
  commit: readCommit(),
  builtAt: new Date().toISOString(),
}

/**
 * Writes `version.json` into the dist (EZ-1752).
 *
 * A running tab keeps the bundle it loaded until someone reloads it, so it needs a way to ask what
 * is deployed *now*. Core cannot answer that — web is deployed as static files and often changes
 * when core does not — so the dist describes itself, and the check is one static fetch with no
 * backend involved.
 *
 * Emitted by the build rather than written by the deploy script: an artifact that carries its own
 * identity works the same on every environment and needs nothing added to any deploy. It is the
 * opposite decision to `config.json`, which is deliberately *removed* from the artifact because it
 * differs per environment (EZ-1726). This file is the same everywhere the artifact goes, which is
 * exactly what makes it a build stamp.
 *
 * Build only: under `vite dev` there is no build to identify, and no file — the request falls
 * through to the SPA's index.html and the client treats the HTML it gets back as "nothing to say".
 */
function versionManifest(): Plugin {
  return {
    name: 'easy-version-manifest',
    apply: 'build',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'version.json',
        source: JSON.stringify(BUILD, null, 2) + '\n',
      })
    },
  }
}

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(BUILD.version),
    __APP_COMMIT__: JSON.stringify(BUILD.commit),
    __APP_BUILT_AT__: JSON.stringify(BUILD.builtAt),
  },
  plugins: [react(), versionManifest()],
  server: {
    proxy: {
      '/v2': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          // Translate the SPA's bearer token into the oidc_claim_* headers that a core
          // running with easy.core.auth-enabled=false expects, so browser-based local dev
          // needs no IdP at all. Deployed environments no longer work this way: core
          // verifies the JWT itself (see core/conf/security/EasyUserJwtConverter.kt), and
          // Apache in front of it is a plain reverse proxy that passes Authorization
          // through untouched. Point a local core at an IdP with auth-enabled=true and
          // this translation becomes unnecessary.
          proxy.on('proxyReq', (proxyReq, req) => {
            // Remove Origin header so backend CORS filter doesn't reject
            // requests from the Vite dev server (localhost:5173)
            proxyReq.removeHeader('origin')

            const authHeader = req.headers['authorization']
            if (!authHeader?.startsWith('Bearer ')) return

            const token = authHeader.slice(7)
            try {
              const claims = decodeJwt(token)
              proxyReq.setHeader('oidc_claim_preferred_username', claims.preferred_username ?? '')
              proxyReq.setHeader('oidc_claim_email', claims.email ?? '')
              proxyReq.setHeader('oidc_claim_given_name', claims.given_name ?? '')
              proxyReq.setHeader('oidc_claim_family_name', claims.family_name ?? '')
              const easyRole = Array.isArray(claims.easy_role)
                ? claims.easy_role.join(',')
                : (claims.easy_role ?? '')
              proxyReq.setHeader('oidc_claim_easy_role', easyRole)
              proxyReq.setHeader('OIDC_access_token', token)
            } catch {
              // invalid token, let backend handle it
            }
          })
        },
      },
    },
  },
})
