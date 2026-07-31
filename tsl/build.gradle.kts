
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
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("com.charleskorn.kaml:kaml:0.104.0")
}
