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
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.metrics.micrometer)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.forwardedHeader)
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
    implementation(libs.lettuce.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // Shared test support (in-memory adapters, object mothers, GPX/OSM fixtures) used by every test suite.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.bundles.unit.test)
    testFixturesApi(ktorLibs.server.testHost)
    // socketApp: WebSocket tests run on Netty and connect with CIO, as in production.
    testFixturesImplementation(ktorLibs.server.netty)
    testFixturesImplementation(ktorLibs.client.cio)
    // TestProvider signs RS256 tokens like the identity provider (phase 12g). java-jwt comes with the Ktor JWT plugin.
    testFixturesImplementation(ktorLibs.server.auth.jwt)
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
                implementation(libs.turbine)
                implementation(project(":tools:simulator"))
            }
            // Benchmarks on the real Hanoi extract are tagged `slow`: they need a gitignored file and minutes of CPU.
            targets.all { testTask.configure { useJUnitPlatform { excludeTags("slow") } } }
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
                implementation(libs.micrometer.prometheus) // metric assertions (phase 10)
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

// A second start script, `bin/migrate`, next to `bin/app` in the distribution and the image (phase 1.5). The deploy runs
// it before the new app starts: docker compose run --rm --no-deps --entrypoint /app/bin/migrate app
val migrateStartScripts by tasks.registering(CreateStartScripts::class) {
    val appScripts = tasks.startScripts.get()
    applicationName = "migrate"
    mainClass = "veeci.practicing.rts.MigrateKt"
    classpath = appScripts.classpath
    outputDir =
        layout.buildDirectory
            .dir("migrateScripts")
            .get()
            .asFile
}

distributions.main {
    contents { from(migrateStartScripts) { into("bin") } }
}

// `./gradlew slowTest`: only the tests tagged `slow` (TC-6-GR-09, TC-6-SP-11 and the cache and search benchmarks of
// slice 6d). Not part of `check`, because CI has no OSM file; each test skips itself with a message when the file is
// missing. The 512 MB heap is the TC-6-GR-09 limit: the Hanoi load must fit in it.
val slowTest by tasks.registering(Test::class) {
    description = "Runs the benchmarks tagged slow against data/osm/hanoi.osm (docs/data/osm.md)."
    group = "verification"
    val unit = sourceSets.test.get()
    testClassesDirs = unit.output.classesDirs
    classpath = unit.runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    maxHeapSize = "512m"
    systemProperty("rts.osm.hanoi", rootProject.file("data/osm/hanoi.osm").absolutePath)
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
    // A benchmark is worth running again even when no input changed.
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"), testing.suites.named("e2eTest"))
}

// Coverage gate (docs/testing/strategy.md): `check` fails if less than 80 % of the lines in the domain and application
// packages of every context run in tests. Adapters are measured in the aggregated report of the root project
// (`./gradlew koverHtmlReport`), not gated.
kover {
    // Coverage instrumentation would slow the benchmarks down and skew their numbers.
    currentProject { instrumentation { disabledForTestTasks.add("slowTest") } }
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
