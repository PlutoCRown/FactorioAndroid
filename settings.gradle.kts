pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FactorioAndroid"

// Formatting loads only JDK tooling, so it also works without an Android SDK.
if (!providers.gradleProperty("formatOnly").map(String::toBoolean).getOrElse(false)) {
    include(":app", ":compile-stubs")
}
