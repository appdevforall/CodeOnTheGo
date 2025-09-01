import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    id("kotlin-kapt")
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.idetooltips"
}

dependencies {
    kapt(libs.room.compiler)

    implementation(libs.room.ktx)
    implementation(libs.google.gson)
    implementation(libs.google.guava)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    implementation(projects.resources)
    implementation(projects.common)
}
