// The repositories every build resolves from. A build that needs more (the app's Google Maven) declares it before
// applying this. Project repositories are refused, so the Kotlin plugin's Node, Yarn and Binaryen downloads come from
// the ivy repositories below; the sofaking.kotlin convention in build-logic stops the plugin adding its own.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
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
    versionCatalogs {
        // Every build sits one directory below the repository root, beside this gradle/ directory.
        create("libs") { from(files(settingsDir.resolve("../gradle/libs.versions.toml"))) }
    }
}
