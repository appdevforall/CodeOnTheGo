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
    // Whole app+library resource table at 0x80 — disjoint from the host's 0x7f.
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
