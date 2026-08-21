# Local Development

## 0. Prerequisites

- **JDK 25** — the backend will not build on anything older. Gradle itself also needs 17+.
- Node 20+ and Docker for the frontend and database.

If your default `JAVA_HOME` points somewhere else, prefix the Gradle commands below:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew bootRun
```

## 1. Database

Start PostgreSQL:

```sh
docker compose up db
```

To rebuild from scratch (wipes all data, re-runs Liquibase migrations + test data):

```sh
docker compose down db -v
docker compose up db
```

## 2. Backend

```sh
./gradlew bootRun
```

Runs on port 8080 by default. Liquibase migrations run automatically on startup.

Stack: Spring Boot 4.1, Kotlin 2.3, Exposed 1.3, Jackson 3, on JDK 25. If you're
writing backend code, the package names moved during the Java 25 migration — see
`doc/java-25-migration.md`.

## 3. Frontend

```sh
cd web
npm install
npm run dev
```

Runs on http://localhost:5173.

## 4. Auth: which of the two modes you're in

Core has two ways to learn who is calling, and it's worth knowing which one you're running.

### Headers, no IdP (the default for local dev)

`easy.core.auth-enabled: false` installs `DummyZeroAuthFilter`, which reads `oidc_claim_*` request
headers and believes them. That's what makes curl-as-anyone work (`doc/core/api-testing.md`), and
the Vite dev proxy fabricates the same headers from the SPA's token so the browser works too. No
network, no IdP, no token expiry.

It also means anything that can reach the port is any user it likes, admin included. Core therefore
**refuses to start** in this mode unless `server.address` is a loopback address — so keep

```yaml
server:
  address: 127.0.0.1
```

in your `application.yaml`. The sample has it. If you're upgrading an older local config that
omitted it, add it, or you'll get an `IllegalStateException` naming this exact fix on next start.

### Real tokens, real IdP (what deployed environments do)

Since EZ-1724 core verifies Keycloak access tokens itself, so a local core can run the production
auth path with no extra infrastructure — the SPA already logs into a real IdP by default
(`web/public/config.json`, overridable per-developer via `VITE_*` in `web/.env.local`; see
`web/README.md`). Set in `application.yaml`:

```yaml
easy:
  core:
    auth-enabled: true

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: "https://idp.lahendus.ut.ee/auth/realms/master/protocol/openid-connect/certs"
          issuer-uri: "https://idp.lahendus.ut.ee/auth/realms/master"
```

Then the Vite proxy's header translation becomes redundant — harmless, since core ignores those
headers in this mode — and you are logged in as your real self, with whatever roles the IdP gives
you. Worth doing when touching anything auth-shaped, since it's the only local setup that catches
claim-mapping and realm problems. Needs network access to the IdP.

To test verification *failures* — expired tokens, bad signatures, unmapped roles — use the fake IdP
in `core/dev-idp/`, which mints deliberately broken tokens on demand.

## 5. Tests

```sh
./gradlew test
```

That's it — no setup. The suite starts its own PostgreSQL through Testcontainers, so it needs a
Docker daemon (the same one `docker compose up db` already wants) and nothing else. The config it
uses, `core/src/test/resources/application.yaml`, is committed.

Both of those are recent. Until 2026-08 the config was gitignored and hand-written per machine and
there was no database in CI, so the database-backed tests ran in neither place — which is how two
of them came to fail four runs in five without anyone noticing (EZ-1763).

Useful variations:

```sh
./gradlew :core:test --tests '*LatestSubmissions*'    # one class
EASY_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/easyems_test ./gradlew :core:test
```

`./gradlew test` is the JVM modules only. There are five more suites — web unit, web browser, `aae`,
the pins parser and the grading-image reconciler — each with its own runner, listed in
`doc/testing.md`. To see all of them at once:

```sh
bin/testcounts          # how big each suite is
bin/testcounts --run    # run every suite and report what it says
```

`EASY_TEST_JDBC_URL` skips Docker and uses a database you made yourself — handy when you want to
`psql` into it afterwards and look. Create it with:

```sh
docker exec easy-db-1 psql -U easyems -d postgres -c "create database easyems_test;"
```

The name has to end in `_test`; see below.

### Writing a database-backed test

Annotate the class `@IntegrationTest` (`core/testing/IntegrationTest.kt`) and build rows with
`Fixtures` (`core/testing/Fixtures.kt`). Every table is emptied before each test, so tests are
independent and order-free.

Two rules that are enforced rather than advisory:

- **Do not add `@TestPropertySource`, `@MockitoBean` or `@ActiveProfiles` to a test class.** Spring
  caches one context per distinct configuration, so each of those forks another one at ~10s. One
  context for the whole suite is what keeps this fast enough to gate a deploy.
- **Do not call `DateTime.now()` in a test.** Use `TestClock`. `NoWallClockInFixturesTest` fails the
  build otherwise. Two rows written from the wall clock can share a millisecond, and a test that
  depends on which one won is a test that fails a few runs in five — which is exactly what EZ-1763
  was.

`core/src/test/resources/application.yaml` is the authoritative list of required keys: every
`${easy.*}` placeholder in `core/src/main/kotlin` must be present or the context won't start. That
assertion now runs on every push, which is the reason to commit the file rather than sample it.

### Why tests can't wipe a real database

Emptying every table between tests is as destructive as the `dropAll()` it replaced, so the guard
from EZ-1717 is still there. Three independent layers, in order of how much would have to fail:

1. **It's in the test source set only.** Not packaged into the bootJar — verifiable with
   `unzip -l core/build/libs/core-*.jar | grep -i truncate` finding nothing — so no deploy,
   `bootRun` or production start can reach it. Deploys migrate through the `SpringLiquibase` bean in
   `core/conf/DatabaseConf.kt`, which only applies pending changesets and never drops.
2. **`assertDisposableDatabase()` fails closed.** `truncateAll()` in
   `core/testing/DatabaseReset.kt` refuses to run unless the JDBC URL names a **local host** and a
   database whose name **ends in `_test`**. Pinned by `DisposableDatabaseGuardTest`. Widen
   `LOCAL_HOSTS` or `DISPOSABLE_DB_SUFFIX` only deliberately.
3. **The default target is a throwaway container**, not any database on your machine. Reaching a
   database you care about takes an explicit `EASY_TEST_JDBC_URL`, and then layer 2 still has to
   agree.

Layer 2 exists because the arrangement it replaced was thinner than it looked: with no test
`application.yaml`, Spring fell back to the **main** one, which points at `easyems`. Tests failed
only because that file happens to lack `easy.core.liquibase.changelog` — adding that one key would
have been enough to make `./gradlew test` wipe the dev database.

## 6. Mock Executor

A lightweight Node server that pretends to be an auto-assessment executor. No dependencies required.

```sh
node mock-executor/server.mjs
```

Open http://localhost:5111 to configure the grade, feedback, and delay it returns. The test data Liquibase changeset registers this executor automatically (`http://localhost:5111`, container image `mock`).
