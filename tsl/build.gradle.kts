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

// Compiles a whole tree of TSL specs with the real compiler and counts the failures — the check
// that proves a spec migration, since nothing else is an authority on what the compiler accepts.
// See CompileSpecTree.kt and doc/core/tsl-migration/RUNBOOK.md.
//
//   ./gradlew -q :tsl:compileSpecTree -PspecTree=doc/core/tsl-migration/migrated/exercises
tasks.register<JavaExec>("compileSpecTree") {
	group = "verification"
	description = "Compile every <specTree>/<exercise id>/tsl.json; fails if any spec does not"
	classpath = sourceSets["main"].runtimeClasspath
	mainClass.set("com.example.demo.CompileSpecTreeKt")
	// Relative to the repo root rather than this module, so -PspecTree can be pasted from a doc
	// or a shell that is sitting where everything else in the migration runs.
	workingDir = rootDir
	args = listOfNotNull(project.findProperty("specTree")?.toString())
}
