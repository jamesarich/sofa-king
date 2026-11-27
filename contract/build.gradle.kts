import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("sofaking.kotlin")
    id("sofaking.lint")
}

group = "dev.jamesrich.sofaking"
version = libs.versions.sofaking.get()

kotlin {
    // Java 17 bytecode, the lowest the JVM server and Android both load; the JDK 21 toolchain compiles it.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Every target the app's core module builds, except android.
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm64()
    watchosSimulatorArm64()
    linuxX64()
    linuxArm64()
    js { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() }

    // The Kotlin Toolchain layout: src/ is common code, test@jvm/ and testResources@jvm/ are the JVM tests.
    sourceSets {
        commonMain { kotlin.setSrcDirs(listOf("src")) }
        jvmTest {
            kotlin.setSrcDirs(listOf("test@jvm"))
            resources.setSrcDirs(listOf("testResources@jvm"))
        }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
