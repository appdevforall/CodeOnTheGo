plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35
    namespace = "org.appdevforall.layouteditor"

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

    implementation (platform("io.github.Rosemoe.sora-editor:bom:0.23.5"))
    implementation ("io.github.Rosemoe.sora-editor:editor")
    implementation ("io.github.Rosemoe.sora-editor:language-textmate")

    implementation ("org.apache.commons:commons-text:1.11.0")

    implementation ("commons-io:commons-io:2.15.1")
}
