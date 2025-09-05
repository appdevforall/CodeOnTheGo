plugins {
	id("com.android.library")
}

android {
	namespace = "rikka.shizuku.common"
	buildFeatures {
		buildConfig = false
	}
}

dependencies {
	compileOnly(libs.rikka.hidden.stub)
	api(libs.rikka.hidden.compat)
}
