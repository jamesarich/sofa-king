// One IDE import for both Gradle builds; CI builds app/ and contract/ on their own. The Kotlin Toolchain builds the
// server from project.yaml, which the IDE's Kotlin Toolchain plugin reads.
apply(from = "gradle/build-cache.settings.gradle.kts")
rootProject.name = "sofa-king-monorepo"
includeBuild("contract")
includeBuild("app")
