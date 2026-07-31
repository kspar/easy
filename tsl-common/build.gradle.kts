// Shared TSL model classes, consumed by :tsl and through it by :core.
// Was a Kotlin Multiplatform module while :wui existed — the JS target served the
// Kotlin/JS UI only, so it went away with it. JVM-only now.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "ee.urgas"
version = "1"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
