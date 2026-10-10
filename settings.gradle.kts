pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
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
rootProject.name = "sofa-king-backend"
include(":api-contract", ":server", ":client")
