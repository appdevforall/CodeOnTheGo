import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.external"
}

dependencies {
	implementation(projects.lsp.api)
	implementation(projects.eventbusEvents)
	implementation(projects.shared)

	implementation(libs.common.lsp4j)
	implementation(libs.common.jsonrpc)
	implementation(libs.common.kotlin)
	implementation(libs.common.kotlin.coroutines.android)

	compileOnly(projects.common)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(projects.common)
}
