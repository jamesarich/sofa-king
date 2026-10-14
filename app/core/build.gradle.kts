@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencyLocking { lockAllConfigurations() }

// Node and Binaryen resolve through the repositories declared in settings, which forbids project repositories.
plugins.withType<NodeJsPlugin> { the<NodeJsEnvSpec>().downloadBaseUrl = null }
plugins.withType<WasmNodeJsPlugin> { the<WasmNodeJsEnvSpec>().downloadBaseUrl = null }
plugins.withType<BinaryenPlugin> { the<BinaryenEnvSpec>().downloadBaseUrl = null }

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.jamesrich.sofaking.core"
        compileSdk { version = release(37) { minorApiLevel = 1 } }
        minSdk = 28
        withHostTest {}
    }
    jvm()

    // Every Apple, Linux and web target the dependency set publishes; Windows native is left out because
    // DataStore has no mingw build, and the JVM covers Windows desktops.
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    // The SwiftUI Apple TV app (tvosApp) links core directly, since Compose Multiplatform has no tvOS renderer.
    listOf(tvosArm64(), tvosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SofaKingCore"
            isStatic = true
        }
    }
    watchosArm64()
    watchosSimulatorArm64()
    linuxX64()
    linuxArm64()
    // Tests run on Node; browsers get the same klibs through the ui module's web apps.
    js { nodejs() }
    wasmJs { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.lifecycle.viewmodel)
            implementation(libs.kotlinx.serialization.json)
            implementation("dev.jamesrich.sofaking:contract")
            api(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            api(libs.datastore.preferences.core)
            implementation(libs.okio)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            implementation(libs.mockwebserver)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.curl)
        }
        webMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}
