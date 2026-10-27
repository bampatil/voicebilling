import java.net.URI

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
        versionCode = 8
        versionName = "0.4.4"
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
    // Both libraries above contain libonnxruntime.so (both 1.17.1). Keep one copy.
    packaging {
        jniLibs { pickFirsts += "**/libonnxruntime.so" }
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
// It and the small voice detector (silero_vad.onnx) are too big / not ours to keep in git, so they are
// downloaded from the official k2-fsa releases the first time the project is opened (Android Studio or CI).
// Already there = nothing is downloaded.
val speechFiles = mapOf(
    "libs/sherpa-onnx.aar" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-1.12.23.aar",
    "src/main/assets/silero_vad.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
)
for ((path, url) in speechFiles) {
    val f = file(path)
    if (!f.exists() || f.length() == 0L) {
        logger.lifecycle("Downloading $url")
        f.parentFile.mkdirs()
        val part = file("$path.part")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { out -> input.copyTo(out) } }
        part.renameTo(f)
    }
}

// The screen is written in web/page.html (layout + logic) and web/parser.js (understanding what was said).
// They are joined into the single file the app shows: app/src/main/assets/index.html. Edit the web/ files, not index.html.
val page = rootProject.file("web/page.html")
val parser = rootProject.file("web/parser.js")
if (page.exists() && parser.exists()) {
    val joined = page.readText().replace("/*PARSER*/", parser.readText())
    val out = file("src/main/assets/index.html")
    if (!out.exists() || out.readText() != joined) out.writeText(joined)
}

dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
    // ONNX Runtime's own Android library (MIT), same version as inside sherpa-onnx. Lets the app run the model
    // directly and read its rough guesses, which keyword listening needs. Test stage: see EngineCheck.kt.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
}
