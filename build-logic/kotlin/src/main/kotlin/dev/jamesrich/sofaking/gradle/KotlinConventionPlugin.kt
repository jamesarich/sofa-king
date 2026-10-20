package dev.jamesrich.sofaking.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.EnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinToolingSetupTask
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * What every project in the three builds shares: the JDK 21 toolchain for Kotlin and the web toolchains (Node, Yarn,
 * Binaryen) resolved through the repositories in `gradle/repositories.settings.gradle.kts`, and, where a build sets
 * `sofaking.warningsAsErrors=true` in its `gradle.properties`, compiler warnings that fail the build.
 * Apply it to each project, the root of a multi-project build included, since the Kotlin plugin adds the web
 * toolchain plugins to the root as well as to each web module.
 */
@OptIn(ExperimentalWasmDsl::class) // The Binaryen and Wasm Node toolchain specs are still experimental.
class KotlinConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.withType(KotlinBasePlugin::class.java) {
            extensions.getByType(KotlinBaseExtension::class.java).jvmToolchain(JDK)
        }

        // Compiles only: Kotlin/Native's link tasks warn about the runner's processor count, which no source can fix.
        // A gradle.properties value, not a CI flag, so local and CI compiles share remote-cache keys.
        val warningsAsErrors = providers.gradleProperty("sofaking.warningsAsErrors").map(String::toBoolean).orElse(false)
        tasks.withType(KotlinCompilationTask::class.java).configureEach {
            it.compilerOptions.allWarningsAsErrors.set(warningsAsErrors)
        }

        // The JS and Wasm npm installs each run Yarn and store a lock file; run together under the configuration cache
        // they raced, and `kotlinStoreYarnLock` then found no `build/js/yarn.lock`. One at a time, across the build.
        val npmInstall = gradle.sharedServices.registerIfAbsent("sofakingNpmInstall", NpmInstallLock::class.java) {
            it.maxParallelUsages.set(1)
        }
        tasks.withType(KotlinNpmInstallTask::class.java).configureEach { it.usesService(npmInstall) }
        tasks.withType(KotlinToolingSetupTask::class.java).configureEach { it.usesService(npmInstall) }

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

/** Holds no state; its `maxParallelUsages` of one serialises the tasks that use it. */
abstract class NpmInstallLock : BuildService<BuildServiceParameters.None>
