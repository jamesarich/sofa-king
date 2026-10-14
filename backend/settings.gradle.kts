pluginManagement {
    includeBuild("../build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
apply(from = "../gradle/repositories.settings.gradle.kts")
apply(from = "../gradle/build-cache.settings.gradle.kts")
rootProject.name = "sofa-king-backend"
includeBuild("../contract")
include(":server")
