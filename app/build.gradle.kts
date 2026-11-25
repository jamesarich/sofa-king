buildscript {
    dependencies {
        // The Android Gradle plugin, lint and the Play tooling bring these in older than their advisories' fixes; only the
        // build runs them, the shipped app and the server do not. Remove a line once the plugin's own version is past it.
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.87")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.87")
            classpath("org.bouncycastle:bcutil-jdk18on:1.87")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.commons:commons-lang3:3.22.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("org.apache.httpcomponents:httpmime:4.5.14")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.hot.reload) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.aboutlibraries) apply false
    id("sofaking.kotlin")
    id("sofaking.lint")
}
