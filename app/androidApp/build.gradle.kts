plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.jamesrich.sofaking"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "dev.jamesrich.sofaking"
        minSdk = 28
        targetSdk = 37
        versionCode = providers.gradleProperty("sofaking.versionCode").get().toInt()
        versionName = providers.gradleProperty("sofaking.version").get() +
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

kotlin {
    jvmToolchain(21)
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)
}
