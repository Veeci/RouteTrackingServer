// Plugins are declared once here (versions from the catalog) and applied per module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(ktorLibs.plugins.ktor) apply false
}
