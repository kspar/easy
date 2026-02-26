
plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":tsl-common"))
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
	implementation("com.charleskorn.kaml:kaml:0.52.0")
}
