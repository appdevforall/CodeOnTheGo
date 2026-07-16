import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	// Bundled with the Kotlin Gradle plugin already on the classpath, so apply without a version.
	id("org.jetbrains.kotlin.plugin.parcelize")
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
	implementation(libs.rikka.hidden.stub)
	implementation(libs.rikka.refine.runtime)
}
