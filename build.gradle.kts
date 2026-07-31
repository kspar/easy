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
}
