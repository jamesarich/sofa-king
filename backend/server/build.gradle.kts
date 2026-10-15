plugins { application; alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); id("sofaking.kotlin") }
application { mainClass = "dev.jamesrich.sofaking.backend.MainKt" }
dependencies {
    implementation("dev.jamesrich.sofaking:contract")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.html)
    implementation(libs.logback.classic)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

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
