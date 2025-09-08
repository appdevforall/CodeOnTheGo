plugins {
	id("com.android.library")
	id("dev.rikka.tools.refine")
}

android {
	namespace = "rikka.shizuku.starter"
	buildFeatures {
		buildConfig = false
	}
}

dependencies {
	implementation(projects.buildInfo)
	implementation(projects.subprojects.shizukuCommon)
	implementation(projects.subprojects.shizukuShared)
	implementation(projects.subprojects.shizukuServerShared)
	compileOnly(projects.subprojects.shizukuProvider)

	implementation(libs.androidx.annotation)
	implementation(libs.rikka.hidden.compat)
	implementation(libs.rikka.refine.runtime)
	compileOnly(libs.rikka.hidden.stub)
}
