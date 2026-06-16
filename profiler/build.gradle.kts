import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.profiler"

    buildFeatures {
        compose = true
    }
}

dependencies {
	api(projects.actions)
	implementation(projects.subprojects.privilegedServices)

	api(libs.androidx.annotation)
	api(libs.androidx.fragment)
    api(libs.androidx.fragment.ktx)
	api(libs.androidx.lifecycle.viewmodel.ktx)
	api(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.shark.android)
    implementation(libs.shark.hprof)
    implementation(libs.shark.graph)

    implementation(libs.rikka.hidden.compat)
    implementation(libs.rikka.hidden.stub)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.google.material)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
}
