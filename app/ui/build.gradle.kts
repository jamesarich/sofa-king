import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    id("sofaking.kotlin")
    id("sofaking.android")
    id("sofaking.lint")
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
            |
            """.trimMargin(),
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
        // Coil's JS decoder loads skiko, whose glue writes to `window`; give Node one so the test binary loads. Mocha's
        // 2-second default is too short on a busy CI runner, as in core.
        nodejs {
            testTask {
                nodeJsArgs += listOf("--import", "data:text/javascript,globalThis.window ??= globalThis")
                useMocha { timeout = "30s" }
            }
        }
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
                implementation(libs.compose.material3.adaptive.navigation3)
                implementation(libs.compose.material3.adaptive.navigation.suite)
                implementation(libs.compose.material.icons.core)
                implementation(libs.navigationevent.compose)
                implementation(libs.navigation3.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.aboutlibraries.compose.m3)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.lifecycle.viewmodel.navigation3)
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
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

// The snapshot suffix, `-dev.BUILD+COMMIT` or empty, read by the About row at runtime, which trims it. A custom resource
// directory replaces the source set's default one, so the common resources are synced beside the generated file. The
// file ends in a newline so it is never empty: Compose's iOS reader throws on an empty resource (it pins element 0 of
// the empty array), which stopped the app on opening About in every build without a suffix.
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
        file.writeText("$suffix\n")
    }
}
// The open-source licenses screen's list: every library each target ships, with the targets that ship it, so a
// platform lists only its own. The license texts come from SPDX at build time, and the build cache keeps the result.
aboutLibraries {
    collect { includeTargets = true }
    export {
        excludeFields.addAll("funding")
        prettyPrint = false
    }
}

val commonComposeResources = layout.buildDirectory.dir("generated/composeResources/commonMain")
val syncCommonComposeResources = tasks.register<Sync>("syncCommonComposeResources") {
    from(layout.projectDirectory.dir("src/commonMain/composeResources"))
    from(generateVersionSuffix)
    from(tasks.named("exportLibraryDefinitions")) { into("files") }
    into(commonComposeResources)
}

compose.resources {
    packageOfResClass = "dev.jamesrich.sofaking.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = syncCommonComposeResources.map {
            commonComposeResources.get()
        },
    )
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

compose.dependencyCompatibility {
    // Coil asks for an older Skiko than this Compose; Gradle resolves the newer one, and Coil moves with the next
    // Compose it builds against. Without this every target's compile prints the mismatch.
    exclude("io.coil-kt.coil3")
}

// Every package carries the catalog version, and a snapshot's carry its build number too, so each installs over the
// last: the .deb takes the whole suffix (0.1.0-dev.42+abc1234, which apt orders by the build number), and the MSI,
// which allows only numbers, takes the build number as its last field. jpackage refuses a macOS version whose first
// number is 0, so the app bundle is built as 1.x, and a later CFBundleShortVersionString, which macOS reads instead,
// shows the catalog version; its build version ends in the build number.
val buildNumber = providers.gradleProperty("sofaking.versionCode").get()
fun withBuild(version: String) = if (versionSuffix.isEmpty()) {
    version
} else {
    version.substringBeforeLast('.') +
        ".$buildNumber"
}
val macVersion = appVersion.let { if (it.startsWith("0.")) "1.${it.removePrefix("0.")}" else it }

compose.desktop {
    application {
        mainClass = "dev.jamesrich.sofaking.MainKt"
        // `:ui:run -Psofaking.tv=true` lays the window out as a TV, with the keyboard as the remote.
        providers.gradleProperty("sofaking.tv").orNull?.let { jvmArgs("-Dsofaking.tv=$it") }
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
                debPackageVersion = appVersion + versionSuffix
                debMaintainer = "2199651+jamesarich@users.noreply.github.com"
                menuGroup = "AudioVideo;Video;TV"
                appCategory = "video"
                shortcut = true
                iconFile.set(icons.file("sofa-king.png"))
            }
            windows {
                msiPackageVersion = withBuild(appVersion)
                menuGroup = "Sofa King"
                menu = true
                shortcut = true
                perUserInstall = true
                // Fixed so each MSI upgrades the installed app in place.
                upgradeUuid = "4b04060d-b077-47c9-acc6-f55b71d52d1f"
                iconFile.set(icons.file("sofa-king.ico"))
            }
            macOS {
                packageVersion = macVersion
                packageBuildVersion = withBuild(macVersion)
                infoPlist { extraKeysRawXml = "<key>CFBundleShortVersionString</key><string>$appVersion</string>" }
                bundleID = "dev.jamesrich.sofaking"
                dockName = "Sofa King"
                appCategory = "public.app-category.entertainment"
                iconFile.set(icons.file("sofa-king.icns"))
            }
        }
    }
}

// The player is libVLC, which the package cannot bundle; the .deb has apt install VLC with it.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) freeArgs.addAll("--linux-package-deps", "vlc")
}

// A hung UI test fails its task instead of running into the CI job's timeout, so the reports still upload.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(15))
}
