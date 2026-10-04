plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
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
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.websockets)
    implementation(libs.logback.classic)

    implementation(libs.hoplite.core)
    implementation(libs.hoplite.hocon)

    // Shared test support (in-memory adapters, object mothers, GPX/OSM fixtures) used by every test suite.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.bundles.unit.test)
    testFixturesApi(ktorLibs.server.testHost)
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
            }
        }

        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.junit)
                implementation(libs.postgresql)
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
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.junit)
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
