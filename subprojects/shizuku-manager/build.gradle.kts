@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
	id("dev.rikka.tools.refine")
	id("dev.rikka.tools.materialthemebuilder")
}

android {
	namespace = "moe.shizuku.manager"
	ndkVersion = BuildConfig.NDK_VERSION

	defaultConfig {
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
			}
		}
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
		prefab = true
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/jni/CMakeLists.txt")
			version = "3.31.0+"
		}
	}
}

dependencies {
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.recyclerview)

	implementation(projects.common)
	implementation(projects.logger)
	implementation(projects.resources)
	implementation(projects.subprojects.shizukuServer)
	implementation(projects.subprojects.shizukuStarter)
	implementation(projects.subprojects.shizukuApi)
	implementation(projects.subprojects.shizukuProvider)

	implementation(libs.rikka.hidden.compat)
	implementation(libs.rikkax.htmlktx)
	compileOnly(libs.rikka.hidden.stub)

	implementation(libs.libsu.core)
	implementation(libs.common.hiddenApiBypass)
	implementation(libs.boringssl)
	implementation(libs.bcpkix.jdk18on)

	//noinspection UseTomlInstead
	implementation("org.lsposed.libcxx:libcxx:${BuildConfig.NDK_VERSION}")
}
