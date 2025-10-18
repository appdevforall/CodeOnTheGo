import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    id("kotlin-kapt")
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.common.ui"
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.google.material)
    implementation(projects.common)
    implementation(projects.idetooltips)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(projects.resources)
}
