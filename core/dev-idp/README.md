# Fake IdP

A throwaway OIDC signing key and a JWKS endpoint, for checking that core's JWT verification
accepts what it should and rejects what it shouldn't. Used to verify EZ-1724.

This exercises the **real** production auth path — `NimbusJwtDecoder` fetching a real JWKS over
HTTP and validating a real RS256 signature — which the unit tests in
`core/src/test/kotlin/core/conf/security/` deliberately don't: they cover claim mapping with no
Spring context so they can run in CI.

It is not a login provider. There is no authorization endpoint and no login form, so keycloak-js
cannot redirect to it and the browser flow can't be driven from here. For that, either point the
web app at a real IdP (see "Running local dev against a real IdP" in `DEVELOPMENT.md`) or use the
`oidc_claim_*` header path in `doc/core/api-testing.md`.

## Use

```sh
node core/dev-idp/fake-idp.mjs
```

Then start core against it, on a port that isn't the 8080 your normal dev instance holds:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:bootRun --args="\
  --server.port=8099 \
  --easy.core.auth-enabled=true \
  --spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:5199/realms/test/protocol/openid-connect/certs \
  --spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:5199/realms/test"
```

`tokens.json` appears next to this file. Pull one out and call core with it:

```sh
TOKEN=$(node -e "console.log(require('./core/dev-idp/tokens.json').valid)")
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8099/v2/teacher/courses
```

Tokens are minted at startup with a one-hour expiry. Restart for fresh ones.

## What each token should do

| Token | Expected |
| --- | --- |
| `valid` | 200 on teacher endpoints, 403 on admin ones |
| `validAdmin` | 200 on admin endpoints |
| `validStudent` | 200 on student endpoints, 403 on teacher ones |
| `stringRole` | 200 — `easy_role` as a bare string is accepted |
| `badSignature` | 401 — right `kid`, wrong key |
| `expired` | 401 |
| `wrongIssuer` | 401 |
| `noUsername` / `noEmail` / `noRoles` | 401, naming the missing claim in core's log |
| `unmappedRole` | 401, not 500 |

Two more worth checking by hand, since they are the point of the change:

```sh
# No credentials at all -> 401
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8099/v2/teacher/courses

# Forged claim headers, no token -> 401. Before EZ-1724 this was a working admin login.
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'oidc_claim_preferred_username: kspar' \
  -H 'oidc_claim_email: k@e.ee' \
  -H 'oidc_claim_easy_role: admin' \
  http://localhost:8099/v2/teacher/courses
```

## Note

`tokens.json` is gitignored. The key is generated fresh on every start and never leaves the
machine, so nothing here is a credential — but it is also not reproducible between runs, which
is why the file isn't committed.
