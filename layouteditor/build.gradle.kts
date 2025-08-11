plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35
    namespace = "org.appdevforall.codeonthego.layouteditor"

    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":vectormaster"))
    implementation(projects.common)
    implementation(projects.uidesigner)

    implementation (libs.androidx.lifecycle.viewmodel.ktx)
    implementation (libs.androidx.activity.ktx)
    implementation (libs.androidx.appcompat)
    implementation (libs.androidx.constraintlayout)
    implementation (libs.androidx.core.ktx)
    implementation (libs.androidx.preference.ktx)
    implementation (libs.androidx.recyclerview)
    implementation (libs.androidx.viewpager2)
    implementation (libs.androidx.palette.ktx)
    implementation (libs.google.material)
    implementation (libs.google.material)
    implementation (libs.play.services.oss.licenses)
    implementation (libs.google.gson)
    implementation (libs.common.glide)

    implementation(libs.zoomage)
    implementation(libs.utilcodex)
    implementation(libs.zoomage)
    implementation(libs.utilcodex)
    implementation(libs.colorpickerview)
    implementation(libs.common.editor)
    implementation(libs.utilcodex)
    implementation(libs.utilcodex)

    implementation(platform(libs.sora.bom))
    implementation(libs.common.editor)
    implementation(libs.sora.language.textmate)

    implementation(libs.commons.text)
    implementation(libs.common.io)

}
