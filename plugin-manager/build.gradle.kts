import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.plugins.manager"

	lint {
		abortOnError = false
	}
}

dependencies {
	api(projects.pluginApi)

	implementation(projects.actions)
	implementation(projects.common)
	implementation(projects.logger)
	implementation(projects.lookup)
	implementation(projects.preferences)
	implementation(projects.resources)
	implementation(projects.idetooltips)
	implementation(projects.shared)
	implementation(projects.subprojects.projects)

	implementation(libs.androidx.appcompat)
	implementation(libs.gson.v2101)
	implementation(libs.brotli4j)
	implementation(libs.commons.compress)
	implementation(libs.tukaani.xz)
}
