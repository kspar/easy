// A fake IdP for exercising core's JWT verification by hand. No dependencies; Node 20+.
//
//   node core/dev-idp/fake-idp.mjs
//
// Serves a JWKS at /realms/test/protocol/openid-connect/certs and writes tokens.json next to
// this file — one valid token plus a set of deliberately broken ones. See README.md.
import { createServer } from 'node:http'
import { generateKeyPairSync, createSign, randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'

const PORT = Number(process.env.PORT ?? 5199)
const ISSUER = `http://localhost:${PORT}/realms/test`

const keypair = () => generateKeyPairSync('rsa', { modulusLength: 2048 })
const real = keypair()
// Same kid as the published key, different private key: proves core checks the signature
// rather than trusting the header's key id.
const rogue = keypair()

const b64 = (buf) => Buffer.from(buf).toString('base64url')

function sign(key, claims) {
  const header = { alg: 'RS256', typ: 'JWT', kid: 'dev-idp-key' }
  const input = `${b64(JSON.stringify(header))}.${b64(JSON.stringify(claims))}`
  const sig = createSign('RSA-SHA256').update(input).sign(key.privateKey)
  return `${input}.${b64(sig)}`
}

const now = Math.floor(Date.now() / 1000)
const claims = (extra) => ({
  iss: ISSUER,
  sub: randomUUID(),
  iat: now,
  exp: now + 3600,
  preferred_username: 'dev-teacher',
  email: 'teacher@test.ee',
  given_name: 'Mari',
  family_name: 'Maasikas',
  easy_role: ['teacher'],
  ...extra,
})

// Each entry is a case core must handle. Expected results are in README.md.
const tokens = {
  valid: sign(real, claims({})),
  validAdmin: sign(real, claims({ preferred_username: 'kspar', easy_role: ['student', 'teacher', 'admin'] })),
  validStudent: sign(real, claims({ preferred_username: 'dev-student', email: 'student@test.ee', easy_role: ['student'] })),
  // easy_role as a bare string instead of an array
  stringRole: sign(real, claims({ easy_role: 'student' })),
  // Correctly formed, signed by a key that is not the published one
  badSignature: sign(rogue, claims({})),
  expired: sign(real, claims({ iat: now - 7200, exp: now - 3600 })),
  wrongIssuer: sign(real, claims({ iss: `http://localhost:${PORT}/realms/somewhere-else` })),
  noUsername: sign(real, (({ preferred_username, ...rest }) => rest)(claims({}))),
  noEmail: sign(real, (({ email, ...rest }) => rest)(claims({}))),
  noRoles: sign(real, (({ easy_role, ...rest }) => rest)(claims({}))),
  unmappedRole: sign(real, claims({ easy_role: ['wizard'] })),
}

const tokensPath = new URL('./tokens.json', import.meta.url)
writeFileSync(tokensPath, `${JSON.stringify(tokens, null, 2)}\n`)

const jwks = () => {
  const { n, e } = real.publicKey.export({ format: 'jwk' })
  return { keys: [{ kty: 'RSA', use: 'sig', alg: 'RS256', kid: 'dev-idp-key', n, e }] }
}

createServer((req, res) => {
  if (req.url?.includes('/certs')) {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify(jwks()))
  } else {
    res.writeHead(404).end()
  }
}).listen(PORT, () => {
  console.log(`Fake IdP listening on ${PORT}`)
  console.log(`  issuer:   ${ISSUER}`)
  console.log(`  jwks:     ${ISSUER}/protocol/openid-connect/certs`)
  console.log(`  tokens:   ${tokensPath.pathname}`)
  console.log(`\nTokens expire in an hour; restart to mint fresh ones.`)
})
