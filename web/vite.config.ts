import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { decodeJwt } from './src/utils/jwt-proxy.ts'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/v2': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          // Mimic Apache OIDC reverse proxy: extract JWT claims from
          // Authorization header and set them as oidc_claim_* headers
          proxy.on('proxyReq', (proxyReq, req) => {
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
