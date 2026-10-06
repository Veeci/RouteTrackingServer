plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "veeci.practicing"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Part of this library's public API (ProtocolJson is a Json), so consumers get it too.
    api(libs.kotlinx.serialization.json)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.kotest.assertions)
                implementation(libs.json.schema.validator)
            }
        }
    }
}
