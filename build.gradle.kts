// Top-level build file: plugin versions only.
plugins {
    alias(libs.plugins.android.application) apply false
    // org.jetbrains.kotlin.android removed: AGP 9 provides built-in Kotlin.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
