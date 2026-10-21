import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("sofaking.kotlin")
    id("sofaking.android")
}

val appVersion = libs.versions.sofaking.get()
val versionSuffix = providers.gradleProperty("sofaking.versionSuffix").getOrElse("")

// The one version string, from the catalog, for the About row on every platform. Only the catalog version is compiled
// in; the snapshot suffix changes every run, so it travels as a resource file and leaves every compile output cached.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val output = layout.buildDirectory.dir("generated/buildInfo")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("dev/jamesrich/sofaking/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.jamesrich.sofaking
            |
            |/** Generated from `sofaking` in gradle/libs.versions.toml. */
            |internal object BuildInfo {
            |    /** The catalog version; [VERSION_SUFFIX_FILE] carries a snapshot's suffix. */
            |    const val VERSION: String = "$version"
            |    const val VERSION_SUFFIX_FILE: String = "files/version-suffix.txt"
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
    @OptIn(ExperimentalWasmDsl::class)
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
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    }
    // Every web source set, parents and children alike, as the compiler requires opt-ins to agree along dependsOn.
    sourceSets.matching { it.name.startsWith("web") || it.name.startsWith("js") || it.name.startsWith("wasmJs") }
        .configureEach { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }

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
                implementation(libs.compose.material3.adaptive.navigation.suite)
                implementation(libs.compose.material.icons.core)
                implementation(libs.navigationevent.compose)
                implementation(libs.navigation3.ui)
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
            implementation(libs.androidx.core.pip)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui.compose)
            implementation(libs.media3.session)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.vlcj)
        }
        // Desktop only: in commonTest it would reach the Android host tests, which have no Compose host.
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

// The snapshot suffix, `-dev.RUN+COMMIT` or empty, read by the About row at runtime. A custom resource directory
// replaces the source set's default one, so the common resources are synced beside the generated file.
val versionSuffixResources = layout.buildDirectory.dir("generated/versionSuffix")
val generateVersionSuffix = tasks.register("generateVersionSuffix") {
    // Locals, so the action captures values rather than this script, which the configuration cache cannot store.
    val suffix = versionSuffix
    val output = versionSuffixResources.map { it.file("files/version-suffix.txt").asFile }
    inputs.property("suffix", suffix)
    outputs.dir(versionSuffixResources)
    doLast {
        val file = output.get()
        file.parentFile.mkdirs()
        file.writeText(suffix)
    }
}
val commonComposeResources = layout.buildDirectory.dir("generated/composeResources/commonMain")
val syncCommonComposeResources = tasks.register<Sync>("syncCommonComposeResources") {
    from(layout.projectDirectory.dir("src/commonMain/composeResources"))
    from(generateVersionSuffix)
    into(commonComposeResources)
}

compose.resources {
    packageOfResClass = "dev.jamesrich.sofaking.resources"
    customDirectory(sourceSetName = "commonMain", directoryProvider = syncCommonComposeResources.map { commonComposeResources.get() })
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

compose.dependencyCompatibility {
    // Coil asks for an older Skiko than this Compose; Gradle resolves the newer one, and Coil moves with the next
    // Compose it builds against. Without this every target's compile prints the mismatch.
    exclude("io.coil-kt.coil3")
}

compose.desktop {
    application {
        mainClass = "dev.jamesrich.sofaking.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // From `suggestRuntimeModules`; DataStore's protobuf needs jdk.unsupported, and the trimmed runtime lacks it.
            modules("java.instrument", "java.management", "jdk.unsupported")
            packageName = "Sofa King"
            packageVersion = appVersion
            description = "Live TV, recordings and your own movies and shows from your Sofa King server"
            vendor = "James Rich"
            copyright = "Copyright 2026 James Rich. Licensed under the GPL-3.0-only."
            licenseFile.set(layout.settingsDirectory.file("../LICENSE"))
            // The icons come from tools/render-icons.sh.
            val icons = layout.projectDirectory.dir("icons")
            linux {
                packageName = "sofa-king"
                // dpkg orders 0.1.0-dev.BUILD+COMMIT by build number and after a plain 0.1.0, so each snapshot upgrades the last.
                debPackageVersion = appVersion + versionSuffix
                debMaintainer = "2199651+jamesarich@users.noreply.github.com"
                menuGroup = "AudioVideo;Video;TV"
                appCategory = "video"
                shortcut = true
                iconFile.set(icons.file("sofa-king.png"))
            }
            windows {
                menuGroup = "Sofa King"
                menu = true
                shortcut = true
                perUserInstall = true
                // Fixed so each MSI upgrades the installed app in place.
                upgradeUuid = "4b04060d-b077-47c9-acc6-f55b71d52d1f"
                iconFile.set(icons.file("sofa-king.ico"))
            }
            macOS {
                bundleID = "dev.jamesrich.sofaking"
                dockName = "Sofa King"
                appCategory = "public.app-category.entertainment"
                iconFile.set(icons.file("sofa-king.icns"))
                // jpackage refuses a 0 major on macOS, so it gets a 1.x stand-in; the later plist key wins and shows the
                // real version, and CFBundleVersion carries the build number.
                packageVersion = if (appVersion.startsWith("0.")) "1" + appVersion.removePrefix("0") else appVersion
                packageBuildVersion = providers.gradleProperty("sofaking.versionCode").get()
                infoPlist {
                    extraKeysRawXml = "<key>CFBundleShortVersionString</key><string>$appVersion</string>"
                }
            }
        }
    }
}

// The player is libVLC, which the package cannot bundle; the .deb has apt install VLC with it.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) freeArgs.addAll("--linux-package-deps", "vlc")
}
