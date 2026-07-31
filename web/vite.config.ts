import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { decodeJwt } from './vite-jwt-proxy.ts'

export default defineConfig({
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
