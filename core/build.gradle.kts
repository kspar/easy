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
        classpath("org.liquibase:liquibase-core:5.0.3")
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
    alias(libs.plugins.kover)
}

/**
 * Coverage: **report everywhere, gate in four places.**
 *
 * A global threshold is a trap in this codebase and would be worse than none. A large fraction of
 * these 17,000 lines is DTO declaration, so a global percentage measures the ratio of boilerplate to
 * logic rather than how well anything is tested — and the number moves for reasons nobody chose.
 * Worse, `EndpointAuthorizationMatrixTest` executes nearly every controller line as a side effect of
 * checking who may call them, so global coverage looks impressive while whatever actually needs
 * testing sits untouched. A number that rises when the thing you care about has not changed will be
 * trusted at exactly the wrong moment.
 *
 * So the gate names the packages where being untested is a specific, nameable harm, and everything
 * else is reported for a human to read:
 *
 * - **access_control** — the 306 lines deciding who may see whose work. Being wrong here returns
 *   somebody else's data and throws nothing.
 * - **conf.security** — the filter chain, the JWT converter, the permitAll list.
 * - **ems.service.storage** and **ems.cron** — the only code that deletes a teacher's file.
 *
 * Run `./gradlew :core:koverHtmlReport` and open the result; `koverVerify` is what fails the build.
 */
kover {
    reports {
        total {
            filters {
                excludes {
                    // Data carriers and the Spring entry point. Counting them measures how much
                    // boilerplate exists, which is not a question any of the targets below asks.
                    classes("core.EasyCoreApp*", "core.db.Tables*")
                }
            }
            xml { onCheck = false }
            html { onCheck = false }
        }
    }
}

/**
 * The packages where being untested is a specific, nameable harm — and the only ones with a number.
 *
 * Kover 0.9's own `verify` cannot express this: `KoverVerifyRule` lost per-rule filters, so a rule
 * applies to the whole report. Grouping by package instead applies the same bound to *every*
 * package, which fails on `core.db` for having DTOs in it. So the report is Kover's and the
 * threshold is ours — which also buys a failure message that names the package and the number.
 */
data class CoverageTarget(
    val classPrefix: String,
    val minimum: Int,
    val why: String,
    /** Classes deliberately not exercised, with the reason. Excluded from the denominator. */
    val except: List<String> = emptyList(),
)

/**
 * Targets name **classes**, not packages, because the first version of this named packages and
 * measured the wrong thing three times out of four.
 *
 * `core/ems/cron` scored 30% — not because the sweep is untested (it is at 93%) but because
 * `DeleteInactiveUsers`, 134 lines of Keycloak plumbing, shares the package. `core/conf/security`
 * scored 80% largely on `DummyZeroAuthFilter`, which is the auth-disabled path that must never run
 * on a deployed environment and which we therefore **want** uncovered.
 *
 * A threshold that moves when an unrelated neighbour is added is a threshold people learn to ignore.
 * These are ratchets set at or just under today's measurement: they exist to stop coverage falling,
 * not to claim a number was chosen from first principles.
 *
 * ### What this catches, measured
 *
 * Disabling the whole of `StoredFileSweepTest` takes the sweep from 94% to **7%** and fails the
 * build. Disabling *two* of its tests takes it to 92% and does not.
 *
 * That is the honest scope: a coverage gate catches an area falling out of the suite — a deleted
 * file, a class nobody exercises any more — and is blind to losing a test or two. Tightening the
 * numbers to close that gap would make them fail on refactors that add a line, which is how a gate
 * gets switched off. **The fine-grained question is mutation testing's** (`bin/mutate.sh`), which
 * asks whether a test can fail rather than whether a line was executed. The two are complementary
 * and neither substitutes for the other.
 */
val coverageTargets = listOf(
    // The 306 lines deciding who may see whose work. Being wrong here returns somebody else's data
    // and throws nothing. 85% today; the missing lines are error branches in courses.kt.
    CoverageTarget("core.ems.service.access_control.", 85, "who may see whose work"),

    CoverageTarget(
        "core.conf.security.", 85, "the security configuration",
        except = listOf(
            // Trusts oidc_claim_* headers verbatim and is installed only when auth is disabled.
            // Core refuses to start with that flag off a loopback address, so this is code whose
            // correctness we assert by it never running. Testing it would make the release gate the
            // biggest consumer of the one path that must never run anywhere real.
            "core.conf.security.DummyZeroAuthFilter",
        ),
    ),

    // The only code in this application that removes an object from storage.
    CoverageTarget("core.ems.service.storage.", 90, "object storage"),
    CoverageTarget("core.ems.cron.StoredFileSweep", 90, "the stored-file sweep"),
    CoverageTarget("core.ems.cron.Stored_file_sweepKt", 90, "the sweep's scanned-column list"),
)

tasks.register("koverVerifyTargets") {
    group = "verification"
    description = "Line coverage thresholds for the packages where being untested is a real harm"
    dependsOn(tasks.named("koverXmlReport"))

    val reportFile = layout.buildDirectory.file("reports/kover/report.xml")
    val targets = coverageTargets
    inputs.file(reportFile)

    doLast {
        val xml = groovy.xml.XmlParser().apply {
            // The report has no DTD to fetch, and fetching one from a build is a needless network
            // call that fails closed on an offline machine.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        }.parse(reportFile.get().asFile)

        // Class-level counts, keyed by fully-qualified name. Kover writes paths with slashes.
        @Suppress("UNCHECKED_CAST")
        val classes = (xml.get("package") as List<groovy.util.Node>)
            .flatMap { it.get("class") as List<groovy.util.Node> }
            .mapNotNull { cls ->
                val line = (cls.get("counter") as List<groovy.util.Node>)
                    .firstOrNull { it.attribute("type") == "LINE" } ?: return@mapNotNull null
                val covered = (line.attribute("covered") as String).toInt()
                val missed = (line.attribute("missed") as String).toInt()
                (cls.attribute("name") as String).replace('/', '.') to (covered to missed)
            }

        val failures = mutableListOf<String>()
        val report = StringBuilder("Coverage of the code that carries a threshold:\n")

        targets.forEach { target ->
            val matched = classes.filter { (name, _) ->
                name.startsWith(target.classPrefix) && target.except.none { name.startsWith(it) }
            }

            if (matched.isEmpty()) {
                // A target matching nothing scores 0 of 0 and would otherwise read as a pass. A
                // renamed class must fail the build rather than silently stop being checked — the
                // same reasoning as the fail-on-zero-tests guard in the root build.
                failures += "  ${target.classPrefix}* matched no class in the report — renamed, or no longer compiled?"
                return@forEach
            }

            val covered = matched.sumOf { it.second.first }
            val total = covered + matched.sumOf { it.second.second }
            val percent = if (total == 0) 0 else covered * 100 / total
            val verdict = if (percent >= target.minimum) "ok" else "BELOW ${target.minimum}%"
            report.append(
                "  %-42s %3d%% of %4d lines  %-12s (%s)%n"
                    .format(target.classPrefix + "*", percent, total, verdict, target.why)
            )
            if (percent < target.minimum) {
                val worst = matched.filter { it.second.second > 0 }
                    .sortedByDescending { it.second.second }
                    .take(3)
                    .joinToString(", ") { "${it.first.substringAfterLast('.')} (${it.second.second} missed)" }
                failures += "  ${target.classPrefix}* is at $percent%, below ${target.minimum}% for ${target.why}. Worst: $worst"
            }
        }

        logger.lifecycle(report.toString().trimEnd())

        if (failures.isNotEmpty()) throw GradleException(
            "Coverage below target:\n" + failures.joinToString("\n") +
                    "\n\nThese four packages carry a number because being untested in them is a " +
                    "specific harm — somebody else's data, or a teacher's file. Everything else is " +
                    "reported, not gated: see the note above coverageTargets in core/build.gradle.kts." +
                    "\n\nRead the detail with: ./gradlew :core:koverHtmlReport"
        )
    }
}

// Part of `check`, so `./gradlew build` enforces it. Reports stay opt-in (`onCheck = false`) —
// generating HTML on every build costs time nobody asked for; the XML the task above needs is
// produced by its own dependsOn.
tasks.named("check") { dependsOn("koverVerifyTargets") }

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

    // Forwarded into the test JVM, which does not inherit Gradle's own system properties.
    // `-Pcontract.write=true` makes GenerateApiShapes rewrite doc/core/api-shapes.json instead of
    // asserting against it; without this the flag is silently ignored and the test just fails,
    // which is a confusing way to be told the command was right.
    (project.findProperty("contract.write") as String?)?.let { systemProperty("contract.write", it) }

    // The "how many ran, and fail on zero" reporting lives in the root build.gradle.kts now, so
    // that :tsl and :tsl-common get it too. It matters most here, where `excludeTags` above is
    // driven by a property and a typo'd tag would otherwise run nothing and go green.
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
    // And a MinIO, for the S3 half of StorageServiceContractTest. Skipped with a reason when there
    // is no Docker, rather than silently reducing the suite to the local backend.
    testImplementation(libs.testcontainers.minio)

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
