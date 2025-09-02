plugins {
	id("com.android.library")
}

android {
	namespace = "rikka.shizuku.shared"
}

dependencies {
	implementation(projects.subprojects.shizukuAidl)
	implementation(libs.androidx.annotation)
}
