plugins {
	id("com.android.library")
}

android {
	namespace = "rikka.shizuku.provider"

	defaultConfig {
		consumerProguardFiles("consumer-rules.pro")
	}

	buildFeatures {
		buildConfig = false
	}
}

dependencies {
	implementation(projects.subprojects.shizukuApi)
	implementation(libs.androidx.annotation)
}
