import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Version catalog accessors are not available inside `buildscript`, so this is the one
        // liquibase version that cannot be read from gradle/libs.versions.toml. Keep it in sync
        // with `liquibase` there. Removing this block is not an option: the Liquibase tasks fail
        // with NoClassDefFoundError: liquibase/Scope without it.
        classpath("org.liquibase:liquibase-core:4.31.1")
    }
}

// Versions come from the root build.gradle.kts — deliberately applied without one here.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    // Versioned here rather than in the root, so the plugin lands in the same classloader as
    // the liquibase-core on this project's buildscript classpath. See the note in the root build.
    alias(libs.plugins.liquibase)
}

group = "ee.urgas"

// One version for the whole product, from the repo-root VERSION file (EZ-1709). web/ and aae/ read
// the same file, so "which version is deployed" has one answer rather than three that can disagree.
// Bumping it is a release step — see doc/release-procedure.md.
version = rootProject.file("VERSION").readText().trim()

/**
 * The commit this jar was built from, seven characters of it.
 *
 * `GITHUB_SHA` first, because that is the authority in CI and needs no git in the build. Locally it
 * falls back to asking git, and to "unknown" where neither exists (a source tarball, a build with
 * git absent) — a version endpoint reporting "unknown" is fine, a build that fails because it could
 * not find git is not.
 */
val gitCommit: String = System.getenv("GITHUB_SHA")?.take(7)
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
            workingDir = rootDir
        }.standardOutput.asText.get().trim().ifEmpty { null }
    }.getOrNull() ?: "unknown"

springBoot {
    // Generates META-INF/build-info.properties, which Spring exposes as a BuildProperties bean —
    // read by core.ems.service.VersionsController. `time` comes for free and is the honest answer
    // to "is this actually the build I deployed an hour ago".
    buildInfo {
        properties {
            additional.put("commit", gitCommit)
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

repositories {
    mavenCentral()
}

tasks.test {
    useJUnitPlatform {
        // Nothing passes -PexcludeTags any more: CI runs a plain `./gradlew build` and every test
        // executes, since EZ-1715 gave the suite its own PostgreSQL (Testcontainers) and the test
        // config is committed. No test in the tree is tagged today.
        //
        // Kept because it costs nothing and is the right tool for the next thing that needs it —
        // "run everything except the slow integration set" — and because a filter mechanism is
        // easier to keep than to reintroduce. If you do tag something, note the guard below: a
        // typo'd tag name that matches nothing would otherwise run zero tests and go green.
        (project.findProperty("excludeTags") as String?)
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.forEach { excludeTags(it) }
    }

    // Gradle prints nothing when tests pass, so "3 passed" and "0 matched the filter" look
    // identical in CI. With the exclusion above driven by a property, a typo would silently run
    // nothing and still go green, so report the count and fail on zero.
    var executed = 0L
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent == null) {
            executed = result.testCount
            logger.lifecycle(
                "Tests: ${result.testCount} completed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped"
            )
        }
    }))
    doLast {
        if (executed == 0L) throw GradleException(
            "No tests were executed. Check the -PexcludeTags value and the @Tag annotations."
        )
    }
}

dependencies {

    // Spring Boot
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    // Core verifies Keycloak JWTs itself — see SecurityConf and doc/core/api-testing.md
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.cache)
    // restTemplate:
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.validation)

    // Cache
    implementation(libs.caffeine)

    // Logging
    implementation(libs.kotlin.logging)

    // Database
    implementation(libs.bundles.exposed)

    implementation(libs.postgresql)
    implementation(libs.liquibase.core)
    liquibaseRuntime(libs.liquibase.core)
    liquibaseRuntime(libs.postgresql)
    liquibaseRuntime(libs.picocli)
    liquibaseRuntime(libs.commons.lang3)
    liquibaseRuntime(files("src/main/resources"))

    // Kotlin support
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.security.test)
    // The suite starts its own PostgreSQL. Needs a Docker daemon — the same one `docker compose up
    // db` already requires — or EASY_TEST_JDBC_URL pointing at a local throwaway database.
    // See core/src/test/kotlin/core/testing/TestDatabase.kt.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)

    // Markdown (CommonMark with GFM extensions)
    implementation(libs.bundles.commonmark)

    // Jsoup for post-processing rendered Markdown (see MarkdownService)
    implementation(libs.jsoup)

    // StoredFile type detection
    implementation(libs.tika.core)

    // Object storage for uploaded files (see core/ems/service/storage). Only pulled in by
    // S3StorageService — the local-filesystem backend a laptop and CI run on touches none of it.
    implementation(libs.aws.s3)

    // Source code similarity
    implementation(libs.fuzzywuzzy)

    // TSL
    implementation(project(":tsl"))
}

val liquibaseProperties = Properties().apply {
    val configured = file("src/main/resources/db/database.properties")
    val source = if (configured.exists()) configured else file("src/main/resources/db/database.properties.sample")
    source.inputStream().use { load(it) }
}

liquibase {
    // Activity's DSL is Groovy methodMissing, which Kotlin can't call — the same settings
    // go in as an arguments map instead. Keys are the Liquibase CLI argument names.
    activities.register("main") {
        arguments = buildMap {
            put("changelogFile", "db/changelog.xml")
            // Blank values are dropped rather than passed through: Liquibase's CLI parser
            // treats an empty `--password` as a missing parameter and swallows the next
            // argument as its value. Local dev runs postgres with trust auth, so a blank
            // password is the normal case there.
            listOf("url", "username", "password").forEach { key ->
                liquibaseProperties.getProperty(key)?.takeIf(String::isNotBlank)?.let { put(key, it) }
            }
        }
    }
}
