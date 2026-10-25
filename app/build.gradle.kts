plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.pixelcallkeeper"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pixelcallkeeper"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
