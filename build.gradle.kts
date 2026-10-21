plugins {
    id("com.android.application") version "9.0.1" apply false
    // AGP 9 supplies Kotlin 2.2.10. The Compose compiler must use the same version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
