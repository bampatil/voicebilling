plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicebilling.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicebilling.app"
        minSdk = 26
        targetSdk = 34
        // Phones from the last ~8 years (Pixel, Samsung S/A/M series) are 64-bit ARM; keeps the app small.
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 6
        versionName = "0.4.2"
    }

    // Fixed signing key so every new build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// One library: sherpa-onnx (Apache-2.0) runs the offline speech model on the phone.
// The workflow downloads the official file from github.com/k2-fsa/sherpa-onnx releases into app/libs.
dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
}
