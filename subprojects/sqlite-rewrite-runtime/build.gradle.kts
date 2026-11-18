plugins {
	id("com.android.library")
}

description = "Runtime helpers for classes rewritten from android.database.sqlite to requery (ADFA-7138)"

android {
	namespace = "org.appdevforall.sqliterewrite"

	defaultConfig {
		// requery's own minSdk (its AAR manifest says 21); apps rewritten to use it need no more
		minSdk = 21
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	buildFeatures {
		viewBinding = false
		buildConfig = false
	}
}

dependencies {
	// Not packaged: requery's AAR is a file dependency, which brings none of its own dependencies, so
	// androidx.core comes from the app (AppCompat and most androidx libraries depend on it)
	compileOnly(libs.androidx.core)
}
