import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
}

android {
	// The package the other tree-sitter grammars use, so the app's
	// `-keep class com.itsaky.androidide.treesitter.**` keeps the JNI class.
	namespace = "com.itsaky.androidide.treesitter.javascript"
	ndkVersion = BuildConfig.NDK_VERSION

	defaultConfig {
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
			}
		}
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.31.0+"
		}
	}
}

dependencies {
	api(libs.androidide.ts)
}
