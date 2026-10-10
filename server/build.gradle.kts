plugins { application; alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
application { mainClass = "dev.jamesrich.sofaking.backend.MainKt" }
dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:${libs.versions.kotlin.get()}"))
    implementation(project(":api-contract"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client)
    implementation(libs.ktor.cio)
    implementation(libs.ktor.netty)
    implementation(libs.ktor.json)
    implementation(libs.ktor.negotiation)
    implementation(libs.ktor.status)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.test)
    testImplementation(libs.ktor.mock)
    testImplementation(project(":client"))
}
dependencyLocking { lockAllConfigurations() }
