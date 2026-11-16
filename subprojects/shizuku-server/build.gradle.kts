plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.rikka.refine)
}

android {
	namespace = "moe.shizuku.server"
	buildFeatures {
		buildConfig = false
	}
}

dependencies {
	implementation(libs.androidx.annotation)

	implementation(libs.google.gson)
	api(libs.rikkax.parcelablelist)

	implementation(projects.subprojects.shizukuAidl)
	implementation(projects.subprojects.shizukuCommon)
	implementation(projects.subprojects.shizukuShared)
	implementation(projects.subprojects.shizukuStarter)
	implementation(projects.subprojects.shizukuServerShared)
	implementation(libs.rikka.hidden.compat)

	compileOnly(projects.subprojects.shizukuProvider)
	compileOnly(libs.rikka.hidden.stub)
}
