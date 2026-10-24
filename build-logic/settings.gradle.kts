// Convention plugins the app, the server and the contract share. Each build includes this one from its own
// pluginManagement block and applies the plugins by id.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // The Android plugin's API, for the android convention only.
        google { content { includeGroupAndSubgroups("com.android") } }
    }
}

apply(from = "../gradle/repositories.settings.gradle.kts")

rootProject.name = "build-logic"
include(":kotlin", ":android", ":lint")
