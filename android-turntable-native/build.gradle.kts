// Root build script. Plugins are declared here with `apply false` so the version
// catalog stays the single source of truth for the whole project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
