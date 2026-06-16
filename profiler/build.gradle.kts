import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.profiler"
}

dependencies {
    api(projects.actions)
    api(libs.androidx.fragment)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)
}