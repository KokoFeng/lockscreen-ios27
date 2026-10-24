plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ios27.chat"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ios27.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("chat-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures {
        buildConfig = false
        aidl = false
        renderScript = false
        shaders = false
        resValues = false
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.google.zxing:core:3.5.3")
}
