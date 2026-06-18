plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
}

android {
	namespace = "android.llama.cpp"
	compileSdk = 34

	defaultConfig {
		minSdk = 26  // Match app minSdk to avoid manifest merge conflicts
		targetSdk = 34
		consumerProguardFiles("proguard-rules.pro")
		ndk {
			// Add NDK properties if wanted, e.g.
			// abiFilters += listOf("arm64-v8a")
		}
		externalNativeBuild {
			cmake {
				arguments += "-DLLAMA_CURL=OFF"
				arguments += "-DLLAMA_BUILD_COMMON=ON"
				arguments += "-DGGML_LLAMAFILE=OFF"
				arguments += "-DCMAKE_BUILD_TYPE=Release"
				cppFlags += listOf()
				arguments += listOf()

				cppFlags("")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}
	externalNativeBuild {
		cmake {
			path("src/main/cpp/CMakeLists.txt")
			version = "3.22.1"
		}
	}

	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}
}

dependencies {
	implementation(project(":llama-api"))
	implementation(libs.androidx.core.ktx.v1120)
	implementation(libs.androidx.appcompat.v171)
	implementation(libs.tooling.slf4j)
}
