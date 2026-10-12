plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.jamesrich.sofaking"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.jamesrich.sofaking"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = providers.gradleProperty("sofaking.version").get()
    }

    buildFeatures {
        compose = true
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
}
