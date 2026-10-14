// One IDE import for every build; CI builds app/ and backend/ on their own.
apply(from = "gradle/build-cache.settings.gradle.kts")
rootProject.name = "sofa-king-monorepo"
includeBuild("contract")
includeBuild("app")
includeBuild("backend")

// The root has no projects. Dependabot reads these to look up the shared catalog's versions, which it finds from here.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
