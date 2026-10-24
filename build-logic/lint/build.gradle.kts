plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    // Applied by class from the convention, so the builds that use it need not declare either plugin themselves.
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("lint") {
            id = "sofaking.lint"
            implementationClass = "dev.jamesrich.sofaking.gradle.LintConventionPlugin"
        }
    }
}
