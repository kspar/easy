# Local Development

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
