// Every plugin version used anywhere in the build is declared here, once, from
// gradle/libs.versions.toml. Subprojects apply these without a version — see :core, :tsl and
// :tsl-common. Putting a version back in a subproject reintroduces the duplication this
// replaced (Kotlin's version alone used to appear in four places).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDependencyManagement) apply false
    // org.liquibase.gradle is deliberately NOT declared here. Declaring it in the root puts the
    // plugin in the root buildscript classloader, which cannot see the liquibase-core that
    // :core adds to its own buildscript classpath — the Liquibase tasks then die with
    // NoClassDefFoundError: liquibase/Scope. :core applies it via the catalog alias instead,
    // so the version still lives in gradle/libs.versions.toml.
    alias(libs.plugins.kover) apply false
}

/**
 * One rule for every module's test task: say how many tests ran, and fail if none did.
 *
 * Gradle prints nothing when tests pass, so "3 passed" and "0 matched the filter" look identical in
 * CI — and a module whose tests silently stopped being discovered goes green forever. That is the
 * same vacuous-pass failure the web suite's check-count ratchet exists for, one level up.
 *
 * Here rather than in each module because it now applies to three of them, and because the module
 * most likely to need it is the next one somebody adds. Module-specific test configuration
 * (`:core`'s `excludeTags` and `contract.write`) stays in that module's own build file.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        var executed = 0L
        afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
            if (desc.parent == null) {
                executed = result.testCount
                logger.lifecycle(
                    "Tests (${project.name}): ${result.testCount} completed, " +
                            "${result.failedTestCount} failed, ${result.skippedTestCount} skipped"
                )
            }
        }))
        doLast {
            if (executed == 0L) throw GradleException(
                "No tests were executed in :${project.name}. Either the test source set is empty, " +
                        "a filter matched nothing, or discovery is broken — none of which is a pass."
            )
        }
    }
}
