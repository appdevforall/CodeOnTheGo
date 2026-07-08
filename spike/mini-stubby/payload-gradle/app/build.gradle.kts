plugins {
    id("com.android.application")
    kotlin("android") version "1.9.22"
}

android {
    namespace = "app.payload"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.payload"; minSdk = 30; targetSdk = 34
        versionCode = 1; versionName = "1.0"
    }
    androidResources {
        additionalParameters += listOf("--package-id", "0x80", "--allow-reserved-package-id")
    }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
}
