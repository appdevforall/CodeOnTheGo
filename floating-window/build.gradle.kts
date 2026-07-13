

import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.floating"

	buildFeatures {
		compose = true
	}
}

dependencies {
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.runtime)
	implementation(libs.compose.ui)
	implementation(libs.compose.foundation)
	implementation(libs.compose.material3)
	implementation(libs.compose.activity)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)

	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.google.material)

	implementation(projects.editorApi)
	implementation(projects.common)
	implementation(projects.resources)
	implementation(projects.logger)
}
