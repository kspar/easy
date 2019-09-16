import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJsDce

val DCE_ENABLED = true
val SOURCE_MAP_ENABLED = true

group = "ee.urgas"
version = "2"


plugins {
    id("kotlin2js")
    id("kotlin-dce-js")
    id("kotlinx-serialization")
}

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-js")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common:0.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js:0.13.0")
    testCompile("junit:junit:4.11")
    testImplementation("org.jetbrains.kotlin:kotlin-test-js")
}

tasks.named<Kotlin2JsCompile>("compileKotlin2Js").get()
        .kotlinOptions {
            sourceMap = SOURCE_MAP_ENABLED
        }

tasks.named<KotlinJsDce>("runDceKotlinJs").get()
        .dceOptions {
            devMode = !DCE_ENABLED
        }
