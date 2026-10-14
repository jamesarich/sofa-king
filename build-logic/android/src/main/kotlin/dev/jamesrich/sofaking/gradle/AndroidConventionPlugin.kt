package dev.jamesrich.sofaking.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** The Android SDK levels the app, its Baseline Profile generator and the Kotlin Multiplatform libraries share. */
class AndroidConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.withPlugin("com.android.application") {
            extensions.getByType(ApplicationExtension::class.java).apply {
                compileSdk { version = release(COMPILE_SDK) { minorApiLevel = COMPILE_SDK_MINOR } }
                defaultConfig {
                    minSdk = MIN_SDK
                    targetSdk = TARGET_SDK
                }
            }
        }
        pluginManager.withPlugin("com.android.test") {
            extensions.getByType(TestExtension::class.java).apply {
                compileSdk { version = release(COMPILE_SDK) { minorApiLevel = COMPILE_SDK_MINOR } }
                defaultConfig {
                    minSdk = MIN_SDK
                    targetSdk = TARGET_SDK
                }
            }
        }
        pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java) as ExtensionAware
            kotlin.extensions.getByType(KotlinMultiplatformAndroidLibraryExtension::class.java).apply {
                compileSdk { version = release(COMPILE_SDK) { minorApiLevel = COMPILE_SDK_MINOR } }
                minSdk = MIN_SDK
            }
        }
    }

    private companion object {
        const val COMPILE_SDK = 37
        const val COMPILE_SDK_MINOR = 1
        const val TARGET_SDK = 37
        const val MIN_SDK = 28
    }
}
