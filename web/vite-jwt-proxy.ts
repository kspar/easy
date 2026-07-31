/**
 * Decode a JWT payload without verification (for dev proxy only).
 *
 * Runs in the Vite dev server (Node), not in the browser — which is why it sits next to
 * vite.config.ts rather than under src/. Only tsconfig.node.json pulls it in, and that is
 * the project configured with Node types, so `Buffer` resolves. Under src/ it was compiled
 * by tsconfig.app.json too, which has no Node types.
 */

/** The claims the dev proxy forwards as oidc_claim_* headers. */
export interface JwtClaims {
  preferred_username?: string
  given_name?: string
  family_name?: string
  email?: string
  easy_role?: string | string[]
  [claim: string]: unknown
}

export function decodeJwt(token: string): JwtClaims {
  const payload = token.split('.')[1]
  if (!payload) return {}
  const json = Buffer.from(payload, 'base64url').toString('utf-8')
  return JSON.parse(json) as JwtClaims
}
