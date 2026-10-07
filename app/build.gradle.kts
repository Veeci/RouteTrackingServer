import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
    // Applied to every module by the root build script; named here as well, so that the `kover {}` block below compiles.
    id("org.jetbrains.kotlinx.kover")
}

group = "veeci.practicing"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "veeci.practicing.rts.ApplicationKt"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.bodyLimit)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.metrics.micrometer)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.rateLimit)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.swagger)
    implementation(ktorLibs.server.websockets)
    implementation(libs.logback.classic)
    implementation(platform(libs.kotlinx.coroutines.bom))
    implementation(libs.kotlinx.coroutines.slf4j)
    runtimeOnly(libs.logstash.encoder)
    implementation(libs.micrometer.prometheus)

    implementation(libs.hoplite.core)
    implementation(libs.hoplite.hocon)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.hikari)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // Shared test support (in-memory adapters, object mothers, GPX/OSM fixtures) used by every test suite.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.bundles.unit.test)
    testFixturesApi(ktorLibs.server.testHost)
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesApi(platform(libs.koin.bom))
    testFixturesApi(libs.koin.ktor)
    testFixturesApi(libs.openapi.validator)
    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi(libs.testcontainers.postgresql)
}

// Test suites (see docs/testing/strategy.md):
//   test            fast: unit, property, service, architecture, wiring. No Docker.
//   integrationTest real Postgres via Testcontainers: repositories, API tests. Skips itself when Docker is absent.
//   e2eTest         whole journeys driven by the simulator's clients.
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(testFixtures(project()))
                implementation(libs.konsist)
                implementation(libs.koin.test.junit5)
                implementation(project(":tools:simulator"))
            }
        }

        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(libs.testcontainers.junit)
                // Repository, transaction, logging and WebSocket tests use these types directly.
                implementation(project(":protocol"))
                implementation(libs.logback.classic)
                implementation(libs.exposed.core)
                implementation(libs.exposed.jdbc)
                implementation(libs.hikari)
            }
            targets.all { testTask.configure { shouldRunAfter(test) } }
        }

        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(project(":protocol"))
                implementation(project(":tools:simulator"))
                implementation(libs.testcontainers.junit)
                // The restart test runs a real server and connects to it over the network.
                implementation(ktorLibs.server.netty)
                implementation(ktorLibs.client.cio)
            }
            targets.all { testTask.configure { shouldRunAfter(integrationTest) } }
        }
    }
}

tasks.named<JavaExec>("run") {
    val envFile = rootProject.file("deploy/.env")
    if (envFile.exists()) {
        envFile
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"), testing.suites.named("e2eTest"))
}

// Coverage gate (docs/testing/strategy.md): `check` fails if less than 80 % of the lines in the domain and application
// packages of every context run in tests. Adapters are measured in the aggregated report of the root project
// (`./gradlew koverHtmlReport`), not gated.
kover {
    reports {
        total {
            filters {
                includes { classes("veeci.practicing.rts.*.domain.*", "veeci.practicing.rts.*.application.*") }
            }
            verify {
                onCheck = true
                rule("Line coverage of domain and application code") { minBound(80, CoverageUnit.LINE) }
            }
        }
    }
}
