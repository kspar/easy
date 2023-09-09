import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

group = "ee.urgas"
version = "2"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ezspa"))
    implementation(project(":tsl-common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")

    val pathToMaterialize: String? by project
    when (pathToMaterialize) {
        null -> logger.warn("wui: path to materialize not configured")
        else -> implementation(npm(File(pathToMaterialize!!)))
    }

    implementation(npm("overlayscrollbars", "2.3.0"))
    implementation(npm("container-query-polyfill", "0.1.2"))
    implementation(npm("mustache", "4.2.0"))
}

kotlin {
    js(IR) {
        browser {}
        binaries.executable()
    }
}


tasks.register("wuiLocalCopyAppProperties") {
    group = "a wui"
    doLast {
        val sourcePath = Paths.get("src/main/kotlin/AppProperties.kt.local")
        val destPath = Paths.get("src/main/kotlin/AppProperties.kt")
        Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING)
    }
}

tasks.register("wuiDevCopyAppProperties") {
    group = "a wui"
    doLast {
        val sourcePath = Paths.get("src/main/kotlin/AppProperties.kt.dev")
        val destPath = Paths.get("src/main/kotlin/AppProperties.kt")
        Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING)
    }
}

tasks.register("wuiLocalBuildAndCopy", Copy::class.java) {
    group = "a wui"
    dependsOn("wuiLocalCopyAppProperties", "browserDevelopmentWebpack")

    from("build/developmentExecutable/wui.js") {
        into("static/js")
    }
    from("static/index.html")
    from("static") {
        into("static")
    }
    val localWuiServeDir: String by project
    into(localWuiServeDir)
}

tasks.register("wuiDevBuild") {
    group = "a wui"
    dependsOn("wuiDevCopyAppProperties", "browserDevelopmentWebpack")
}

// Specify task ordering if these tasks are run together
tasks.named("browserDevelopmentWebpack").configure {
    mustRunAfter("wuiLocalCopyAppProperties")
    mustRunAfter("wuiDevCopyAppProperties")
}
