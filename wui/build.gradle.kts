
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.0.0-RC")

    implementation(npm("css-element-queries", "1.2.2"))
}

kotlin {
    js {
        browser {}
        binaries.executable()
    }
}
