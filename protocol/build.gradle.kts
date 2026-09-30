plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "veeci.practicing"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}
