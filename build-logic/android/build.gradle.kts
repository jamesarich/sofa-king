plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencyLocking { lockAllConfigurations() }

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("android") {
            id = "sofaking.android"
            implementationClass = "dev.jamesrich.sofaking.gradle.AndroidConventionPlugin"
        }
    }
}
