pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Provisions the JDK 21 toolchain on machines that lack one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The toolchains Kotlin/JS and Kotlin/Wasm download; the root build stops their plugins adding these themselves.
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

// The remote cache on omv, used only when credentials are set: sofaking.cache.* Gradle properties or SOFAKING_CACHE_*
// variables. CI pushes from main; every other build only reads.
fun cacheSetting(name: String): String? =
    providers.gradleProperty("sofaking.cache.$name")
        .orElse(providers.environmentVariable("SOFAKING_CACHE_${name.uppercase()}"))
        .orNull?.trim()?.takeIf { it.isNotEmpty() }

val cacheUsername = cacheSetting("username")
val cachePassword = cacheSetting("password")
buildCache {
    remote<HttpBuildCache> {
        url = uri(cacheSetting("url") ?: "https://gradle-cache.hiddentemple.xyz/cache/")
        isEnabled = cacheUsername != null && cachePassword != null
        isPush = cacheSetting("push") == "true"
        credentials {
            username = cacheUsername
            password = cachePassword
        }
    }
}

rootProject.name = "sofa-king"
include(":core", ":ui", ":androidApp")
