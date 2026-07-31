// Versions come from the root build.gradle.kts / gradle/libs.versions.toml.
plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

kotlin {
	jvmToolchain(25)
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":tsl-common"))
	implementation(libs.kotlin.reflect)
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kaml)
}
