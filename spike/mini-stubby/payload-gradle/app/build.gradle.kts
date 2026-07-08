plugins { id("com.android.application") }

android {
    namespace = "app.payload"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.payload"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Non-transitive R keeps each library's R at its own package; combined with a
    // reserved package id, the whole payload table lives at 0x80 — disjoint from
    // the host shell's 0x7f, so ResourcesLoader can merge it without collision.
    androidResources {
        additionalParameters += listOf("--package-id", "0x80", "--allow-reserved-package-id")
    }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
