plugins {
    // AGP 9 provides Kotlin support built in, so no separate Kotlin Android plugin is applied.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
