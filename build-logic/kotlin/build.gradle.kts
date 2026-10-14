plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlin") {
            id = "sofaking.kotlin"
            implementationClass = "dev.jamesrich.sofaking.gradle.KotlinConventionPlugin"
        }
    }
}
