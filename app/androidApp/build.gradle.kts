plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
    id("sofaking.kotlin")
    id("sofaking.lint")
    id("sofaking.android")
}

android {
    namespace = "dev.jamesrich.sofaking"

    defaultConfig {
        applicationId = "dev.jamesrich.sofaking"
        versionCode = providers.gradleProperty("sofaking.versionCode").get().toInt()
        versionName = libs.versions.sofaking.get() +
            providers.gradleProperty("sofaking.versionSuffix").getOrElse("")
        // The touch tests in src/androidTest, against the fixture server (docs/development.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each test starts from a fresh install's data: a random walk can change settings the next test relies on.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    // AGP's Compose Preview tasks fail to register without this and the unit tests androidComponents enables below.
    // Android Studio's sync registers every task, so dropping either one breaks the sync.
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            isIncludeAndroidResources = true
        }
        screenshotTests.create("screenshotTest") {
            engineVersion = libs.versions.screenshot.get()
            targetVariants.add("debug")
            dependencies {
                implementation(libs.compose.ui.tooling)
                implementation(libs.screenshot.validation.api)
            }
        }
    }

    // The release key, from Gradle properties or the environment (docs/development.md#signing). Without it, release
    // builds keep the debug key so a local assembleRelease still works.
    fun signingValue(name: String) =
        providers.gradleProperty(name).orElse(providers.environmentVariable(name.uppercase().replace('.', '_')))
    val releaseKeystore = signingValue("sofaking.keystore.path").orNull?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("sofaking.keystore.password").get()
                keyAlias = signingValue("sofaking.key.alias").get()
                // A PKCS12 keystore, keytool's default, has one password for the store and the key.
                keyPassword = signingValue("sofaking.key.password")
                    .orElse(signingValue("sofaking.keystore.password"))
                    .get()
            }
        }
    }

    buildTypes {
        release {
            // Debug builds compose an 8 s first guide frame on a Chromecast; R8 + non-debuggable is the TV build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
}

// Compose Preview needs unit tests on every variant; see testOptions above.
androidComponents {
    beforeVariants { variantBuilder ->
        variantBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
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
    implementation(libs.androidx.tvprovider)
    implementation(libs.engage.tv)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime)
    baselineProfile(project(":baselineprofile"))
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
}
