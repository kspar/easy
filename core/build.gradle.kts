import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.liquibase:liquibase-core:4.31.1")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.spring") version "2.3.10"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.liquibase.gradle") version "3.1.0"
}

group = "ee.urgas"
version = "1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

repositories {
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
}

dependencies {

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    // restTemplate:
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Cache
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Logging
    implementation("io.github.oshai:kotlin-logging:8.0.01")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:1.3.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.3.1")
    implementation("org.jetbrains.exposed:exposed-dao:1.3.1")
    implementation("org.jetbrains.exposed:exposed-jodatime:1.3.1")
    implementation("org.jetbrains.exposed:exposed-json:1.3.1")


    implementation("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core:4.31.1")
    liquibaseRuntime("org.liquibase:liquibase-core:4.31.1")
    liquibaseRuntime("org.postgresql:postgresql")
    liquibaseRuntime("info.picocli:picocli:4.7.6")
    liquibaseRuntime("org.apache.commons:commons-lang3:3.20.0")
    liquibaseRuntime(files("src/main/resources"))

    // Kotlin support
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Asciidoc
    implementation("org.asciidoctor:asciidoctorj:3.0.1")

    // Markdown (CommonMark with GFM extensions)
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // Jsoup for Asciidoctor HTML output customisation
    implementation("org.jsoup:jsoup:1.22.1")

    // StoredFile type detection
    implementation("org.apache.tika:tika-core:3.2.3")

    // Source code similarity
    implementation("me.xdrop:fuzzywuzzy:1.2.0")

    // Temporary JWT parsing for username migration
    implementation("com.auth0:java-jwt:3.18.3")

    // TSL
    implementation(project(":tsl"))
}

val liquibaseProperties = Properties().apply {
    val configured = file("src/main/resources/db/database.properties")
    val source = if (configured.exists()) configured else file("src/main/resources/db/database.properties.sample")
    source.inputStream().use { load(it) }
}

liquibase {
    // Activity's DSL is Groovy methodMissing, which Kotlin can't call — the same settings
    // go in as an arguments map instead. Keys are the Liquibase CLI argument names.
    activities.register("main") {
        arguments = buildMap {
            put("changelogFile", "db/changelog.xml")
            // Blank values are dropped rather than passed through: Liquibase's CLI parser
            // treats an empty `--password` as a missing parameter and swallows the next
            // argument as its value. Local dev runs postgres with trust auth, so a blank
            // password is the normal case there.
            listOf("url", "username", "password").forEach { key ->
                liquibaseProperties.getProperty(key)?.takeIf(String::isNotBlank)?.let { put(key, it) }
            }
        }
    }
}
