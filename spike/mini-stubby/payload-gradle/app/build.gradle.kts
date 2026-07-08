plugins {
    id("com.android.application")
    kotlin("android") version "2.0.21"
    // Kotlin 2.0+ ships the Compose compiler as a first-party plugin whose version
    // tracks Kotlin exactly — no more kotlinCompilerExtensionVersion matching game.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
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
    buildFeatures { buildConfig = false; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
}
