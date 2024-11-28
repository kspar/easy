val mvnGroup = "rip.kspar"
val mvnArtifact = "ezspa"
val mvnVersion = "0.4.0"
val repoUrl = "https://github.com/kspar/easy/tree/master/ezspa"

plugins {
    kotlin("js")
}

group = mvnGroup
version = mvnVersion

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.5.2")
}

kotlin {
    js(IR) {
        browser {}
        binaries.executable()
    }
}
