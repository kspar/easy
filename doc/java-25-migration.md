# Java 11 → 25 Migration

**Status: done.** Merged to master in July 2026 (EZ-1615, branch `java25`, by priit). This
document was the plan; it is now the record. If you are looking for what the stack *is*, read
the first two sections and stop.

## Where we landed

| | Before | After |
|---|---|---|
| Java | 11 | **25** (toolchain `JavaLanguageVersion.of(25)`) |
| Kotlin | 1.5.31 / 1.7.20 / 1.8.0 / 1.8.22 across modules | **2.3.10**, unified |
| Spring Boot | 2.7.17 | **4.1.0** |
| Exposed | 0.48.0 | **1.3.1** |
| Jackson | 2.x (`com.fasterxml`) | **3.x** (`tools.jackson`) |
| Gradle | 7.6.3 | **9.6.1** |
| Liquibase | 3.6.3 | **4.31.1** |
| kotlin-logging | 1.7.10 (`io.github.microutils`) | **8.0.01** (`io.github.oshai`) |
| AsciidoctorJ | 2.4.3 | 3.0.1 |
| Apache Tika | 1.25 | 3.2.3 |

## Idioms that changed — read this before writing backend code

These are repo-wide. Getting them wrong produces errors that look like missing dependencies
rather than renamed packages, which is slow to diagnose. Copy the import block from a
recently-touched service rather than working from memory.

- **Validation**: `javax.validation.*` → `jakarta.validation.*`.
  (`javax.sql.DataSource` stays — it's JDK, not Jakarta EE.)
- **Logging**: `mu.KotlinLogging` → `io.github.oshai.kotlinlogging.KotlinLogging`.
- **Exposed** split into two artifacts, and you need imports from both:
  - `org.jetbrains.exposed.v1.core.*` — `Table`, `Column`, `SortOrder`, `and`, `eq`, `dao.id.*`
  - `org.jetbrains.exposed.v1.jdbc.*` — `select`, `selectAll`, `insert`, `insertAndGetId`,
    `update`, `deleteWhere`, `transactions.transaction`
  - Joda columns: `org.jetbrains.exposed.v1.jodatime.datetime`
- **Jackson 3**: annotations stay at `com.fasterxml.jackson.annotation.*`, everything else
  moves to `tools.jackson.*` (e.g. `tools.jackson.databind.annotation.JsonSerialize`).
  Annotation targets now matter on Kotlin data classes:
  - request DTOs → **`@param:JsonProperty`**
  - response DTOs → **`@get:JsonProperty`**

  Bare `@JsonProperty` is not reliable under Jackson 3 + Kotlin.
- **Spring Security 6**: `WebSecurityConfigurerAdapter` is gone; config is a
  `@Bean SecurityFilterChain`. `antMatchers()` → `requestMatchers()`,
  `authorizeRequests()` → `authorizeHttpRequests()`, `@EnableGlobalMethodSecurity` →
  `@EnableMethodSecurity`.

## Liquibase

Several historical changesets in `v2.xml` and `v3.xml` had checksum drift and trailing-whitespace
problems that Liquibase 4 refuses to tolerate. They now carry `<validCheckSum>any</validCheckSum>`
plus `MARK_RAN` preconditions, so existing databases migrate forward and fresh ones build clean.

**Do not "fix" a checksum mismatch by editing an old changeset.** Add a new one.

## Where the plan was wrong

Kept for calibration on the next cascade upgrade:

- The plan targeted **Spring Boot 3.5.x**; we went to **4.1.0**. Jackson 3 came along with it,
  which the plan did not anticipate at all — that was the single largest source of churn,
  touching every request and response DTO in the codebase.
- The plan targeted **Exposed 0.54.0**, explicitly deferring 1.0 because of "massive package
  renames". We took the renames anyway and went to **1.3.1**. Deferring would have meant doing
  it twice.
- The plan wanted Gradle **8.14.4 as an intermediate step** before 9.x. That didn't happen;
  the wrapper went straight to 9.6.1.
- Effort was estimated at **10–15 days**. Actual elapsed span of the branch was roughly five
  months of intermittent work (late February to early July 2026), though not full-time.

The lesson that generalises: when a dependency upgrade is forced by a language-version bump,
the transitive blast radius is consistently underestimated, and "defer the big rename to a
separate step" tends to be false economy.

## Follow-on work this unblocked

- **JPlag integration** (`doc/jplag-integration.md`) needed JDK 17+ and was the original
  motivation for the whole migration. No longer blocked.
