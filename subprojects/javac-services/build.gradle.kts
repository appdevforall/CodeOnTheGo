import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.javac.services"

	buildTypes {
		release {
			isMinifyEnabled = false
		}
	}
}

dependencies {
	implementation(libs.common.kotlin)
	implementation(libs.common.utilcode)
	implementation(libs.google.guava)
	implementation(projects.common)
	implementation(projects.logger)

	api(libs.composite.javac)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.robolectric)
}
