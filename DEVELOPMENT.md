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

## 4. Tests

`./gradlew test` needs a config file that is **not** in the repo — copy the sample and fill it in:

```sh
cp core/src/test/resources/application.yaml.sample core/src/test/resources/application.yaml
```

Without it the Spring test context fails to start with
`Could not resolve placeholder 'easy.core.liquibase.changelog'`. It's gitignored on purpose,
same as the main `application.yaml`.

Two things to get right in that file:

- **Point it at a throwaway database, never `easyems`.** `InitTestDatabase` runs Liquibase
  `dropAll()` on the configured datasource at context startup, so the whole schema is wiped
  on every test run. Create a separate one:
  ```sh
  docker exec easy-db-1 psql -U easyems -d postgres -c "create database easyems_test;"
  ```
  then set `jdbc-url: "jdbc:postgresql://localhost:5432/easyems_test"`.
- **`easy.core.liquibase.changelog` is classpath-relative** (`db/changelog.xml`), not a
  filesystem path — it's read through `ClassLoaderResourceAccessor`.

The sample is the authoritative list of required keys; every `${easy.*}` placeholder in
`core/src/main/kotlin` must be present or the context won't start.

### Why tests can't drop a real database

Three independent layers, in order of how much they'd have to fail:

1. **`dropAll()` is in the test source set only.** It is not packaged into the bootJar
   (verifiable: `unzip -l core/build/libs/core-1.jar | grep TestDatabaseConf` finds nothing),
   so no deploy, `bootRun`, or production start can reach it. Deploys migrate through the
   `SpringLiquibase` bean in `core/conf/DatabaseConf.kt`, which only applies pending
   changesets — it never drops.
2. **`assertDisposableDatabase()` fails closed.** Every destructive helper in
   `TestDatabaseConf.kt` refuses to run unless the JDBC URL points at a **local host** and a
   database whose name **ends in `_test`**. A misconfigured `application.yaml` gets a loud
   error instead of a dropped schema. Widen `LOCAL_HOSTS` or `DISPOSABLE_DB_SUFFIX` there only
   deliberately.
3. **The test config is separate from the dev config**, and gitignored, so the dev datasource
   isn't inherited by accident.

Layer 2 exists because layer 3 used to be the only thing protecting the dev database: with no
`core/src/test/resources/application.yaml`, Spring falls back to the **main** `application.yaml`
— which points at `easyems`. Tests failed only because that file happens to lack
`easy.core.liquibase.changelog`. Adding that one key would have been enough to make
`./gradlew test` wipe the dev database.

## 5. Mock Executor

A lightweight Node server that pretends to be an auto-assessment executor. No dependencies required.

```sh
node mock-executor/server.mjs
```

Open http://localhost:5111 to configure the grade, feedback, and delay it returns. The test data Liquibase changeset registers this executor automatically (`http://localhost:5111`, container image `mock`).
