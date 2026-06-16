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
    implementation(projects.subprojects.privilegedServices)

    api(libs.androidx.annotation)
    api(libs.androidx.fragment)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.shark.android)
    implementation(libs.shark.hprof)
    implementation(libs.shark.graph)

    implementation(libs.rikka.hidden.compat)
    implementation(libs.rikka.hidden.stub)
}
