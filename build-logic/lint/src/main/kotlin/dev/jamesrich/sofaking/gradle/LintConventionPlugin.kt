package dev.jamesrich.sofaking.gradle

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.plugin.DetektPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin

/**
 * The lint every project in the three builds shares, the root of a multi-project build included.
 *
 * - Spotless formats the Kotlin sources and Gradle scripts with ktlint, set up by the repository's `.editorconfig`:
 *   `spotlessCheck` fails on a file ktlint would change, and `spotlessApply` changes it.
 * - detekt analyzes each Kotlin project with its default rules plus `gradle/detekt.yml`, and the Compose rules where the
 *   Compose compiler is applied: `detekt` fails on any finding.
 *
 * Both versions come from the version catalog; the Compose rules are built against one detekt version.
 */
class LintConventionPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val ktlint = libs.findVersion("ktlint").get().requiredVersion

        // The style lives in the repository's .editorconfig, above each build's root, where Spotless would not look:
        // without it ktlint falls back to its own ktlint_official style.
        val editorConfig = isolated.rootProject.projectDirectory.file("../.editorconfig").asFile
        pluginManager.apply(SpotlessPlugin::class.java)
        extensions.configure(SpotlessExtension::class.java) { spotless ->
            spotless.kotlin {
                it.target("src/**/*.kt")
                it.ktlint(ktlint).setEditorConfigPath(editorConfig)
            }
            spotless.kotlinGradle {
                it.target("*.gradle.kts")
                it.ktlint(ktlint).setEditorConfigPath(editorConfig)
            }
        }

        plugins.withType(KotlinBasePlugin::class.java) {
            pluginManager.apply(DetektPlugin::class.java)
            extensions.configure(DetektExtension::class.java) { detekt ->
                // Every source set at once, Kotlin Multiplatform's included; the plugin's own default is src/main and
                // src/test only.
                detekt.source.setFrom(layout.projectDirectory.dir("src"))
                detekt.buildUponDefaultConfig.set(true)
                // Every build sits one directory below the repository root, beside the shared gradle/ directory.
                detekt.config.setFrom(isolated.rootProject.projectDirectory.file("../gradle/detekt.yml"))
                detekt.parallel.set(true)
            }
            pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
                dependencies.add("detektPlugins", libs.findLibrary("compose-rules-detekt").get())
            }
        }
    }
}
