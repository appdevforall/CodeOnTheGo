import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

description = "SQLite debugger for debug builds of apps built with Code on the Go (ADFA-7131)"

android {
	namespace = "org.appdevforall.sqlitedebugger"
	ndkVersion = BuildConfig.NDK_VERSION

	defaultConfig {
		// requery's minSdk, not MIN_SDK_FOR_APPS_BUILT_WITH_COGO: the debugger runs on requery's SQLite
		minSdk = 21
		// One AAR for both ABIs of the phones apps built with CoGo run on, unlike CoGo's own
		// modules: this module has no v7/v8 flavors (AndroidModuleConf).
		ndk {
			abiFilters.clear()
			abiFilters += listOf("arm64-v8a", "armeabi-v7a")
		}
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
			}
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	buildFeatures {
		viewBinding = false
		buildConfig = false
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.31.0+"
		}
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
		// Apps compile against this library with Code on the Go's on-device Kotlin compiler,
		// which can be older than the one that builds it (as in :plugin-api).
		apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
		languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
	}
}

dependencies {
	// Android provides org.json on a device
	testImplementation(libs.org.json)
	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
}
