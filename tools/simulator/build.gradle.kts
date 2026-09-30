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
    implementation(project(":protocol"))
}
