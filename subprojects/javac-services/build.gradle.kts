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
	implementation(libs.google.guava)
	implementation(projects.common)
	implementation(projects.logger)

	// The actual javac fork this module wraps -- bundled with this module wherever it ends up
	// (isolated carrier, per ADFA-5053).
	api(libs.composite.jdkCompiler)

	// Resident (see docs/adr/0012): must never be duplicated into the isolated carrier, so
	// these are compileOnly even though this module's own code (ReusableContext.kt, etc.)
	// references their types directly.
	compileOnly(libs.composite.javaCompiler)
	compileOnly(projects.subprojects.javacFs)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.robolectric)
}
