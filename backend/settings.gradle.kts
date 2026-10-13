pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "KtorEap"
                    url = uri("https://packages.jetbrains.team/maven/p/ktor/eap")
                }
            }
            filter { includeGroup("io.ktor") }
        }
    }
}
apply(from = "../gradle/build-cache.settings.gradle.kts")
rootProject.name = "sofa-king-backend"
include(":api-contract", ":server", ":client")
