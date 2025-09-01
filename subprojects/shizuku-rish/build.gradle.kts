@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
}

android {
	namespace = "rikka.rish"
	ndkVersion = BuildConfig.ndkVersion

	defaultConfig {
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
			}
		}
	}
	buildFeatures {
		prefab = true
	}
	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.31.0+"
		}
	}
}

dependencies {
	implementation(projects.subprojects.shizukuApi)
	implementation(libs.androidx.annotation)

	//noinspection UseTomlInstead
	implementation("org.lsposed.libcxx:libcxx:${BuildConfig.ndkVersion}")
}
