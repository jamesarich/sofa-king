import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("sofaking.kotlin")
    id("sofaking.android")
}

val appVersion = providers.gradleProperty("sofaking.version").get()
val versionSuffix = providers.gradleProperty("sofaking.versionSuffix").getOrElse("")

// The one version string, from gradle.properties, for the About row on every platform.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val output = layout.buildDirectory.dir("generated/buildInfo")
    val version = appVersion + versionSuffix
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("dev/jamesrich/sofaking/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.jamesrich.sofaking
            |
            |/** Generated from `sofaking.version` in gradle.properties. */
            |internal object BuildInfo {
            |    const val VERSION: String = "$version"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    // Every target Compose Multiplatform renders on. Android covers Android TV, Google TV and phones;
    // the JVM covers Linux, macOS and Windows desktops.
    android {
        namespace = "dev.jamesrich.sofaking.ui"
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = true }
    }
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SofaKingUi"
            isStatic = true
        }
    }
    // The browser runs the app; Node runs the common tests on JS without a headless browser. Wasm tests are off:
    // Compose's wasm test binary loads skiko.wasm, which Node is not served; core runs its tests on wasm.
    js {
        browser {
            commonWebpackConfig { outputFileName = "sofaking.js" }
            testTask { enabled = false }
        }
        // Coil's JS decoder loads skiko, whose glue writes to `window`; give Node one so the test binary loads.
        nodejs { testTask { nodeJsArgs += listOf("--import", "data:text/javascript,globalThis.window ??= globalThis") } }
        binaries.executable()
    }
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "sofaking.js" }
            testTask { enabled = false }
        }
        binaries.executable()
    }

    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                api(project(":core"))
                implementation("dev.jamesrich.sofaking:contract")
                // App() is this module's public surface, so the app shell compiles against the Compose it runs.
                api(libs.compose.runtime)
                api(libs.compose.ui)
                implementation(libs.compose.foundation)
                implementation(libs.compose.animation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material3.adaptive)
                implementation(libs.navigationevent.compose)
                implementation(libs.compose.components.resources)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.kermit)
                implementation(libs.kotlinx.datetime)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui.compose)
            implementation(libs.media3.session)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.vlcj)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.jamesrich.sofaking.resources"
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

compose.desktop {
    application {
        mainClass = "dev.jamesrich.sofaking.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // From `suggestRuntimeModules`; DataStore's protobuf needs jdk.unsupported, and the trimmed runtime lacks it.
            modules("java.instrument", "java.management", "jdk.unsupported")
            packageName = "Sofa King"
            packageVersion = appVersion.let { if (it.startsWith("0.")) "1.${it.removePrefix("0.")}" else it }
            // Fixed so each MSI upgrades the installed app in place.
            windows { upgradeUuid = "4b04060d-b077-47c9-acc6-f55b71d52d1f" }
        }
    }
}

// The player is libVLC, which the package cannot bundle; the .deb has apt install VLC with it.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) freeArgs.addAll("--linux-package-deps", "vlc")
}
