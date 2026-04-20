import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.indexing"
}

dependencies {
	api(libs.androidx.annotation)
	api(libs.androidx.sqlite.ktx)
	api(libs.androidx.sqlite.framework)
	api(libs.kotlinx.coroutines.core)

	api(projects.logger)

	testImplementation(projects.testing.unit)
	testImplementation(libs.tests.kotlinx.coroutines)
}
