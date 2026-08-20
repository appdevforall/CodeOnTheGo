import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.common.compose"

	buildFeatures {
		compose = true
	}
}

dependencies {
	// api, not implementation: consumers write Compose against these types (ColorScheme, Typography),
	// so they must be on the consumer's compile classpath.
	api(platform(libs.compose.bom))
	api(libs.compose.runtime)
	api(libs.compose.material3)
	api(libs.compose.ui)

	implementation(libs.compose.foundation)
	implementation(libs.google.material)

	testImplementation(projects.testing.unit)
}
