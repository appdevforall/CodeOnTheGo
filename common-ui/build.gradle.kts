import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    id("kotlin-kapt")
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.common.ui"
}

dependencies {
    implementation(libs.google.material)
    implementation(projects.common)
    implementation(projects.idetooltips)
}
