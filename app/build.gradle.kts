import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.factorio.local.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.plutocrown.factorio"
        minSdk = 26
        // Keep runtime/storage/lifecycle behavior unchanged during the UI migration.
        targetSdk = 33
        versionCode = providers.gradleProperty("factorioVersionCode").get().toInt()
        versionName = providers.gradleProperty("factorioVersionName").get()
    }
    signingConfigs {
        create("localRelease") {
            storeFile = rootProject.file(".local/release.keystore")
            storePassword = System.getenv("FACTORIO_KEYSTORE_PASSWORD")
                ?: rootProject.file(".local/release-keystore.pass").takeIf { it.isFile }?.readText()?.trim()
            keyAlias = System.getenv("FACTORIO_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "factoriorelease"
            keyPassword = System.getenv("FACTORIO_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePassword
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") { dimension = "distribution" }
        create("performance") {
            dimension = "distribution"
            applicationId = "com.miHoYo.Yuanshen"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The GNU loaders/DSOs are verified payloads, not AGP-built native code.
            keepDebugSymbols += "**/*.so"
        }
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    sourceSets.getByName("test") {
        java.srcDirs(rootProject.file("tests"), rootProject.file("tests/host-stubs"))
        java.exclude("device/**", "host-stubs/**", "ArchiveInventory.java")
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.tukaani:xz:1.12")
    compileOnly(project(":compile-stubs"))
    testImplementation("junit:junit:4.13.2")
}

val restoreRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val javaBin = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    commandLine(javaBin.absolutePath, rootProject.file("scripts/RestoreRuntime.java").absolutePath)
}
tasks.named("preBuild") { dependsOn(restoreRuntime) }
