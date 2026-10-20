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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

// In AGP 9.5 with Compose enabled, GenerateComposePreviewRunfilesTask requires unit tests to be enabled
// for all variants so that Android XML resources can be packaged for Layoutlib.
androidComponents {
    beforeVariants { variantBuilder ->
        variantBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}
