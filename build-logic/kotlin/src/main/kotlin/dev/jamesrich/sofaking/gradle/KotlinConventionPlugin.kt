package dev.jamesrich.sofaking.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinTopLevelExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.EnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec

/**
 * What every project in the three builds shares: locked dependencies, the JDK 21 toolchain for Kotlin, and the web
 * toolchains (Node, Yarn, Binaryen) resolved through the repositories in `gradle/repositories.settings.gradle.kts`.
 * Apply it to each project, the root of a multi-project build included, since the Kotlin plugin adds the web
 * toolchain plugins to the root as well as to each web module.
 */
class KotlinConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        dependencyLocking.lockAllConfigurations()

        plugins.withType(KotlinBasePlugin::class.java) {
            extensions.getByType(KotlinTopLevelExtension::class.java).jvmToolchain(JDK)
        }

        // The settings refuse project repositories, which is where these plugins would otherwise add their own.
        noDownloadRepository<NodeJsPlugin, NodeJsEnvSpec>()
        noDownloadRepository<WasmNodeJsPlugin, WasmNodeJsEnvSpec>()
        noDownloadRepository<YarnPlugin, YarnRootEnvSpec>()
        noDownloadRepository<WasmYarnPlugin, WasmYarnRootEnvSpec>()
        noDownloadRepository<BinaryenPlugin, BinaryenEnvSpec>()
    }

    private inline fun <reified P : Plugin<*>, reified S : EnvSpec<*>> Project.noDownloadRepository() {
        plugins.withType(P::class.java) { extensions.getByType(S::class.java).downloadBaseUrl.set(null as String?) }
    }

    private companion object {
        const val JDK = 21
    }
}
