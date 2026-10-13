import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.android.library)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.security"
}

dependencies {
	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.robolectric)
}
