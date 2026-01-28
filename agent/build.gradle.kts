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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.common)
    implementation(projects.commonUi)
    implementation(projects.subprojects.projects)
    implementation(projects.idetooltips)
    implementation(projects.resources)
    implementation(projects.llamaApi)

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
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation(libs.androidx.security.crypto)
    implementation(libs.google.genai)
    "v7Implementation"(files("../app/libs/v7/llama-v7-release.aar"))
    "v8Implementation"(files("../app/libs/v8/llama-v8-release.aar"))

    testImplementation(projects.testing.unit)
    testImplementation("io.mockk:mockk:1.13.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}
