plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "rikka.shizuku.aidl"
	buildFeatures {
		buildConfig = false
		aidl = true
	}
}
