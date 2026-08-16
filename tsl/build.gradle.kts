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

	testImplementation(libs.junit.jupiter)
	testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
	useJUnitPlatform()
	// PythonSyntaxTest shells out to python3 and skips itself with a reason when there is none.
	// Forwarded because the test JVM does not inherit Gradle's environment view of PATH edits.
	systemProperty("tsl.python", providers.gradleProperty("tsl.python").getOrElse("python3"))
	// Golden files are regenerated rather than asserted with -Ptsl.golden.update=true. Without the
	// forwarding the flag is silently ignored and the test simply fails, which is a confusing way
	// to be told the command was right.
	systemProperty("tsl.golden.update", providers.gradleProperty("tsl.golden.update").getOrElse("false"))
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
	// Second arg is the optional -PspecDump=<dir>. Empty rather than absent when unset, so the
	// positional pair stays a pair.
	args = listOfNotNull(
		project.findProperty("specTree")?.toString(),
		project.findProperty("specDump")?.toString() ?: "",
	)
}
