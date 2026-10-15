plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
// The catalog accessor exists only in this script, so the version is read once and handed to every project.
val sofaKingVersion = libs.versions.sofaking.get()
allprojects {
    group = "dev.jamesrich.sofaking"
    version = sofaKingVersion
}
