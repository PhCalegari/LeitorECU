plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.leitorecu"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.leitorecu"
        minSdk = 23
        targetSdk = 34
        versionCode = 14
        versionName = "1.13"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
