buildscript {
    dependencies {
        // AGP 9.1 bundles Kotlin 2.2, but the application and Ktor stack are built with Kotlin 2.4.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
