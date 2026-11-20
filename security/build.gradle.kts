import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.android.library)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.security"
}

dependencies {
	implementation(libs.androidx.appcompat.v171)
	implementation(libs.androidx.core.ktx.v1120)
	implementation(libs.google.material)
	implementation(projects.preferences)
	implementation(projects.resources)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.robolectric)
}
