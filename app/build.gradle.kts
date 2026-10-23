import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application") version "9.0.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "org.factorio.local.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.plutocrown.factorio"
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        // Target the SDK used to compile the app; window/back behavior is handled explicitly.
        targetSdk = 36
        versionCode = providers.gradleProperty("factorioVersionCode").get().toInt()
        versionName = providers.gradleProperty("factorioVersionName").get()
    }
    signingConfigs {
        create("localRelease") {
            storeFile = rootProject.file(".local/release.keystore")
            storePassword =
                System.getenv("FACTORIO_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: rootProject
                        .file(".local/release-keystore.pass")
                        .takeIf { it.isFile }
                        ?.readText()
                        ?.trim()
            keyAlias =
                System.getenv("FACTORIO_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "factoriorelease"
            keyPassword =
                System.getenv("FACTORIO_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePassword
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
        getByName("debug") {
            // Same identity as release so a test install keeps the imported game.
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
    sourceSets.getByName("main") {
        jniLibs.setSrcDirs(
            listOf(layout.buildDirectory.dir("generated/game-runtime/jni").get().asFile)
        )
        jniLibs.srcDir(layout.buildDirectory.dir("generated/ime/jni").get().asFile)
        assets.srcDir(layout.buildDirectory.dir("generated/ime/assets").get().asFile)
        assets.srcDir(layout.buildDirectory.dir("generated/mesa/assets").get().asFile)
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
    testImplementation("org.json:json:20240303")
}

val restoreRuntime by
    tasks.registering(Exec::class) {
        workingDir(rootProject.projectDir)
        val javaBin =
            File(
                System.getProperty("java.home"),
                "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
            )
        commandLine(
            javaBin.absolutePath,
            rootProject.file("scripts/RestoreRuntime.java").absolutePath,
        )
    }
val buildImeNative by
    tasks.registering(Exec::class) {
        workingDir(rootProject.projectDir)
        val javaBin =
            File(
                System.getProperty("java.home"),
                "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
            )
        commandLine(
            javaBin.absolutePath,
            rootProject.file("scripts/BuildImeNative.java").absolutePath,
        )
        inputs.files(
            rootProject.fileTree("native-input/direct-wsi"),
            rootProject.file("native-input/ime/keyboard-opener.c"),
            rootProject.file("native-input/ime/sdl-hint-guard.c"),
            rootProject.file("native-input/sdl-symbols.h"),
            rootProject.file("native-input/gamepad-bridge.c"),
            rootProject.file("scripts/BuildImeNative.java"),
            rootProject.file("scripts/BuildAndroidReceiver.java"),
            rootProject.file("native-input/android-link-stubs.c"),
            rootProject.file("scripts/ime-compiler.properties"),
        )
        inputs.dir(rootProject.file("native-input/gnu-tls"))
        inputs.dir(rootProject.file("native-input/direct-present-include"))
        inputs.file(rootProject.file("native-input/direct-present-server.c"))
        outputs.dir(layout.buildDirectory.dir("generated/ime"))
    }
val buildMesaNative by
    tasks.registering(Exec::class) {
        dependsOn(restoreRuntime, buildImeNative)
        workingDir(rootProject.projectDir)
        val javaBin =
            File(
                System.getProperty("java.home"),
                "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
            )
        commandLine(
            javaBin.absolutePath,
            rootProject.file("scripts/BuildMesaNative.java").absolutePath,
        )
    }
val stageGameRuntime by
    tasks.registering(Sync::class) {
        dependsOn(buildMesaNative)
        from(rootProject.file("app/src/main/jniLibs")) {
            exclude(
                "arm64-v8a/librea_direct_icd.so",
                "arm64-v8a/librea_gamepad_bridge.so",
                "arm64-v8a/librea_gnu_tls.so",
                "arm64-v8a/librea_direct_present.so",
            )
        }
        from(layout.buildDirectory.dir("generated/mesa/jni")) {
            include("arm64-v8a/librea_compat_*.so")
        }
        into(layout.buildDirectory.dir("generated/game-runtime/jni"))
    }

tasks.named("preBuild") { dependsOn(stageGameRuntime, buildImeNative) }
