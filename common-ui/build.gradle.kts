plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    id("kotlin-kapt")
}

android {
    namespace = "org.appdevforall.codeonthego.common.ui"
}

dependencies {
    implementation(libs.google.material)
    implementation(projects.common)
    implementation(projects.idetooltips)
}
