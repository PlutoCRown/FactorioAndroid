plugins { id("com.diffplug.spotless") version "8.0.0" }

tasks.register("formatCheck") {
    group = "verification"
    description = "Check Java, Kotlin and Gradle source formatting."
    dependsOn("spotlessCheck")
}

tasks.register("formatApply") {
    group = "formatting"
    description = "Format Java, Kotlin and Gradle sources."
    dependsOn("spotlessApply")
}

// Keep formatting targets explicit: generated files, native dependencies and assets are excluded.
val sourceRoot = rootProject.projectDir

spotless {
    java {
        target(
            fileTree(sourceRoot.resolve("app/src")) { include("**/*.java") },
            fileTree(sourceRoot.resolve("compile-stubs/src")) { include("**/*.java") },
            fileTree(sourceRoot.resolve("scripts")) { include("*.java") },
            fileTree(sourceRoot.resolve("tests/device")) { include("*.java") },
        )
        googleJavaFormat("1.24.0").aosp()
        endWithNewline()
    }
    kotlin {
        target(fileTree(sourceRoot.resolve("app/src")) { include("**/*.kt") })
        ktfmt("0.53").kotlinlangStyle()
    }
    kotlinGradle {
        target(
            sourceRoot.resolve("build.gradle.kts"),
            sourceRoot.resolve("settings.gradle.kts"),
            sourceRoot.resolve("app/build.gradle.kts"),
            sourceRoot.resolve("compile-stubs/build.gradle.kts"),
        )
        ktfmt("0.53").kotlinlangStyle()
    }
}
