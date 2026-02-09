/**
 * Decode a JWT payload without verification (for dev proxy only).
 * This runs in the Vite dev server (Node), not in the browser.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function decodeJwt(token: string): Record<string, any> {
  const payload = token.split('.')[1]
  if (!payload) return {}
  const json = Buffer.from(payload, 'base64url').toString('utf-8')
  return JSON.parse(json)
}