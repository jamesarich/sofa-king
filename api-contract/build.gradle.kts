plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:${libs.versions.kotlin.get()}"))
    implementation(libs.serialization.json)
}
dependencyLocking { lockAllConfigurations() }
