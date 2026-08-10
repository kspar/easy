import { defineConfig } from 'vite'
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

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(readVersion()),
    __APP_COMMIT__: JSON.stringify(readCommit()),
    __APP_BUILT_AT__: JSON.stringify(new Date().toISOString()),
  },
  plugins: [react()],
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
