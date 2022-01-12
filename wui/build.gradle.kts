plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

group = "ee.urgas"
version = "2"

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(project(":ezspa"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")

    val pathToMaterialize: String? by project
    when (pathToMaterialize) {
        null -> logger.warn("wui: path to materialize not configured")
        else -> implementation(npm(File(pathToMaterialize!!)))
    }
//    implementation(npm("css-element-queries", "1.2.3"))
}

kotlin {
    js {
        browser {}
        binaries.executable()
    }
}
