// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.chaquo.python") version "17.1.0" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
