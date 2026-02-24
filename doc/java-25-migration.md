# Java 11 → 25 Migration

Prerequisite for JPlag integration (requires JDK 17+). Moving straight to 25 since that's what's installed locally and avoids another migration later.

**This is a cascade upgrade** — Java 25 forces upgrades across the entire stack. Recommended order:

## Phase 1: Kotlin unification + upgrade

The project currently has 4 different Kotlin versions across modules:
- `core`: 1.8.22 (from `core/gradle.properties`)
- `tsl-common`: 1.8.0 multiplatform (from root `build.gradle.kts`)
- `tsl`: 1.5.31 JVM (from root `build.gradle.kts`)
- `wui`/`ezspa`: 1.7.20 JS (from root `build.gradle.kts`)

The Kotlin/JS modules (`wui`, `ezspa`) are legacy — the new React frontend replaces them. They can be excluded from the build or removed entirely, which simplifies this significantly.

**Target: Kotlin 2.3.0** (first version with JDK 25 bytecode support). Key changes:
- K2 compiler enabled by default (stricter type checking — may surface compile errors)
- `kotlinOptions {}` DSL deprecated → use `compilerOptions {}`
- `kotlin-stdlib-jdk8` merged into `kotlin-stdlib` (remove explicit dep in `core/build.gradle`)
- Upgrade `kotlinx-serialization-json` from 1.5.0 to 1.10.x (in `tsl`, `tsl-common`)

## Phase 2: Spring Boot 2.7.17 → 3.5.x

Spring Boot 2.7 only supports Java 8/11/17 and is EOL.

**Jakarta namespace migration** (`javax.*` → `jakarta.*`):
- ~66 files, ~157 import statements — mechanical find-and-replace
- Affects: `javax.servlet.*`, `javax.validation.*`, `javax.annotation.PostConstruct`
- **Exception**: `javax.sql.DataSource` stays (it's part of the JDK, not Jakarta EE)

**Spring Security 6 rewrite** (`core/src/main/kotlin/core/conf/security/SecurityConf.kt`):
- `WebSecurityConfigurerAdapter` removed — rewrite as `@Bean SecurityFilterChain`
- `@EnableGlobalMethodSecurity` → `@EnableMethodSecurity`
- `antMatchers()` → `requestMatchers()`
- `http.authorizeRequests()` → `http.authorizeHttpRequests()`

**Other Spring Boot 3 changes**:
- Trailing slash matching disabled by default (may affect existing clients)
- Various `spring.*` property renames (use `spring-boot-properties-migrator` to detect)

## Phase 3: Exposed ORM 0.48.0 → 0.54.0+

Exposed 0.48 won't compile with Kotlin 2.x. Version 0.54.0 is the first Kotlin 2.0-compatible release with minimal API changes. (1.0.0 exists but has massive package renames — do as a separate step if desired.)

## Phase 4: Other dependency upgrades

| Dependency | Current | Target | Why |
|-----------|---------|--------|-----|
| `kotlin-logging` | 1.7.10 (`io.github.microutils`) | 7.0.x (`io.github.oshai:kotlin-logging`) | Package renamed |
| AsciidoctorJ | 2.4.3 | 2.5.13+ | JRuby reflective access blocked by JDK 17+ |
| HikariCP | 3.4.5 (explicit) | Remove explicit dep | Spring Boot manages 4.0.3 |
| Liquibase Core | 3.6.3 | 4.x+ | |
| Liquibase Gradle Plugin | 2.0.4 | 3.1.0+ | Gradle 9 compat |
| Apache Tika | 1.25 | 2.x+ | Very old |
| Caffeine | 3.0.1 | Latest 3.x | |
| PostgreSQL driver | 42.6.0 | Latest 42.7.x | |

## Phase 5: Gradle 7.6.3 → 9.3.1

Gradle 8.x cannot run on JDK 25 (Groovy DSL incompatibility, won't be backported). Gradle 9.1+ is the first version with JDK 25 daemon support.

Key breaks:
- Minimum JVM 17 to run Gradle itself
- `jcenter()` repository removed (used in `ezspa/build.gradle.kts` — remove or exclude module)
- Numerous deprecated APIs removed from 7.x/8.x

Recommended: upgrade to 8.14.4 first as intermediate step, fix deprecation warnings, then go to 9.3.1.

## Phase 6: JDK switch

- Update `sourceCompatibility` and `jvmTarget` to `"25"` (or `"17"` if no Java 25 language features needed)
- Update `JAVA_HOME` references in docs/MEMORY
- Full integration test — especially AsciidoctorJ (exercised at runtime, not compile time)

## Build files to change

- `core/build.gradle` — sourceCompatibility, jvmTarget, dependency versions
- `tsl/build.gradle.kts` — `JavaVersion.VERSION_11` → `VERSION_25`
- `tsl-common/build.gradle.kts` — kotlinx-serialization version
- `build.gradle.kts` — Kotlin plugin versions
- `core/gradle.properties` — `kotlinVersion`
- `gradle/wrapper/gradle-wrapper.properties` — Gradle distribution URL

## Rough effort estimate: ~10–15 days
