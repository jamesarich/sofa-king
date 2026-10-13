plugins { application; alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
application { mainClass = "dev.jamesrich.sofaking.backend.MainKt" }
dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:${libs.versions.kotlin.get()}"))
    implementation("dev.jamesrich.sofaking:contract")
    implementation(libs.serialization.json)
    implementation(libs.coroutines)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client)
    implementation(libs.ktor.cio)
    implementation(libs.ktor.netty)
    implementation(libs.ktor.json)
    implementation(libs.ktor.negotiation)
    implementation(libs.ktor.status)
    implementation(libs.ktor.compression)
    implementation(libs.ktor.body.limit)
    implementation(libs.ktor.rate.limit)
    implementation(libs.ktor.call.id)
    implementation(libs.ktor.cors)
    implementation(libs.ktor.call.logging)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite)
    implementation(libs.kotlinx.html)
    implementation(libs.logback.classic)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.test)
    testImplementation(libs.ktor.mock)
}
dependencyLocking { lockAllConfigurations() }

// Real responses must keep every field of the wire fixtures the contract's tests decode. The provider keeps the
// absolute path out of the test task's cache key.
abstract class WireFixtures : CommandLineArgumentProvider {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dir: DirectoryProperty
    override fun asArguments() = listOf("-Dsofaking.wireFixtures=${dir.get().asFile.absolutePath}")
}
tasks.test {
    jvmArgumentProviders += objects.newInstance<WireFixtures>().apply {
        dir.set(rootProject.layout.projectDirectory.dir("../contract/src/jvmTest/resources/wire"))
    }
}
