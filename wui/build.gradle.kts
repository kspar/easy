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

    implementation(npm("container-query-polyfill", "0.1.2"))
    implementation(npm("mustache", "4.2.0"))
}

kotlin {
    js(IR) {
        browser {}
        binaries.executable()
    }
}
