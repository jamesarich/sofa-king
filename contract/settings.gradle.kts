pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // The toolchains Kotlin/JS and Kotlin/Wasm download; the build stops their plugins adding these themselves.
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

apply(from = "../gradle/build-cache.settings.gradle.kts")
rootProject.name = "contract"

// The app and the server each compile this build with their own Kotlin plugin, so all three pin the same Kotlin and
// kotlinx.serialization.
val pins = Regex("""^(kotlin|serialization) = "([^"]+)"""", RegexOption.MULTILINE)
fun pinsOf(catalog: String) = pins.findAll(File(settingsDir, catalog).readText()).associate { it.groupValues[1] to it.groupValues[2] }
val ownPins = pinsOf("gradle/libs.versions.toml")
for (consumer in listOf("../app", "../backend")) {
    val consumerPins = pinsOf("$consumer/gradle/libs.versions.toml")
    check(consumerPins == ownPins) { "contract pins $ownPins but $consumer pins $consumerPins; move them together" }
}
