import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// Plugins are declared once here (versions from the catalog) and applied per module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(ktorLibs.plugins.ktor) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
}

// Formatting: ktlint via Spotless for every Kotlin source and build script in the repo.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
    }
}

// Static analysis: detekt on every Kotlin module, default rules plus config/detekt/detekt.yml.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jetbrains.kotlinx.kover")
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            parallel = true
        }
    }
}

// Coverage: one aggregated Kover report for all modules (`./gradlew koverHtmlReport`).
// The 80 % gate on domain and application code is in app/build.gradle.kts.
dependencies {
    kover(project(":app"))
    kover(project(":protocol"))
    kover(project(":tools:simulator"))
}
