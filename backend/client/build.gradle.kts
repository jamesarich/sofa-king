plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:${libs.versions.kotlin.get()}"))
    api(project(":api-contract"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines)
    implementation(libs.ktor.client)
    implementation(libs.ktor.cio)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.mock)
}
dependencyLocking { lockAllConfigurations() }
