plugins {
	id("com.android.library")
}

android {
	namespace = "rikka.shizuku.api"
	buildFeatures {
		buildConfig = false
	}
}

dependencies {
	api(projects.buildInfo)
	api(projects.subprojects.shizukuAidl)
	api(projects.subprojects.shizukuShared)
	implementation(libs.androidx.annotation)
}
