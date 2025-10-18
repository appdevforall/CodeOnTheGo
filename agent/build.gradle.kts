import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    id("kotlin-kapt")
    kotlin("plugin.serialization")
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.agent"
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(projects.common)
    implementation(projects.commonUi)
    implementation(projects.subprojects.projects)
    implementation(projects.idetooltips)
    implementation(projects.resources)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.nav.fragment)
    implementation(libs.androidx.nav.ui)

    implementation(libs.google.material)
    implementation(libs.common.kotlin.coroutines.android)
    implementation("io.insert-koin:koin-android:3.5.3")
    implementation(libs.common.markwon.core)
    implementation(libs.common.markwon.linkify)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(libs.tooling.slf4j)
}
