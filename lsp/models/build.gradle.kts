import com.itsaky.androidide.build.config.BuildConfig

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.lsp.models"
}

dependencies {
    implementation(libs.composite.fuzzysearch)

    implementation(projects.common)

    implementation(platform(libs.sora.bom))
    implementation(libs.common.editor)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.common.kotlin)
    implementation(libs.common.utilcode)
}