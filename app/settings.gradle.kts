pluginManagement {
    includeBuild("../build-logic")
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Provisions the JDK 21 toolchain on machines that lack one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Google's Maven first, for AndroidX and the Android plugin, and only for their groups, so every other module goes
// straight to the shared file's repositories.
dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
    }
}
apply(from = "../gradle/repositories.settings.gradle.kts")
apply(from = "../gradle/build-cache.settings.gradle.kts")

rootProject.name = "sofa-king"
includeBuild("../contract")
include(":core", ":ui", ":androidApp", ":baselineprofile")
