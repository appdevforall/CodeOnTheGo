import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	// Bundled with the Kotlin Gradle plugin already on the classpath, so apply without a version.
	alias(libs.plugins.kotlin.parcelize)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.privserv"

	buildFeatures {
		aidl = true
	}
}

dependencies {
	api(projects.subprojects.shizukuApi)
	implementation(projects.subprojects.hiddenApisCompat)

	api(libs.androidx.annotation)

	implementation(libs.rikka.hidden.compat)
	// compileOnly: packaged stubs make R8 treat framework interfaces as unimplemented app classes
	// and rewrite calls through them to throws.
	compileOnly(libs.rikka.hidden.stub)
	implementation(libs.rikka.refine.runtime)
}
