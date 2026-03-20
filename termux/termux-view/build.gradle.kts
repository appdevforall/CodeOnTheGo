import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.android.library)
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