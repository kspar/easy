import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJsDce

val DCE_ENABLED = true
val SOURCE_MAP_ENABLED = true

group = "ee.urgas"
version = "2"


plugins {
    id("kotlin2js") version("1.3.31")
    id("kotlin-dce-js") version("1.3.31")
    id("kotlinx-serialization") version("1.3.40")
}

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-js")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js:0.11.1")
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
