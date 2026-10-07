plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "veeci.practicing"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "veeci.practicing.rts.simulator.MainKt"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // DriverClient is public API of this module (the e2e tests use it), and its constructor takes a Ktor HttpClient.
    api(project(":protocol"))
    api(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    runtimeOnly(libs.logback.classic)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.kotest.assertions)
            }
        }
    }
}
