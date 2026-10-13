// One IDE import for every build; CI builds app/ and backend/ on their own.
apply(from = "gradle/build-cache.settings.gradle.kts")
rootProject.name = "sofa-king-monorepo"
includeBuild("contract")
includeBuild("app")
includeBuild("backend")
