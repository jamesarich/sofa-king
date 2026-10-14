// Generates androidApp's Baseline Profile on a connected device:
// ./gradlew :androidApp:generateBaselineProfile. See docs/development.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
    id("sofaking.kotlin")
    id("sofaking.android")
}

android {
    namespace = "dev.jamesrich.sofaking.baselineprofile"
    targetProjectPath = ":androidApp"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

baselineProfile {
    // A connected TV or phone; nothing in CI generates the profile.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
