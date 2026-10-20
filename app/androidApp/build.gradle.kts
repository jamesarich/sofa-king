plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
    id("sofaking.kotlin")
    id("sofaking.android")
}

android {
    namespace = "dev.jamesrich.sofaking"

    defaultConfig {
        applicationId = "dev.jamesrich.sofaking"
        versionCode = providers.gradleProperty("sofaking.versionCode").get().toInt()
        versionName = libs.versions.sofaking.get() +
            providers.gradleProperty("sofaking.versionSuffix").getOrElse("")
    }

    buildTypes {
        release {
            // Debug builds compose an 8 s first guide frame on a Chromecast; R8 + non-debuggable is the TV build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

baselineProfile {
    // R8 lays out the first dex file from the startup profile; without this the startup profile only feeds ART.
    dexLayoutOptimization = true
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}
