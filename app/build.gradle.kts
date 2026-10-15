import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The built-in software encoders (x264, x265) are optional: build with -Psoftenc=true after running
// scripts/build-codecs.sh. Without them the app uses the phone's hardware encoders only.
val softenc = (findProperty("softenc") as String?) == "true"

// Release signing comes from a properties file outside the repository (see README); without it, release builds are unsigned.
val signing = Properties().apply {
    val f = (findProperty("graden.signing") as String?)?.let { file(it) }
    if (f != null && f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.graden.editor"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.graden.editor"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-beta"
        if (softenc) {
            // x264 / x265 are built for 64-bit ARM, which every current Android phone uses.
            ndk { abiFilters += listOf("arm64-v8a") }
            externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
        }
    }
    if (softenc) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        ndkVersion = "27.2.12479018"
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Optimised like a release build (R8, not debuggable) but signed with the debug key, so it installs over the
        // development build without losing projects or media. Used to measure real-world smoothness.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isProfileable = true
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
}
