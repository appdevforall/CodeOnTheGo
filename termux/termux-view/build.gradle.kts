import com.itsaky.androidide.build.config.BuildConfig

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.termux.view"
    ndkVersion = BuildConfig.NDK_VERSION
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(projects.resources)
    api(projects.termux.termuxEmulator)

    testImplementation(projects.testing.unit)
}