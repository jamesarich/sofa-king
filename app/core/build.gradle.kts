import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    id("sofaking.kotlin")
    id("sofaking.android")
}

kotlin {
    android {
        namespace = "dev.jamesrich.sofaking.core"
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
    // Tests run on Node; browsers get the same klibs through the ui module's web apps. Mocha's 2-second default failed
    // a 90 ms test on a busy CI runner, where the first logged stack trace waits for its source maps.
    js { nodejs { testTask { useMocha { timeout = "30s" } } } }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs { testTask { useMocha { timeout = "30s" } } } }

    // `fileMain` holds the stores that live in files, which need okio's system file system; the web targets keep theirs in
    // the browser's storage instead (BrowserStorage), and have no file system to test against on Node. The default
    // `native` group sits under it as well as under `common`, so `nativeMain` and the Apple source sets see the stores.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("file") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
                group("native") { withNative() }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.lifecycle.viewmodel)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
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
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jmdns)
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
