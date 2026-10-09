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
        versionName = "0.1.0"

        // Dispatcharr base URL and channel profile; override with -PdispatcharrUrl=... -PdispatcharrProfile=...
        buildConfigField("String", "DISPATCHARR_URL", "\"${project.findProperty("dispatcharrUrl") ?: "http://192.168.1.3:9191"}\"")
        buildConfigField("String", "DISPATCHARR_PROFILE", "\"${project.findProperty("dispatcharrProfile") ?: "livingroom"}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
}
