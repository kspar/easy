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

## 4. Mock Executor

A lightweight Node server that pretends to be an auto-assessment executor. No dependencies required.

```sh
node mock-executor/server.mjs
```

Open http://localhost:5111 to configure the grade, feedback, and delay it returns. The test data Liquibase changeset registers this executor automatically (`http://localhost:5111`, container image `mock`).
