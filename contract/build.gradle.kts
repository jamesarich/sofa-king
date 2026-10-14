import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("sofaking.kotlin")
}

group = "dev.jamesrich.sofaking"
version = "0.1.0"

kotlin {
    // The server runs on Java 17; Android consumes this variant too.
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
    wasmJs { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
