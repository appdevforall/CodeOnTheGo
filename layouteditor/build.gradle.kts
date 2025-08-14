plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.appdevforall.codeonthego.layouteditor"

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(projects.vectormaster)
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
    implementation(platform(libs.sora.bom))
    implementation(libs.common.editor)
    implementation(libs.sora.language.textmate)

    implementation(libs.commons.text)
    implementation(libs.common.io)

}
